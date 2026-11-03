package dev.phonelinux.proto

import java.io.File

/** Failure-path evidence for the xdiag diagnostic proxy spawn. On EVERY
 *  proxy-start abort the androidTest code persists the spawn's own output
 *  tail, identity, exit status, sanitized argv/env and path probes AFTER
 *  the once-only cleanup attempt and BEFORE the failure propagates.
 *  Every artifact is generated and written independently (a generation
 *  or write failure is recorded per-artifact and never suppresses the
 *  others), and the per-file outcomes are themselves recorded last.
 *  Pure file/string work, unit-testable off-device; nothing here touches
 *  Android APIs. */
internal object XdiagAbortEvidence {

    /** Explicit exit status text; never throws, never guesses. */
    fun exitStatus(exited: Boolean?, code: Int?, error: String?, notSpawned: Boolean): String =
        when {
            notSpawned -> "NOT_SPAWNED (abort before pb.start())"
            error != null -> "UNKNOWN (" + error + ")"
            exited == true -> "EXITED code=" + code
            exited == false -> "STILL_ALIVE at evidence write (reap incomplete/failed)"
            else -> "UNKNOWN (no process handle)"
        }

    /** Distinct tail labels: a drain that never ran is NOT the same
     *  evidence as a drain that ran and captured nothing. */
    fun tailText(drainStarted: Boolean, snapshot: String): String = when {
        !drainStarted -> "UNAVAILABLE (drain never started - abort before/at drain launch)\n"
        snapshot.isEmpty() -> "(drain ran; no output captured)\n"
        else -> snapshot
    }

    /** Keeps only SELinux audit/AVC lines (with their timestamps and
     *  pids) so the abort record carries attribution evidence without a
     *  full logcat. Keeps the MOST RECENT [maxLines] matches. */
    fun filterAvcLines(logcat: String, maxLines: Int = 200): String {
        val matches = logcat.lines().filter { it.contains("avc:") || it.contains("audit(") }
        val kept = if (matches.size > maxLines) matches.drop(matches.size - maxLines) else matches
        return kept.joinToString("\n")
    }

    /** Raw File probes only. exists=false can mean permission-denied -
     *  these lines are NOT resolution proof and NOT confirmed absence. */
    fun pathFacts(paths: Map<String, String>): String =
        paths.entries.joinToString("\n") { (label, path) ->
            try {
                val f = File(path)
                label + "=" + path + " rawExists=" + f.exists() +
                    " rawCanRead=" + f.canRead() + " rawCanExecute=" + f.canExecute() +
                    " (raw File probes only; false can mean permission-denied, not confirmed absent)"
            } catch (t: Throwable) {
                label + "=" + path + " PROBE-ERROR(" + t.javaClass.simpleName + ")"
            }
        }

    /** Sanitized env record: explicit values (fixed, non-secret build
     *  constants) plus inherited KEY NAMES only - values never recorded. */
    fun envFacts(explicitEnv: Map<String, String>?, inheritedKeys: List<String>?): String {
        val b = StringBuilder()
        b.append("explicit:\n")
        explicitEnv?.entries?.forEach { b.append("  ").append(it.key).append("=").append(it.value).append('\n') }
        b.append("inherited keys only (values never recorded):\n")
        inheritedKeys?.forEach { b.append("  ").append(it).append('\n') }
        return b.toString()
    }

    /** Runs [fn] on a daemon thread with a hard time bound ON THE CALLER'S
     *  WAIT; returns null on timeout or any failure. Never throws; the
     *  bound call's latency can never suppress other evidence. This limits
     *  the caller's wait ONLY - a blocking worker that ignores interrupt
     *  is NOT guaranteed to terminate (it is abandoned on a daemon
     *  thread, which is why the caller-side bound is what protects the
     *  evidence path). */
    fun <T> bounded(ms: Long, fn: () -> T): T? {
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "xdiag-abort-evidence").apply { isDaemon = true } }
        try {
            val fut = ex.submit(java.util.concurrent.Callable { fn() })
            return try { fut.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (t: Throwable) { fut.cancel(true); null }
        } catch (t: Throwable) {
            return null
        } finally { ex.shutdownNow() }
    }

    /** Generates and writes every artifact independently, IN ORDER; one
     *  artifact's generation or write failure never suppresses the rest
     *  and NOTHING here throws. Returns per-file outcome lines (also
     *  persisted as proxy-abort-summary.txt, written LAST). */
    fun write(dir: File, artifacts: List<Pair<String, () -> String>>): List<String> {
        val outcomes = mutableListOf<String>()
        val ready = try { dir.isDirectory || dir.mkdirs() } catch (t: Throwable) { false }
        if (!ready) {
            outcomes.add("FAILED <dir>: unavailable " + dir.absolutePath)
            return outcomes
        }
        for ((name, gen) in artifacts) {
            val body = try { gen() } catch (t: Throwable) {
                outcomes.add("FAILED " + name + " (generate): " + t.javaClass.simpleName + ": " + t.message)
                null
            }
            if (body != null) {
                try {
                    File(dir, name).writeText(body)
                    outcomes.add("OK " + name + " (" + body.length + " chars)")
                } catch (t: Throwable) {
                    outcomes.add("FAILED " + name + " (write): " + t.javaClass.simpleName + ": " + t.message)
                }
            }
        }
        try {
            File(dir, "proxy-abort-summary.txt").writeText(outcomes.joinToString("\n") + "\n")
        } catch (t: Throwable) { /* nothing else depends on this write */ }
        return outcomes
    }
}
