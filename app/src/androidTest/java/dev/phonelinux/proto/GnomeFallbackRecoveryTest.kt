package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runtime qualification of the crash-fallback RECOVERY path (kept from
 * the parked stop-intent suite; the intent mechanism itself was removed
 * after run 41306297954 showed a proot tracee cannot do useful work
 * after its tracer dies - ENOSYS on intercepted syscalls. That finding
 * covers the tested guarded case only; other tracer-death shapes were
 * not established either way).
 *
 * One real session: an unexpected death (session + owned shells killed
 * by verified identity, the launcher script left alive, NO owned stop)
 * must EXEC the bare gnome-shell fallback and REGISTER it on the bus;
 * owned cleanup then removes it (live root: identity-verified
 * teardown). Fail-closed survivor semantics are preserved: all owned
 * shells are dead before the fallback, nothing is forced past the
 * survivor check.
 */
@RunWith(AndroidJUnit4::class)
class GnomeFallbackRecoveryTest {
    private val TAG = "GnomeFallbackRecoveryTest"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val d = Distros.UBUNTU
    private val rootfs get() = DistroInstaller(ctx).rootfsDir(d)
    private val logFile get() = File(ctx.filesDir, "guest-gnome.log")

    @After fun cleanup() {
        try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { }
        try { XServer.resetOwnershipStateForTest() } catch (t: Throwable) { }
    }

    private fun statFields(pid: Int): List<String>? {
        val st = try { File("/proc/" + pid + "/stat").readText() } catch (t: Throwable) { return null }
        val close = st.lastIndexOf(')')
        if (close < 0) return null
        val rest = st.substring(close + 1).trim().split(Regex("\\s+"))
        return if (rest.size >= 20) rest else null
    }

    private fun startTicksOf(pid: Int): String? = statFields(pid)?.get(19)

    /** Identity-checked kill: signal only if pid still carries the same
     *  start ticks and runs as our uid. */
    private fun killVerified(pid: Int, startTicks: String, sig: Int): Boolean {
        if (startTicksOf(pid) != startTicks) return false
        val uid = try { android.system.Os.stat("/proc/" + pid).st_uid } catch (t: Throwable) { return false }
        if (uid != android.os.Process.myUid()) return false
        return try { android.os.Process.sendSignal(pid, sig); true } catch (t: Throwable) { false }
    }

    /** Post-base log window. UNIT DISCIPLINE: [base] is a CHAR offset into
     *  the file's decoded content (captured from a readText() snapshot at
     *  launch), never File.length() BYTES - multi-byte UTF-8 in earlier
     *  output (mutter warnings use curly quotes) makes char count < byte
     *  count, and a byte base silently mis-slices the window (run
     *  41303335159: the byte base exceeded the early char length, the
     *  window fell back to the whole file, and a STALE alive line from
     *  the previous session matched). Same discipline as
     *  SessionReset.trackedWarmup. */
    private fun window(base: Long): String? {
        if (!logFile.isFile) return ""
        val content = logFile.readText()
        // FAIL CLOSED on shrink: a shorter content than base means the
        // log rotated/truncated under us; falling back to offset 0 would
        // re-admit the stale lines the base exists to exclude.
        if (content.length < base) return null
        return content.substring(base.toInt())
    }

    /** A marker line belonging to THIS launch generation: it must sit
     *  AFTER the exact launch-id line of this launch inside the post-base
     *  window (a bare window match can still pick a line that precedes
     *  this launch's id within the same file tail). */
    private fun lineOfThisLaunch(w: String, launchId: String, needle: String): String? {
        // EXACT id-token match: the character after the id must end the
        // line, so id "A" never binds to id "AB"'s generation.
        val idLine = "gnome-launch: launch id=" + launchId
        var idIdx = -1
        var from = 0
        while (true) {
            val i = w.indexOf(idLine, from)
            if (i < 0) break
            val end = i + idLine.length
            if (end >= w.length || w[end] == '\n' || w[end] == '\r') idIdx = i
            from = i + 1
        }
        if (idIdx < 0) return null
        val needleIdx = w.lastIndexOf(needle)
        if (needleIdx < idIdx) return null
        return w.substring(needleIdx).lineSequence().first().trim()
    }

