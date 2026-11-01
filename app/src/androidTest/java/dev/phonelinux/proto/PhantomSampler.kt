package dev.phonelinux.proto

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Phantom-process pressure sampler (instrumentation only - no product
 * behavior). Every number is labeled with what it IS; unavailable data
 * is recorded as explicit unknowns with the failing rc, never as zero.
 *
 *  - marker: a sleep process we spawn and then re-observe in the own-uid
 *    /proc scan by REFLECTED PID (no exec -a naming: that scheme was
 *    never observed in any run, likely the emulator sh rejecting the
 *    rename - hypothesis, probed once per run via exec-a-rc). "marker-
 *    verified" calibrates ONLY that the own-uid scan is not silently
 *    empty; it says nothing about the global list.
 *  - uid-procs: every own-uid /proc entry as pid:ppid:name. Includes
 *    reparented orphans a PPid-walk misses. Count INCLUDES the sampler's
 *    own marker process while alive (+1 transient) - sampler overhead,
 *    recorded so it can be subtracted, never hidden.
 *  - global-phantom-lines: `dumpsys activity | grep -ci phantom` - a
 *    LINE-count proxy over the system phantom section, all uids; it
 *    includes section headers, so it is an upper bound on record count,
 *    not an exact count. Absent section => explicit unknown.
 *  - app-adj: /proc/self/oom_score_adj of the app process.
 *  - shell commands run under the shell uid, not the app's - they add no
 *    app-attributed phantom processes, only transient host CPU.
 *
 * Detail goes to files/phantom-snapshots.log (pulled into the evidence
 * artifact); logcat gets one short summary line per sample so long
 * sections are never truncated away.
 */
object PhantomSampler {
    private const val TAG = "PhantomSampler"
    private const val SNAPSHOT_FILE = "phantom-snapshots.log"
    @Volatile private var headerWritten = false

    private data class Proc(val pid: Int, val ppid: Int, val name: String)
    private data class ShellOut(val out: String, val rc: Int)

    fun sample(device: UiDevice, label: String): String {
        val wall = System.currentTimeMillis()
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()

        // Marker spawn (own-uid process we can re-identify by pid).
        var markerProc: Process? = null
        var markerPid = -1
        var markerSpawnErr = ""
        try {
            markerProc = ProcessBuilder("/system/bin/sleep", "25").start()
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

        val appAdj = try { File("/proc/self/oom_score_adj").readText().trim() }
            catch (t: Throwable) { "unknown(read-failed)" }

        // Global phantom section, bounded. rc: grep -c returns 1 on zero
        // matches - a real zero, recorded as 0 with its rc, not unknown.
        val countShell = shell(device, "timeout 3 dumpsys activity | grep -ci phantom; echo RC=" + "${'$'}?")
        val globalLines = when {
            countShell.rc == -1 -> "unknown(shell-failed)"
            countShell.rc == 0 || countShell.rc == 1 -> countShell.out
            else -> "unknown(rc=" + countShell.rc + ")"
        }
        val sectionShell = shell(device, "timeout 4 dumpsys activity | grep -i phantom | head -40; echo RC=" + "${'$'}?")

        // Marker cleanup, identity-verified before the signal.
        val markerSeen = markerPid > 0 && procs.any { it.pid == markerPid }
        if (markerProc != null) {
            try {
                markerProc.destroy()
                val end = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < end && markerProc.isAlive) Thread.sleep(100)
                if (markerProc.isAlive && markerPid > 0 &&
                    startTimeOf(markerPid) != null &&
                    try { android.system.Os.stat("/proc/" + markerPid).st_uid == myUid } catch (t: Throwable) { false }) {
                    try { android.os.Process.sendSignal(markerPid, android.system.OsConstants.SIGKILL) } catch (t: Throwable) { }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "marker cleanup failed (unknown leftover state): " + t.message)
            }
        }

        val markerStatus = when {
            markerProc == null -> "INSTRUMENT-FAILURE(marker spawn failed: " + markerSpawnErr + " - counts untrusted)"
            !markerSeen -> "INSTRUMENT-FAILURE(marker pid " + markerPid + " not observed - counts untrusted)"
            else -> "marker-verified"
        }

        val summary = "phantom-sample[" + label + "] wall=" + wall +
            " " + markerStatus +
            " uid-procs=" + procs.size + "(+marker if alive)" +
            " global-phantom-lines=" + globalLines +
            " app-pid=" + myPid + " app-adj=" + appAdj +
            " shell-rc=" + countShell.rc + "/" + sectionShell.rc
        Log.i(TAG, summary)

        // Full detail block to the snapshot file (survives logcat
        // truncation and test failure; pulled into the artifact).
        val sb = StringBuilder()
        sb.append("=== phantom-sample[").append(label).append("] wall=").append(wall).append(" ===\n")
        maybeHeader(device, sb)
        sb.append("marker: ").append(markerStatus).append('\n')
        sb.append("app: pid=").append(myPid).append(" uid=").append(myUid).append(" oom_score_adj=").append(appAdj).append('\n')
        sb.append("global-phantom-lines: ").append(globalLines).append(" (grep -ci proxy over all-uids section; upper bound incl. headers)\n")
        sb.append("global-section(rc=").append(sectionShell.rc).append("):\n").append(sectionShell.outFull).append('\n')
        sb.append("uid-procs(count=").append(procs.size).append(", includes sampler marker while alive):\n")
        for (p in procs) sb.append("  ").append(p.pid).append(':').append(p.ppid).append(' ').append(p.name).append('\n')
        sb.append("sample-end wall=").append(System.currentTimeMillis()).append("\n\n")
        try {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, SNAPSHOT_FILE)
                .appendText(sb.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot file write failed: " + t.message)
        }
        return summary
    }

    /** One-time per process: image identity for AOSP source applicability
     *  and the sh / exec -a probe resolving the old marker failure. */
    private fun maybeHeader(device: UiDevice, sb: StringBuilder) {
        if (headerWritten) return
        headerWritten = true
        val fp = shell(device,
            "getprop ro.build.fingerprint; getprop ro.build.version.release; " +
            "getprop ro.build.version.sdk; getprop ro.build.display.id; getprop ro.product.model; " +
            "ls -l /system/bin/sh; readlink -f /system/bin/sh; " +
            "sh -c 'exec -a plinux_probe_marker sleep 0.2'; echo EXECA_RC=" + "${'$'}?")
        sb.append("--- run-header ---\n").append(fp.outFull).append("--- header-end ---\n")
    }

    /** Bounded shell via uiautomator; rc parsed from a trailing RC= line
     *  (grep -c semantics preserved), -1 when the command itself failed. */
    private fun shell(device: UiDevice, cmd: String): ShellOut {
        val raw = try {
            device.executeShellCommand(cmd) ?: ""
        } catch (t: Throwable) {
            return ShellOut("", -1)
        }
        val lines = raw.trim().lines()
        val last = lines.lastOrNull()?.trim() ?: ""
        val rc = if (last.startsWith("RC=")) last.removePrefix("RC=").toIntOrNull() ?: -2 else -2
        val body = if (last.startsWith("RC=")) lines.dropLast(1).joinToString("\n") else raw.trim()
        return ShellOut(body.trim(), rc)
    }

    private val ShellOut.outFull: String get() = if (out.isEmpty()) "(empty, rc=" + rc + ")" else out

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
