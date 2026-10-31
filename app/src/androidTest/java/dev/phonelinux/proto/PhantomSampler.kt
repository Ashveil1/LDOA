package dev.phonelinux.proto

import android.util.Log
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Phantom-process pressure sampler (instrumentation only - no product
 * behavior). Qualifies the instrument on every sample: a known marker
 * process is spawned and MUST be observed before any count is trusted,
 * so a silent-empty scan can never be reported as "count 0".
 *
 * What each number IS (labels, per the review):
 *  - runtime-config: the live DeviceConfig value from
 *    `dumpsys activity settings | grep max_phantom_processes` - the actual
 *    bound on THIS device/build, never the assumed AOSP default 32.
 *  - phantom-records: the system's own global list section from
 *    `dumpsys activity | grep -i phantom` - authoritative and global
 *    (all uids), when the build prints it; empty means "not printed",
 *    not "no phantoms".
 *  - uid-processes: every own-uid /proc entry. Includes reparented
 *    orphans (what a PPid-walk misses); this is the closest host-side
 *    approximation of what Android attributes to the app.
 *  - app-descendants: PPid-walk from the app pid. A SUBSET of the above:
 *    reparented orphans escape it. Reported for tree-shape evidence only.
 *
 * The marker calibrates ONLY the own-uid /proc scan (it is a same-uid
 * process): "marker-verified" means the uid scan is not silently empty.
 * It says nothing about the GLOBAL phantom list - that view is the
 * dumpsys phantom-records section, whose absence is reported as unknown,
 * never as zero. dumpsys and /proc access failures are likewise logged
 * as unknown, not as values.
 */
object PhantomSampler {
    private const val TAG = "PhantomSampler"

    fun sample(device: UiDevice, label: String): String {
        val nonce = java.lang.Long.toString(System.currentTimeMillis(), 36)
        val markerName = "plinux-phantom-marker-" + nonce
        val marker = try {
            ProcessBuilder("/system/bin/sh", "-c", "exec -a " + markerName + " sleep 25").start()
        } catch (t: Throwable) { null }
        Thread.sleep(400)

        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: emptyArray()
        var uidCount = 0
        var markerSeen = false
        val ppidOf = HashMap<Int, Int>()
        for (f in dir) {
            val pid = try { f.name.toInt() } catch (t: Throwable) { continue }
            val st = try { android.system.Os.stat(f.absolutePath) } catch (t: Throwable) { continue }
            if (st.st_uid == myUid) {
                uidCount++
                val cmd = try { File(f, "cmdline").readText() } catch (t: Throwable) { "" }
                if (cmd.contains(markerName)) markerSeen = true
            }
            val stat = try { File(f, "stat").readText() } catch (t: Throwable) { continue }
            val close = stat.lastIndexOf(')')
            if (close < 0) continue
            val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 2) continue
            ppidOf[pid] = try { rest[1].toInt() } catch (t: Throwable) { continue }
        }
        var descendants = 0
        val seen = HashSet<Int>()
        val frontier = java.util.ArrayDeque<Int>()
        frontier.add(myPid)
        while (!frontier.isEmpty()) {
            val p = frontier.removeFirst()
            if (!seen.add(p)) continue
            descendants++
            for ((pid, ppid) in ppidOf) if (ppid == p && pid !in seen) frontier.add(pid)
        }

        val runtimeMax = try {
            device.executeShellCommand("dumpsys activity settings | grep max_phantom_processes")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "unknown (no max_phantom_processes line in dumpsys settings)"
        } catch (t: Throwable) { "unknown (dumpsys settings failed: " + t.message + ")" }
        val records = try {
            device.executeShellCommand("dumpsys activity | grep -i phantom | head -30")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "unknown (no phantom section printed by this build)"
        } catch (t: Throwable) { "unknown (dumpsys phantom failed: " + t.message + ")" }
        // Marker cleanup, identity-owned and bounded: re-verify (pid,
        // starttime) before EVERY signal; a reused pid is never signaled.
        if (marker != null) {
            try {
                val mpid = pidOf(marker)
                val mstart = startTimeOf(mpid)
                marker.destroy()
                val end = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < end && marker.isAlive) Thread.sleep(100)
                val uidOk = try {
                    android.system.Os.stat("/proc/" + mpid).st_uid == android.os.Process.myUid()
                } catch (t: Throwable) { false }
                if (marker.isAlive && mstart != null && startTimeOf(mpid) == mstart && uidOk) {
                    try { android.os.Process.sendSignal(mpid, android.system.OsConstants.SIGKILL) } catch (t: Throwable) { }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "marker cleanup failed (unknown leftover state): " + t.message)
            }
        }

        val instrument = when {
            marker == null -> "INSTRUMENT-FAILURE(marker spawn failed - counts untrusted)"
            !markerSeen -> "INSTRUMENT-FAILURE(marker not observed - counts untrusted)"
            else -> "marker-verified"
        }
        val report = "phantom-sample[" + label + "]: " + instrument +
            " runtime-config=[" + runtimeMax + "]" +
            " uid-processes=" + uidCount +
            " app-descendants=" + (descendants - 1).coerceAtLeast(0) + "(subset-misses-reparented)" +
            " phantom-records=[" + records + "]"
        Log.i(TAG, report)
        return report
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
