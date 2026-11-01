package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runtime qualification of the owned-stop intent (P1/P2 fallback
 * suppression). Two real sessions only.
 *
 * T1 simulates the audited P1 path and PROVES it is not vacuous: the
 * script's frozen parent identity must equal the app's spawned tracer
 * pid (the script's parent really is the tracer, not an intermediate
 * wrapper), the script must survive the tracer's SIGKILL, and its ppid
 * must change (reparenting actually simulated). Then an owned Stop
 * lands and the session is killed. BOTH intent readers must honor the
 * SAME retained file: whichever checkpoint fires first, the other still
 * sees the intent (no reader ever deletes it - a delete-after-first-read
 * would make the second reader see absence and respawn). The parent's
 * honor waits for the observer, so the observer's honor always lands
 * while the parent is still alive. Reader ORDER is timing-dependent
 * (the parent's wait unblocks ~immediately, the observer polls every
 * 10s); both orders are safe, so the test asserts both honor lines and
 * the retained file, not the order.
 *
 * T2a proves ACTUAL unexpected-death recovery end to end: with NO
 * owned stop and a planted STALE intent (old id/key/foreign root -
 * ignored, never deleted by a non-owner), an unexpected session death
 * (session + owned shells killed by verified identity, script left
 * alive) must not only decide but EXEC the fallback and REGISTER the
 * new shell on the bus. Owned cleanup then removes it (live root:
 * identity-verified teardown). Fail-closed survivor semantics are
 * preserved: all owned shells are dead before the fallback, nothing is
 * forced past the survivor check.
 *
 * T2b is the P2 race probe: it kills the tracer (identity-verified)
 * and lands Stop right after the fallback decision line: with the root
 * dead the teardown skips the tree walk fail-closed (nothing else can
 * kill the script first), the intent is still written from the
 * in-memory launch identity, and the surviving script is
 * deterministically inside the survivor-window/2s pre-exec span (the
 * survivor window is ~0s once all owned shells are dead). One of the
 * two post-decision gates must honor the intent and NO second
 * gnome-shell may exec. Coverage label: the decision checkpoint is
 * exercised by T1; the post-decision gates by T2b; actual recovery
 * exec/registration by T2a; a live-root Stop needs no gate (the
 * identity-verified teardown kills the whole tree, script included).
 */
@RunWith(AndroidJUnit4::class)
class GnomeStopIntentTest {
    private val TAG = "GnomeStopIntentTest"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val d = Distros.UBUNTU
    private val rootfs get() = DistroInstaller(ctx).rootfsDir(d)
    private val logFile get() = File(ctx.filesDir, "guest-gnome.log")

    @After fun cleanup() {
        try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { }
        try { XServer.resetOwnershipStateForTest() } catch (t: Throwable) { }
    }

