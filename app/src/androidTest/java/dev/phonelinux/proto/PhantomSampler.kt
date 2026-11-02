package dev.phonelinux.proto

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Phantom-process pressure sampler (instrumentation only - no product
 * behavior). Every number is labeled with what it IS; unavailable data
 * is recorded as explicit unknowns, never as zero.
 *
 * Execution-model constraints (empirically verified on this emulator,
 * API 34): UiDevice.executeShellCommand whitespace-splits the command
 * and execs the tokens directly - there is NO shell, so pipes ("|"),
 * quoting, semicolons, redirects and `sh -c` payloads all FAIL (observed
 * live: a "|" token reached dumpsys as an argument -> "Bad activity
 * command: |"). Every command below is therefore a single bare binary
 * with single-token arguments; anything needing pipes or filtering is
 * parsed in Kotlin from the command's full output instead.
 *
 *  - marker: a sleep process we spawn and re-observe in the own-uid
 *    /proc scan by REFLECTED PID. "marker-verified" calibrates ONLY that
 *    the own-uid scan is not silently empty; it says nothing about the
 *    global list. (The old exec -a naming scheme was never observed in
 *    any run and is not expressible without shell quoting, so the probe
 *    is dropped by design; the marker no longer depends on it.) The
 *    short marker can EXPIRE before a slow scan: an expired marker
 *    (zombie or gone) makes the calibration UNKNOWN and the own-uid
 *    counts UNCALIBRATED - raw values are kept and labeled, never
 *    reported as measured and never zeroed.
 *  - uid-procs: every own-uid /proc entry as pid:ppid:name. Includes
 *    reparented orphans a PPid-walk misses. Count INCLUDES the sampler's
 *    own marker process while alive (+1 transient) - sampler overhead,
 *    recorded so it can be subtracted, never hidden.
 *  - global records: a FULL bare `dumpsys activity` captured at most
 *    once per 15s and parsed in-process: PhantomProcessRecord lines
 *    between "All Active App Child Processes:" and the zombie section
 *    (active set), the zombie set likewise; OURS identified two ways -
 *    uid suffix "/u0a<appId>" and recorded parent pid == app pid (they
 *    can differ: the recorded parent is the app pid at record creation).
 *    The raw dump is preserved VERBATIM to files/phantom-dumpsys-raw.log
 *    (size-capped with an explicit marker) so dead/duplicate/history
 *    entries can be recounted by hand; the parsed counts are never the
 *    only artifact.
 *  - app-adj: /proc/self/oom_score_adj of the app process.
 *  - `dumpsys activity` runs under the shell uid, not the app's - it
 *    adds no app-attributed phantom processes, only transient host CPU.
 *    executeShellCommand offers NO timeout: each call's duration is
 *    measured and flagged SLOW beyond 12s, and the sampler thread is
 *    strictly sequential (one sample fully finishes before the next
 *    starts), so samples can never overlap or backlog; an overrun past
 *    the 5s cadence is written to the snapshot as explicit skipped
 *    beats, never silently absorbed.
 */
object PhantomSampler {
    private const val TAG = "PhantomSampler"
    private const val SNAPSHOT_FILE = "phantom-snapshots.log"
    private const val RAW_FILE = "phantom-dumpsys-raw.log"
    private const val GLOBAL_CADENCE_MS = 15_000L
    private const val RAW_CAP_BYTES = 120L * 1024 * 1024
    private const val SLOW_GLOBAL_MS = 12_000L
    @Volatile private var headerWritten = false
    @Volatile private var lastGlobalAtMs = 0L
    @Volatile private var rawCapped = false

    private data class Proc(val pid: Int, val ppid: Int, val name: String)

    private data class GlobalSnap(
        val available: Boolean,
        val unavailableReason: String,
        val activeRecords: Int,
        val zombieRecords: Int,
        val oursByUid: Int,
        val oursByParent: Int,
        val parentPidCounts: String,
        val dumpBytes: Int,
        val durationMs: Long,
        val rawState: String,
        val activeSection: String
    )

    fun sample(device: UiDevice, label: String): String {
        val wall = System.currentTimeMillis()
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()

        // Marker spawn (own-uid process we can re-identify by pid).
        var markerProc: Process? = null
        var markerPid = -1
        var markerSpawnErr = ""
        try {
            // Marker lifetime 3s (was 25s): stays OUT of the live
            // session's phantom-budget window - a 25s marker spawned
            // near launch survived into the trim window (run
            // 41361883549 phantom record pid=17588 /system/bin/sleep 25,
            // trimmed 23:41:15). NO hidden assumption that 3s covers
            // the scan: an expiry before/during the scan is DETECTED
            // below (zombie/absent state) and reported as calibration
            // UNKNOWN. Spawn hygiene only; marker semantics (own-uid
            // proc re-identified by pid) unchanged.
            markerProc = ProcessBuilder("/system/bin/sleep", "3").start()
            Thread.sleep(300)
            markerPid = pidOf(markerProc)
        } catch (t: Throwable) {
            markerSpawnErr = t.message ?: t.javaClass.simpleName
        }

        // Own-uid /proc scan.
        val procs = ArrayList<Proc>()
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: emptyArray()
        for (f in dir) {
            val pid = try { f.name.toInt() } catch (t: Throwable) { continue }
            val st = try { android.system.Os.stat(f.absolutePath) } catch (t: Throwable) { continue }
            if (st.st_uid != myUid) continue
            val statTxt = try { File(f, "stat").readText() } catch (t: Throwable) { continue }
            val close = statTxt.lastIndexOf(')')
            if (close < 0) continue
            val name = statTxt.substring(statTxt.indexOf('(') + 1, close)
            val rest = statTxt.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 2) continue
            val ppid = try { rest[1].toInt() } catch (t: Throwable) { continue }
            procs.add(Proc(pid, ppid, name))
        }

        // Marker state AT SCAN TIME: 'Z'/'X' = expired but not yet
        // reaped (still visible in /proc) - calibration UNKNOWN.
        val markerSpawnStart = if (markerPid > 0) startTimeOf(markerPid) else null
        var markerState: String? = null
        if (markerPid > 0) {
            val mst = try { File("/proc/" + markerPid + "/stat").readText() } catch (t: Throwable) { null }
            if (mst != null) {
                val mclose = mst.lastIndexOf(')')
                if (mclose >= 0) {
                    val mrest = mst.substring(mclose + 1).trim().split(Regex("\\s+"))
                    if (mrest.isNotEmpty()) markerState = mrest[0]
                }
            }
        }

        val appAdj = try { File("/proc/self/oom_score_adj").readText().trim() }
            catch (t: Throwable) { "unknown(read-failed)" }

        // Global phantom records: cadence-gated full dumpsys, parsed in
        // Kotlin (no pipes exist under this execution model).
        val doGlobal = wall - lastGlobalAtMs >= GLOBAL_CADENCE_MS
        var global: GlobalSnap? = null
        if (doGlobal) {
            lastGlobalAtMs = wall
            global = captureGlobal(device, myUid, myPid, label, wall)
        }

        // Marker terminate + REAP (bounded): destroy() asks politely;
        // SIGKILL by pid ONLY after re-verifying the pid still holds
        // the SPAWN-CAPTURED (starttime, uid) identity; the final
        // waitFor() reaps the child so no zombie leaks into later
        // samples or the phantom census.
        var markerCleanup = "not-spawned"
        if (markerProc != null) {
            try {
                markerProc.destroy()
                var end = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < end && markerProc.isAlive) Thread.sleep(50)
                if (markerProc.isAlive && markerPid > 0 && markerSpawnStart != null &&
                    startTimeOf(markerPid) == markerSpawnStart &&
                    try { android.system.Os.stat("/proc/" + markerPid).st_uid == myUid } catch (t: Throwable) { false }) {
                    try { android.os.Process.sendSignal(markerPid, android.system.OsConstants.SIGKILL) } catch (t: Throwable) { }
                    end = System.currentTimeMillis() + 2_000
                    while (System.currentTimeMillis() < end && markerProc.isAlive) Thread.sleep(50)
                }
                if (!markerProc.isAlive) {
                    try { markerProc.waitFor() } catch (t: Throwable) { }
                    markerCleanup = "terminated+reaped"
                } else {
                    markerCleanup = "STILL-ALIVE(unknown leftover state)"
                }
            } catch (t: Throwable) {
                markerCleanup = "cleanup-failed(unknown leftover state: " + (t.message ?: t.javaClass.simpleName) + ")"
                Log.w(TAG, "marker cleanup failed (unknown leftover state): " + t.message)
            }
        }

        val markerInScan = markerPid > 0 && procs.any { it.pid == markerPid }
        val markerStatus = when {
            markerProc == null -> "INSTRUMENT-FAILURE(marker spawn failed: " + markerSpawnErr + " - counts untrusted)"
            !markerInScan ->
                "CALIBRATION-UNKNOWN(marker pid " + markerPid + " absent from the own-uid scan - " +
                    "expired before the scan OR scan blind; own-uid counts uncalibrated)"
            markerState == "Z" || markerState == "X" || markerState == "x" ->
                "CALIBRATION-UNKNOWN(marker pid " + markerPid + " expired (state " + markerState +
                    ") before/during the scan; own-uid counts uncalibrated)"
            markerState == null ->
                "CALIBRATION-UNKNOWN(marker pid " + markerPid + " state unreadable; own-uid counts uncalibrated)"
            else -> "marker-verified"
        }
        val calibrated = markerStatus == "marker-verified"

        val summary = buildString {
            append("phantom-sample[").append(label).append("] wall=").append(wall)
            append(' ').append(markerStatus)
            append(" marker-cleanup=").append(markerCleanup)
            if (calibrated) {
                append(" uid-procs=").append(procs.size).append("(+marker if alive)")
            } else {
                append(" uid-procs=UNCALIBRATED(raw=").append(procs.size)
                    .append(" - not a measured count, never zero)")
            }
            val g = global
            if (g != null) {
                append(" global-active=")
                append(if (g.available) g.activeRecords.toString() else "unavailable(" + g.unavailableReason + ")")
                append(" global-ours-uid=").append(g.oursByUid)
                append(" global-ours-parent=").append(g.oursByParent)
                append(" global-zombie=").append(g.zombieRecords)
                append(" global-dur-ms=").append(g.durationMs)
                if (g.durationMs > SLOW_GLOBAL_MS) append(" SLOW-GLOBAL")
            } else {
                append(" global=skipped(cadence<15s)")
            }
            append(" app-pid=").append(myPid).append(" app-adj=").append(appAdj)
        }
        Log.i(TAG, summary)

        // Full detail block to the snapshot file (survives logcat
        // truncation and test failure; pulled into the evidence
        // artifact). The raw dumpsys text goes to its own file.
        val sb = StringBuilder()
        sb.append("=== phantom-sample[").append(label).append("] wall=").append(wall).append(" ===\n")
        maybeHeader(device, sb)
        sb.append("marker: ").append(markerStatus).append('\n')
        sb.append("marker-cleanup: ").append(markerCleanup).append('\n')
        sb.append("app: pid=").append(myPid).append(" uid=").append(myUid).append(" oom_score_adj=").append(appAdj).append('\n')
        sb.append("memory: ").append(memoryRecord()).append('\n')
        val g = global
        if (g != null) {
            sb.append("global: available=").append(g.available)
            if (!g.available) sb.append(" reason=").append(g.unavailableReason)
            sb.append(" active-records=").append(g.activeRecords)
                .append(" zombie-records=").append(g.zombieRecords)
                .append(" ours-by-uid-suffix=").append(g.oursByUid)
                .append(" ours-by-parent-pid=").append(g.oursByParent)
                .append(" parent-pids{").append(g.parentPidCounts).append("}")
                .append(" dump-bytes=").append(g.dumpBytes)
                .append(" dur-ms=").append(g.durationMs)
                .append(" raw=").append(g.rawState).append('\n')
            sb.append("global-active-section:\n").append(g.activeSection).append('\n')
        } else {
            sb.append("global: skipped (cadence < ").append(GLOBAL_CADENCE_MS).append("ms)\n")
        }
        if (calibrated) {
            sb.append("uid-procs(count=").append(procs.size).append(", includes sampler marker while alive):\n")
        } else {
            sb.append("uid-procs(UNCALIBRATED raw=").append(procs.size)
                .append(" - calibration unknown/failed; not a measured count, never zero):\n")
        }
        for (p in procs) sb.append("  ").append(p.pid).append(':').append(p.ppid).append(' ').append(p.name).append('\n')
        val dur = System.currentTimeMillis() - wall
        sb.append("sample-end wall=").append(System.currentTimeMillis()).append(" dur-ms=").append(dur)
        if (dur > 5_000) sb.append(" OVERRUN(skipped ").append((dur - 1) / 5_000).append(" beat(s) of the 5s cadence - explicit, not absorbed)")
        sb.append("\n\n")
        try {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, SNAPSHOT_FILE)
                .appendText(sb.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot file write failed: " + t.message)
        }
        return summary
    }

    /** Memory-pressure record per sample. Each source is independently
     *  guarded; an unreadable source is recorded as UNKNOWN, NEVER as
     *  0 - a fabricated zero would masquerade as measured pressure. */
    private fun memoryRecord(): String {
        val memAvail: String = try {
            val line = File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemAvailable:") }
            line?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.plus(" kB")
                ?: "UNKNOWN(no MemAvailable line)"
        } catch (t: Throwable) { "UNKNOWN(" + (t.message ?: t.javaClass.simpleName) + ")" }
        val psi: String = try {
            File("/proc/pressure/memory").readText().lineSequence()
                .firstOrNull { it.startsWith("some ") }?.trim()
                ?: "UNKNOWN(no some line)"
        } catch (t: Throwable) { "UNKNOWN(" + (t.message ?: t.javaClass.simpleName) + ")" }
        return "MemAvailable=" + memAvail + " psi-memory[" + psi + "]"
    }

    /** Full bare `dumpsys activity` (no pipes exist to filter it),
     *  parsed in-process. Counts ONLY PhantomProcessRecord lines inside
     *  the active/zombie sections; the verbatim raw text is preserved
     *  separately so nothing is lost to parsing assumptions. */
    private fun captureGlobal(device: UiDevice, myUid: Int, myPid: Int, label: String, wall: Long): GlobalSnap {
        val t0 = System.currentTimeMillis()
        val raw: String? = try { device.executeShellCommand("dumpsys activity") }
            catch (t: Throwable) { null }
        val durMs = System.currentTimeMillis() - t0
        if (raw.isNullOrEmpty()) {
            return GlobalSnap(false,
                if (raw == null) "executeShellCommand-threw" else "empty-output",
                0, 0, 0, 0, "", 0, durMs, "none", "")
        }
        val activeIdx = raw.indexOf("All Active App Child Processes:")
        val zombieIdx = raw.indexOf("All Zombie App Child Processes:")
        val activeEnd = when {
            activeIdx < 0 -> -1
            zombieIdx > activeIdx -> zombieIdx
            else -> raw.length
        }
        val activeSection = if (activeIdx >= 0) raw.substring(activeIdx, activeEnd) else ""
        val zombieSection = if (zombieIdx >= 0) raw.substring(zombieIdx) else ""
        val activeLines = activeSection.lines().filter { it.contains("PhantomProcessRecord{") }
        val zombieLines = zombieSection.lines().filter { it.contains("PhantomProcessRecord{") }

        // Ours, two independent identities. Record line shape observed in
        // prior dumps: "  PhantomProcessRecord{<hash> <pid>:<parentAppPid>:<procName>/u0a<appId>}".
        val uidSuffix = "/u0a" + (myUid % 100000)
        val oursUid = activeLines.count { it.contains(uidSuffix) }
        val pidPair = Regex("(\\d+):(\\d+):")
        val parentHist = HashMap<Int, Int>()
        var oursParent = 0
        for (ln in activeLines) {
            val m = pidPair.find(ln) ?: continue
            val parentPid = m.groupValues[2].toIntOrNull() ?: continue
            parentHist[parentPid] = (parentHist[parentPid] ?: 0) + 1
            if (parentPid == myPid) oursParent++
        }
        val parentStr = parentHist.entries.sortedByDescending { it.value }
            .take(8).joinToString(",") { "" + it.key + "=" + it.value }

        // Raw preservation, size-capped with an explicit in-file marker.
        var rawState = "none"
        try {
            val f = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, RAW_FILE)
            if (rawCapped) {
                rawState = "capped-earlier"
            } else if (f.length() + raw.length + 128 > RAW_CAP_BYTES) {
                rawCapped = true
                f.appendText("=== RAW-CAP reached at sample[" + label + "] wall=" + wall +
                    " - verbatim raw writes stopped here; parsed counts continue per sample ===\n")
                rawState = "cap-reached-at-this-sample"
            } else {
                f.appendText("=== raw-dumpsys[" + label + "] wall=" + wall +
                    " dur-ms=" + durMs + " chars=" + raw.length + " ===\n")
                f.appendText(raw)
                f.appendText("\n=== raw-end[" + label + "] ===\n")
                rawState = "written(chars=" + raw.length + ")"
            }
        } catch (t: Throwable) {
            rawState = "write-failed(" + (t.message ?: t.javaClass.simpleName) + ")"
        }

        val avail = activeIdx >= 0
        return GlobalSnap(avail,
            if (avail) "" else "no-active-section-header-in-dump",
            activeLines.size, zombieLines.size, oursUid, oursParent,
            parentStr, raw.length, durMs, rawState, activeSection.trimEnd())
    }

    /** One-time per process: exact image identity for AOSP source
     *  applicability. Single-token getprop calls only (the execution
     *  model has no shell); an empty value is an explicit unknown. */
    private fun maybeHeader(device: UiDevice, sb: StringBuilder) {
        if (headerWritten) return
        headerWritten = true
        sb.append("--- run-header ---\n")
        for (prop in listOf(
            "ro.build.fingerprint",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.build.display.id",
            "ro.product.model",
            "ro.build.version.security_patch"
        )) {
            val v = try { device.executeShellCommand("getprop " + prop)?.trim() }
                catch (t: Throwable) { null }
            sb.append(prop).append(" = ")
                .append(if (v.isNullOrEmpty()) "unavailable(empty-or-error)" else v)
                .append('\n')
        }
        sb.append("exec-a probe: dropped by design (not expressible without shell quoting; marker no longer depends on it)\n")
        sb.append("--- header-end ---\n")
    }

    /** android.jar's java.lang.Process stub has no pid(): reflect. */
    private fun pidOf(p: Process): Int =
        try { val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p) }
        catch (t: Throwable) { -1 }

    private fun startTimeOf(pid: Int): Long? {
        val stat = try { File("/proc/" + pid + "/stat").readText() } catch (t: Throwable) { return null }
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
        if (rest.size < 20) return null
        return try { rest[19].toLong() } catch (t: Throwable) { null }
    }
}
