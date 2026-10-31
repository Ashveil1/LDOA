package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Identity-owned replacement for the old pre-launch "/proc cmdline
 * name-kill" session resets. Name matching is DETECTION ONLY here; the
 * only teardown paths are the product's identity-captured ones
 * (XServer.stopDesktopMode / teardownRetainedFailedSession: every signal
 * is re-verified against the launch-captured (pid, starttime, uid), a
 * reused PID is never signaled, and the tree walk is skipped fail-closed
 * when the root identity no longer matches).
 *
 * Branch contract for a test's fresh-launch reset:
 *  - a tracked session still STARTING (retainedSessionStarting, readiness
 *    uncertain) is NEVER ended by a reset - the launch path's own
 *    reuse/generation rules handle it;
 *  - a retained FAILED session takes the existing retry-implies-stop
 *    verified teardown;
 *  - a tracked ACTIVE session plus explicit fresh-launch intent is an
 *    explicit Stop of this owned generation (same authority path as the
 *    user's Stop button);
 *  - an UNTRACKED world with session-component cmdlines is never killed
 *    by name: the reset FAILS CLOSED with the evidence (after a bounded
 *    grace for the bus-death cascade, see below).
 */
object SessionReset {
    private const val TAG = "SessionReset"

    /** Session-DEFINING markers: only these gate fail-closed. A live one
     *  means a session world is actually up.
     *
     *  Daemon components (tracker-miner, dbus-daemon, dconf-service,
     *  gvfsd - the exact names in run 41244200992's 21:47:21 trim records)
     *  are REPORT-ONLY: they can be legitimately alive as a PRESERVED
     *  world the product deliberately keeps (a degraded desktop's bus and
     *  its bus-activated services outlive gnome-session by design - the
     *  fallback-suppressed script exits 1 while preserving them), so a
     *  daemon match must never strand that preserved world behind a
     *  fail-closed. Detection only, never a kill list, either way. */
    private val SESSION_DEFINING_MARKERS = listOf("gnome-session-binary", "gnome-session.sh", "gnome-shell")
    private val REPORT_ONLY_MARKERS = listOf("tracker-miner", "dbus-daemon", "dconf-service", "gvfsd")

    /** Detection-only scan of SESSION-DEFINING processes. */
    fun scanUntrackedGnomeWorlds(): Map<Int, String> = scanByMarkers(SESSION_DEFINING_MARKERS)

    /** Detection-only scan of report-only daemon components. */
    fun scanReportOnlyDaemons(): Map<Int, String> = scanByMarkers(REPORT_ONLY_MARKERS)

    private fun scanByMarkers(markers: List<String>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: return out
        for (f in dir) {
            val cmd = try {
                File(f, "cmdline").readText().replace('\u0000', ' ').trim()
            } catch (t: Throwable) { continue }
            if (cmd.isEmpty()) continue
            if (markers.any { cmd.contains(it) }) out[f.name.toInt()] = cmd.take(120)
        }
        return out
    }

    /** The fail-closed seam: a non-empty untracked set is an assertion,
     *  never a kill list. */
    internal fun failClosedOnUntracked(untracked: Map<Int, String>) {
        if (untracked.isNotEmpty()) {
            val s = "reset: FAIL CLOSED - untracked session-world processes present, NOT killed by name: " +
                untracked.entries.joinToString { "pid=" + it.key + " cmd=" + it.value }
            Log.w(TAG, s)
            throw AssertionError(s)
        }
    }

    /** Test-owned warm precondition, IN-INVOCATION: launch a tracked
     *  session, wait (bounded) for THIS launch's verdict line, then tear
     *  down by OWNED identity via the product Stop path. am instrument
     *  force-stops the app on every invocation (AOSP 14 AMS
     *  startInstrumentation, "start instr"), so a cross-invocation warm
     *  handoff can only ever be an UNTRACKED orphan - never teardownable
     *  by identity. Verdict binding: the guest log is APPEND-ONLY across
     *  launches (rotation only past 4MB), so a stale previous verdict
     *  would pass a naive scan - verdicts are accepted only from bytes
     *  appended after this launch's recorded offset, and only once THIS
     *  launch's id line appears there. Identity: the root identity must
     *  be launch-captured (XServer captures at spawn) before the
     *  teardown, else the Stop degrades to destroy-only and the tree
     *  leaks - reported UNQUALIFIED, never hidden. */
    fun trackedWarmup(ctx: Context, distro: Distro, rootfs: File, log: (String) -> Unit): String {
        XServer.start(ctx)
        val logFile = File(ctx.filesDir, "guest-gnome.log")
        val logBase = if (logFile.isFile) logFile.length() else 0L
        XServer.launchOrReuseGnomeSession(ctx, distro, rootfs)
        val identCaptured = XServer.hasSessionRootIdentity()
        val deadline = System.currentTimeMillis() + 6 * 60_000
        var marker = "(none within bound)"
        while (System.currentTimeMillis() < deadline) {
            if (logFile.isFile) {
                // A rotation (>4MB) restarts the file: then ALL of it is
                // this launch. Otherwise only bytes past the base count.
                val base = if (logFile.length() >= logBase) logBase else 0L
                val tail = logFile.readText().substring(base.toInt().coerceAtMost(logFile.length().toInt()))
                if (tail.contains("gnome-launch: launch id=")) {
                    val m = tail.lineSequence().lastOrNull {
                        it.contains("gnome-launch: gnome-session alive") ||
                            it.contains("gnome-launch: session launcher died") ||
                            it.contains("falling back to bare gnome-shell") }
                    if (m != null) { marker = m.trim(); break }
                }
            }
            Thread.sleep(5_000)
        }
        // The session bus daemonizes (reparents out of the proot tree), so
        // the identity-verified tree walk cannot reach it: capture THIS
        // launch's bus via the launcher's creation-time identity record
        // BEFORE the Stop, or the Stop leaves a live stranded bus behind.
        // The expected nonce comes from THIS launch's OWN log block (bytes
        // after logBase, after ITS launch-id line) - never from whatever
        // record or tail is latest. A missing/stale/reused record or a
        // nonce mismatch is NOT captured (bus preserved, never killed).
        val expectedNonce = run {
            val tail = if (logFile.isFile) {
                val base = if (logFile.length() >= logBase) logBase else 0L
                logFile.readText().substring(base.toInt().coerceAtMost(logFile.length().toInt()))
            } else ""
            val launchPos = tail.lastIndexOf("gnome-launch: launch id=")
            if (launchPos < 0) "" else tail.substring(launchPos).lineSequence()
                .lastOrNull { it.contains("gnome-launch: session bus") && it.contains("nonce=") }
                ?.substringAfter("nonce=", "")?.trim()?.takeWhile { !it.isWhitespace() } ?: ""
        }
        val busCaptured = XServer.captureSessionBusIdentityForTest(ctx, expectedNonce)
        XServer.stopDesktopMode(ctx)
        val qualified = identCaptured && marker != "(none within bound)" && busCaptured
        val s = "warmup: " + (if (qualified) "QUALIFIED" else "UNQUALIFIED") +
            " tracked launch -> marker='" + marker + "' rootIdentityCaptured=" + identCaptured +
            " busIdentityCaptured=" + busCaptured + " -> owned teardown done"
        log(s)
        return s
    }

    fun resetForFreshLaunch(ctx: Context, distro: Distro, log: (String) -> Unit): String {
        if (XServer.retainedSessionStarting) {
            val s = "reset: KEPT tracked session still STARTING (readiness uncertain) - a fresh-launch request never ends it"
            log(s); return s
        }
        if (XServer.teardownRetainedFailedSession(ctx)) {
            val s = "reset: tore down retained FAILED session by captured identity (retry implies stop)"
            log(s); return s
        }
        if (XServer.gnomeSessionActive(distro)) {
            XServer.stopDesktopMode(ctx)
            val s = "reset: explicit stop of tracked session by captured identity (fresh-launch intent, owned generation)"
            log(s); return s
        }
        // Runtime ownership basis: this scan executes on live /proc ONLY
        // after every tracked-ownership branch above returned false - no
        // tracked root, no retained handle, nothing STARTING. Any marker
        // match here is therefore untracked BY CONSTRUCTION (not a default
        // -false/no-op): the tracked session's own components can never
        // reach this scan.
        val daemons = scanReportOnlyDaemons()
        if (daemons.isNotEmpty()) {
            // Report-only: daemon components can be a PRESERVED world the
            // product deliberately keeps (degraded desktop). Logged for
            // the phantom-pressure picture, never gating, never killed.
            log("reset: report-only daemon components present (NOT gating): " +
                daemons.entries.joinToString { "pid=" + it.key + " cmd=" + it.value })
        }
        var untracked = scanUntrackedGnomeWorlds()
        if (untracked.isNotEmpty()) {
            // Short bounded grace: a just-finished owned teardown's
            // TERM/KILL verification already waits, so a persisting
            // session-defining match after grace is a REAL anomaly.
            log("reset: untracked session-defining matches - bounded grace re-scan: " + untracked.keys)
            val end = System.currentTimeMillis() + 20_000
            while (untracked.isNotEmpty() && System.currentTimeMillis() < end) {
                Thread.sleep(2_000)
                untracked = scanUntrackedGnomeWorlds()
            }
        }
        failClosedOnUntracked(untracked)
        val s = "reset: no tracked or untracked session world"
        log(s); return s
    }
}