    private fun pidOf(p: Process): Int =
        try { val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p) }
        catch (t: Throwable) { -1 }

    /** /proc stat fields after the comm ')' : [0]=state [1]=ppid [19]=starttime. */
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
    private fun window(base: Long): String {
        if (!logFile.isFile) return ""
        val content = logFile.readText()
        val b = if (content.length >= base) base.toInt() else 0
        return content.substring(b.coerceAtMost(content.length))
    }

    /** Wait for a line containing [needle] in the post-base window. */
    private fun awaitLine(base: Long, needle: String, timeoutMs: Long, pollMs: Long = 2_000): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val hit = window(base).lineSequence().lastOrNull { it.contains(needle) }
            if (hit != null) return hit.trim()
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
     *  the session launcher/script names). Baseline hygiene: three real
     *  sessions in one run must not contaminate each other (leftovers
     *  also inflate the next app's phantom roster). */
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
    private fun aliveOfThisLaunch(w: String, launchId: String): String? {
        val idIdx = w.lastIndexOf("gnome-launch: launch id=" + launchId)
        if (idIdx < 0) return null
        val aliveIdx = w.lastIndexOf("gnome-launch: gnome-session alive pid=")
        if (aliveIdx < idIdx) return null
        return w.substring(aliveIdx).lineSequence().first().trim()
    }

    /** awaitLine with a useful assertion failure instead of a bare !!. */
    private fun requireLine(base: Long, needle: String, timeoutMs: Long, pollMs: Long = 2_000): String {
        val line = awaitLine(base, needle, timeoutMs, pollMs)
        assertTrue("missing '" + needle + "' within " + timeoutMs + "ms: " + window(base).takeLast(400),
            line != null)
        return line!!
    }

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
        var aliveLine: String? = null
        val end = System.currentTimeMillis() + 6 * 60_000
        while (aliveLine == null && System.currentTimeMillis() < end) {
            aliveLine = aliveOfThisLaunch(window(base), launchId!!)
            if (aliveLine == null) Thread.sleep(2_000)
        }
        assertTrue("session did not reach THIS launch's alive marker: " + window(base).takeLast(400),
            aliveLine != null)
        return Triple(proc, base, aliveLine!!)
    }

    @Test fun ownedStopAfterParentDeathSuppressesFallback() {
        val (proc, base, aliveLine) = freshLaunch()
        val gsPid = aliveLine.substringAfter("gnome-session alive pid=").takeWhile { it.isDigit() }.toInt()
        val gsTicks = startTicksOf(gsPid)
        assertTrue("need session start ticks", gsTicks != null)

        // Parent-chain validation (non-vacuous fixture): the script's
        // FROZEN parent identity must name the app's spawned tracer -
        // if proot inserted an intermediate wrapper this fails loudly
        // instead of "passing" a fixture that never had the P1 shape.
        val frozen = requireLine(base, "frozen parent identity pid=", 10_000)
        val frozenPid = frozen.substringAfter("pid=").takeWhile { it.isDigit() }.toInt()
        val scriptPid = frozen.substringAfter("scriptpid=").takeWhile { it.isDigit() }.toInt()
        val tracerPid = pidOf(proc)
        assertTrue("frozen parent must be the spawned tracer pid (parent chain validated)",
            tracerPid > 0 && frozenPid == tracerPid)
        assertTrue("script pid must be distinct from the tracer", scriptPid > 0 && scriptPid != tracerPid)
        val scriptTicks = startTicksOf(scriptPid)
        assertTrue("script must be alive before the kill", scriptTicks != null)

        // Parent death FIRST (P1): SIGKILL the proot tracer; no userspace
        // runs, the script is orphaned, the session lives on.
        val tracerTicks = startTicksOf(tracerPid)
        assertTrue("tracer kill must be identity-verified",
            tracerTicks != null && killVerified(tracerPid, tracerTicks!!, android.system.OsConstants.SIGKILL))

        // Non-vacuous survival + reparenting proof.
        Thread.sleep(2_000)
        val postFields = statFields(scriptPid)
        assertTrue("script must SURVIVE the tracer kill (else no-fallback would be vacuous)",
            postFields != null && postFields[19] == scriptTicks)
        assertTrue("script must be REPARENTED after the tracer kill (ppid changed off the tracer)",
            postFields!![1].toIntOrNull() != tracerPid)

        // The owned Stop: root identity is gone, the tree walk is skipped
        // fail-closed, and the stop intent must have been written BEFORE
        // any signal (in-memory launch identity, same process).
        XServer.stopDesktopMode(ctx)

        // Session death wakes the script at the fallback decision.
        assertTrue("session kill must be identity-verified",
            killVerified(gsPid, gsTicks!!, android.system.OsConstants.SIGKILL))

        // BOTH readers must honor the SAME retained intent file.
        val honorParent = awaitLine(base, "owned stop intent honored (post-exit decision)", 120_000)
        val honorObserver = awaitLine(base, "observer: owned stop intent honored", 120_000)
        assertTrue("parent decision must honor the owned-stop intent (P1): " + window(base).takeLast(400),
            honorParent != null)
        assertTrue("observer must ALSO honor the retained intent (first reader must not delete it)",
            honorObserver != null)
        Thread.sleep(12_000) // let any wrongful fallback announce itself
        val w = window(base)
        assertFalse("NO fallback may follow an owned stop of this launch",
            w.lineSequence().any { it.contains("falling back to bare gnome-shell") })
        // The intent file must STILL EXIST after both honors (retention proof).
        val intentLaunchId = XServer.currentLaunchIdForTest()
        assertTrue("launch identity unavailable from app for the intent-retention check",
            intentLaunchId != null)
        val intent = File(rootfs, "tmp/xdg/ldoa-stop-intent." + intentLaunchId)
        assertTrue("intent file must be retained after both readers honored it", intent.isFile)
        // Test-side hygiene only: the product never deletes intent files.
        intent.delete()
        // The SESSION's own gnome-shell is a documented P1 leftover (the
        // dead root skips the tree walk fail-closed; it is preserved,
        // never name-killed). Clean it up identity-verified so the NEXT
        // test's fresh-launch reset does not fail closed on it.
        for (sp in ourGnomeShellPids()) {
            val t = startTicksOf(sp); if (t != null) killVerified(sp, t, android.system.OsConstants.SIGKILL)
        }
    }

    @Test fun unexpectedDeathRecoversToRegisteredFallback() {
        val (proc, base, aliveLine) = freshLaunch()
        val gsPid = aliveLine.substringAfter("gnome-session alive pid=").takeWhile { it.isDigit() }.toInt()
        val gsTicksOpt = startTicksOf(gsPid)
        assertTrue("session start ticks must be readable", gsTicksOpt != null)
        val gsTicks = gsTicksOpt!!

        // Stale intent from an old world: old id, wrong key, foreign root.
        val stale = File(rootfs, "tmp/xdg/ldoa-stop-intent.9999999999-deadbeef")
        stale.writeText("stopkey=deadbeefcafe rootpid=1 rootstart=1 rootuid=0\n")

        // Unexpected death: session + owned shells gone, script alive,
        // NO owned stop. The fallback must EXEC and REGISTER, ignoring
        // the stale intent.
        assertTrue(killVerified(gsPid, gsTicks, android.system.OsConstants.SIGKILL))
        for (sp in ourGnomeShellPids()) {
            val t = startTicksOf(sp); if (t != null) killVerified(sp, t, android.system.OsConstants.SIGKILL)
        }
        val fellBack = awaitLine(base, "falling back to bare gnome-shell", 120_000)
        assertTrue("crash recovery must reach the fallback decision without an owned stop: " +
            window(base).takeLast(400), fellBack != null)
        assertTrue("stale intent must survive (never deleted by a non-owner)", stale.isFile)
        val registered = awaitLine(base, "fallback shell registered", 150_000)
        assertTrue("ACTUAL recovery: the fallback shell must exec and register on the bus: " +
            window(base).takeLast(400), registered != null)
        assertTrue("the registered fallback shell must be an owned process",
            ourGnomeShellPids().isNotEmpty())

        // Owned cleanup (live root: identity-verified teardown).
        XServer.stopDesktopMode(ctx)
        Thread.sleep(15_000)
        assertTrue("owned cleanup must remove the recovered shell",
            ourGnomeShellPids().isEmpty())
        // Test-side hygiene only: the product never deletes intent files.
        stale.delete()
    }

    @Test fun stopDuringFallbackHitsPostDecisionGate() {
        val (proc, base, aliveLine) = freshLaunch()
        val gsPid = aliveLine.substringAfter("gnome-session alive pid=").takeWhile { it.isDigit() }.toInt()
        val gsTicksOpt = startTicksOf(gsPid)
        assertTrue("session start ticks must be readable", gsTicksOpt != null)
        val gsTicks = gsTicksOpt!!

        // Stale intent from an old world: old id, wrong key, foreign root.
        val stale = File(rootfs, "tmp/xdg/ldoa-stop-intent.9999999999-deadbeef")
        stale.writeText("stopkey=deadbeefcafe rootpid=1 rootstart=1 rootuid=0\n")

        // Unexpected death: session + shell gone, script alive, NO owned
        // stop. Recovery must fire; the stale intent must be ignored.
        assertTrue(killVerified(gsPid, gsTicks, android.system.OsConstants.SIGKILL))
        for (sp in ourGnomeShellPids()) {
            val t = startTicksOf(sp); if (t != null) killVerified(sp, t, android.system.OsConstants.SIGKILL)
        }
        // Fast poll: the Stop below must land inside the 2s pre-exec
        // settle deterministically.
        val fellBack = awaitLine(base, "falling back to bare gnome-shell", 120_000, pollMs = 250)
        assertTrue("crash recovery must still reach the fallback decision without an owned stop: " +
            window(base).takeLast(400), fellBack != null)
        assertTrue("stale intent must survive (never deleted by a non-owner)", stale.isFile)

        // P2 race probe (deterministic): kill the tracer so the teardown
        // cannot kill the script before the gate, then Stop. The script is
        // inside the survivor-window/2s pre-exec span; one of the two
        // post-decision gates must honor the intent.
        val tracerPid2 = pidOf(proc)
        val tracerTicks2 = startTicksOf(tracerPid2)
        assertTrue("tracer kill must be identity-verified",
            tracerTicks2 != null && killVerified(tracerPid2, tracerTicks2!!, android.system.OsConstants.SIGKILL))
        XServer.stopDesktopMode(ctx)
        val gated = awaitLine(base, "owned stop intent honored (pre-exec gate)", 120_000)
            ?: awaitLine(base, "owned stop intent honored (fallback gate after survivor window)", 5_000)
        assertTrue("a post-decision gate must honor the Stop that raced the fallback: " + window(base).takeLast(400),
            gated != null)
        Thread.sleep(15_000)
        assertTrue("no owned gnome-shell may survive Stop + settle (no second WM exec'd)",
            ourGnomeShellPids().isEmpty())
        // Assert complete: the file is test-planted residue; remove it
        // here (the product itself must never delete a non-owned intent).
        stale.delete()
    }
}