    /** Wait for [needle] bound to THIS launch id; fails closed on rotation. */
    private fun awaitLine(base: Long, launchId: String, needle: String, timeoutMs: Long, pollMs: Long = 2_000): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val w = window(base)
                ?: throw AssertionError("guest-gnome.log rotated/shrank under launch " + launchId +
                    " - marker binding is void (fail-closed)")
            val hit = lineOfThisLaunch(w, launchId, needle)
            if (hit != null) return hit
            Thread.sleep(pollMs)
        }
        return null
    }

    /** Wait for [needle] appearing AFTER BOTH this launch's exact id
     *  line AND the crash-injection boundary [afterCharOffset] (a CHAR
     *  offset into the same log content [base] slices, captured at
     *  crash-injection time). A marker that precedes the kill never
     *  counts as recovery FROM that kill, even within the same launch
     *  generation. Fails closed on rotation. */
    private fun awaitLineAfter(base: Long, launchId: String, afterCharOffset: Long,
                               needle: String, timeoutMs: Long, pollMs: Long = 2_000): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val w = window(base)
                ?: throw AssertionError("guest-gnome.log rotated/shrank under launch " + launchId +
                    " - marker binding is void (fail-closed)")
            val idLine = "gnome-launch: launch id=" + launchId
            var idIdx = -1
            var scan = 0
            while (true) {
                val i = w.indexOf(idLine, scan)
                if (i < 0) break
                val eol = i + idLine.length
                if (eol >= w.length || w[eol] == '\n' || w[eol] == '\r') idIdx = i
                scan = i + 1
            }
            if (idIdx >= 0) {
                val boundary = (afterCharOffset - base).toInt()
                val from = maxOf(idIdx, boundary).coerceAtMost(w.length)
                val i = w.indexOf(needle, from)
                if (i >= 0) return w.substring(i).lineSequence().first().trim()
            }
            Thread.sleep(pollMs)
        }
        return null
    }

    private fun ourGnomeShellPids(): List<Int> {
        val out = ArrayList<Int>()
        for (f in File("/proc").listFiles { x -> x.name.matches(Regex("[0-9]+")) } ?: return out) {
            val comm = try { File(f, "comm").readText().trim() } catch (t: Throwable) { continue }
            if (comm != "gnome-shell") continue
            val uid = try { android.system.Os.stat(f.absolutePath).st_uid } catch (t: Throwable) { continue }
            if (uid == android.os.Process.myUid()) out.add(f.name.toInt())
        }
        return out
    }

    /** Owned GNOME components from ANY prior session (comm is truncated
     *  to 15 chars, so match exact comm for gnome-shell and cmdline for
     *  the session launcher/script names). Baseline hygiene: real
     *  sessions in one run must not contaminate each other. */
    private fun ourGnomeComponentPids(): List<Int> {
        val out = ArrayList<Int>()
        for (f in File("/proc").listFiles { x -> x.name.matches(Regex("[0-9]+")) } ?: return out) {
            val uid = try { android.system.Os.stat(f.absolutePath).st_uid } catch (t: Throwable) { continue }
            if (uid != android.os.Process.myUid()) continue
            val comm = try { File(f, "comm").readText().trim() } catch (t: Throwable) { "" }
            val cmd = try { File(f, "cmdline").readText() } catch (t: Throwable) { "" }
            if (comm == "gnome-shell" || cmd.contains("gnome-session-binary") || cmd.contains("gnome-session.sh")) {
                out.add(f.name.toInt())
            }
        }
        return out
    }

    /** Alive marker belonging to THIS launch: the alive line must sit
     *  AFTER the EXACT launch id line this app generated (from
     *  XServer.currentLaunchIdForTest, never the latest id line found in
     *  the window) inside one consistent snapshot - a plain substring
     *  search can match a previous generation's alive line (stale world),
     *  and log rotation alone does not protect ordering. */
    private fun aliveOfThisLaunch(w: String, launchId: String): String? =
        lineOfThisLaunch(w, launchId, "gnome-launch: gnome-session alive pid=")

    private var lastLaunchBase: Long? = null
    private var lastLaunchId: String? = null

    private fun freshLaunch(): Triple<Process, Long, String> {
        SessionReset.resetForFreshLaunch(ctx, d) { Log.i(TAG, it) }
        val leftovers = ourGnomeComponentPids()
        assertTrue("baseline contaminated: owned GNOME components from a prior session survive reset: " + leftovers,
            leftovers.isEmpty())
        XServer.start(ctx)
        // CHAR base from one content snapshot (never File.length() bytes).
        val base = (if (logFile.isFile) logFile.readText() else "").length.toLong()
        val proc = XServer.launchOrReuseGnomeSession(ctx, d, rootfs)
        val launchId = XServer.currentLaunchIdForTest()
        assertTrue("current launch identity unavailable from app - cannot bind " +
            "readiness to THIS launch (failing clearly, not scanning the window)",
            launchId != null)
        lastLaunchBase = base; lastLaunchId = launchId
        var aliveLine: String? = null
        val end = System.currentTimeMillis() + 6 * 60_000
        while (aliveLine == null && System.currentTimeMillis() < end) {
            val w = window(base)
                ?: throw AssertionError("guest-gnome.log rotated/shrank under launch " + launchId +
                    " - marker binding is void (fail-closed)")
            aliveLine = aliveOfThisLaunch(w, launchId!!)
            if (aliveLine == null) Thread.sleep(2_000)
        }
        assertTrue("session did not reach THIS launch's alive marker: " +
            (window(base)?.takeLast(400) ?: "<rotated>"), aliveLine != null)
        return Triple(proc, base, aliveLine!!)
    }

    @Test fun unexpectedDeathRecoversToRegisteredFallback() {
        // Stale-evidence hygiene + failure preservation: the evidence file
        // from any prior run is cleared at start; on failure the launch
        // window tail is persisted BEFORE the @After cleanup runs, so a
        // failed run keeps its evidence (the instr log tail is not enough
        // when assertion messages truncate at 400 chars).
        val evidence = File(ctx.filesDir, "gnome-recovery-evidence.txt")
        evidence.delete()
        try {
            runRecoveryBody()
        } catch (t: Throwable) {
            try {
                val base = lastLaunchBase
                evidence.writeText("== recovery test FAILED: " + t.message + "\n== launch window tail ==\n" +
                    (if (base != null) window(base)?.takeLast(4000) ?: "<log rotated>" else "<no launch reached>"))
            } catch (we: Throwable) { }
            throw t
        }
    }

    private fun runRecoveryBody() {
        val (proc, base, aliveLine) = freshLaunch()
        val gsPid = aliveLine.substringAfter("gnome-session alive pid=").takeWhile { it.isDigit() }.toInt()
        val gsTicksOpt = startTicksOf(gsPid)
        assertTrue("session start ticks must be readable", gsTicksOpt != null)
        val gsTicks = gsTicksOpt!!

        // Unexpected death: session + owned shells gone, script alive,
        // NO owned stop. The fallback must EXEC and REGISTER. The
        // crash-injection boundary (CHAR offset at kill time) binds the
        // recovery markers to AFTER the crash - a fallback line from
        // before the kill never counts as recovery from it.
        val crashBoundary = (if (logFile.isFile) logFile.readText() else "").length.toLong()
        assertTrue(killVerified(gsPid, gsTicks, android.system.OsConstants.SIGKILL))
        for (sp in ourGnomeShellPids()) {
            val t = startTicksOf(sp); if (t != null) killVerified(sp, t, android.system.OsConstants.SIGKILL)
        }
        val fellBack = awaitLineAfter(base, lastLaunchId!!, crashBoundary,
            "falling back to bare gnome-shell", 120_000)
        assertTrue("crash recovery must reach the fallback decision without an owned stop: " +
            (window(base)?.takeLast(400) ?: "<rotated>"), fellBack != null)
        val registered = awaitLineAfter(base, lastLaunchId!!, crashBoundary,
            "fallback shell registered", 150_000)
        assertTrue("ACTUAL recovery: the fallback shell must exec and register on the bus: " +
            (window(base)?.takeLast(400) ?: "<rotated>"), registered != null)
        assertTrue("the registered fallback shell must be an owned process",
            ourGnomeShellPids().isNotEmpty())

        // Owned cleanup (live root: identity-verified teardown).
        XServer.stopDesktopMode(ctx)
        Thread.sleep(15_000)
        assertTrue("owned cleanup must remove the recovered shell",
            ourGnomeShellPids().isEmpty())
    }
}
