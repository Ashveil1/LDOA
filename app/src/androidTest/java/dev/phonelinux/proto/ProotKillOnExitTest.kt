package dev.phonelinux.proto

import android.system.OsConstants
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
 * Behavior fixtures for the --kill-on-exit semantics added to the GNOME
 * session launcher, and for the identity-owned SessionReset helper that
 * replaced the /proc name-kill resets. Vendored proot 5.1.107.101 source
 * refs: flag set on the ROOT tracee only (cli/proot.c:195-199), preserved
 * across the exec leader-replace (tracee.c:436), and fires from
 * terminate_tracee on NORMAL marked-tracee exit (tracee.c:350-362 ->
 * kill_all_tracees = SIGKILL to every tracee, tracee.c:1043-1049). A host
 * SIGKILL of the tracer runs NO proot userspace, so the flag cannot fire
 * on the phantom-trim path - fixture 3 documents that gap.
 *
 * The busCapture* fixtures bound the pid-reuse closure of
 * XServer.captureSessionBusIdentityForTest: the launcher-written
 * creation-time record (pid + start ticks + uid + nonce, validated
 * against the LIVE process and the caller's OWN launch nonce) is the
 * only adoption path. Missing record, tick mismatch (pid REUSED inside
 * any time window), nonce from another launch, and non-bus role are all
 * REJECTED (preserve, never kill); an older-generation capture never
 * overwrites a newer world's identity; a launch-owned record captures
 * and the identity-verified Stop ends AND clears it.
 */
@RunWith(AndroidJUnit4::class)
class ProotKillOnExitTest {
    private val TAG = "ProotKillOnExitTest"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val spawned = mutableListOf<Process>()

    @After fun cleanup() {
        for (p in spawned) try { p.destroy() } catch (t: Throwable) { }
        // Restore the bus record file exactly as the fixture found it.
        val (existed, bytes) = savedRecord
        val f = busRecordFile()
        if (existed) { f.parentFile?.mkdirs(); f.writeBytes(bytes) } else f.delete()
        val (logExisted, logBytes) = savedGnomeLog
        val lf = gnomeLogFile()
        if (logExisted) lf.writeBytes(logBytes) else lf.delete()
        XServer.installBusIdentForTest(null, 0L)
    }

    /** (existed, bytes) of root/.gnome-bus.ident before the fixture ran. */
    private var savedRecord: Pair<Boolean, ByteArray> = false to ByteArray(0)

    private fun busRecordFile(): File =
        File(DistroInstaller(ctx).rootfsDir(Distros.UBUNTU), "root/.gnome-bus.ident")

    /** Boundary BEFORE the fixture mutates anything: save the record. */
    private fun saveBusRecordBoundary() {
        val f = busRecordFile()
        savedRecord = if (f.isFile) true to f.readBytes() else false to ByteArray(0)
    }

    /** (existed, bytes) of guest-gnome.log before the fixture ran. */
    private var savedGnomeLog: Pair<Boolean, ByteArray> = false to ByteArray(0)

    private fun gnomeLogFile(): File = File(ctx.filesDir, "guest-gnome.log")

    /** Boundary BEFORE the fixture writes its own guest log. */
    private fun saveGnomeLogBoundary() {
        val f = gnomeLogFile()
        savedGnomeLog = if (f.isFile) true to f.readBytes() else false to ByteArray(0)
    }

    private fun writeGnomeLog(text: String) = gnomeLogFile().writeText(text)

    /** Boundary for retain calls with no launch of their own: the current
     *  end of the guest log, so no stale bus line can ever match. */
    private fun currentGnomeLogLength(): Long {
        val f = java.io.File(ctx.filesDir, "guest-gnome.log")
        return if (f.isFile) f.length() else 0L
    }

    /** android.jar's java.lang.Process stub has no pid(): reflect the
     *  field (same approach as XServer.pidOf and the lifecycle tests). */
    private fun pidOf(p: Process): Int =
        try { val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p) }
        catch (t: Throwable) { -1 }

    private fun spawnProot(killOnExit: Boolean, sleepSecs: Int): Process {
        val nd = ctx.applicationInfo.nativeLibraryDir
        val argv = mutableListOf(File(nd, "libproot.so").absolutePath, "--link2symlink", "-0")
        if (killOnExit) argv.add("--kill-on-exit")
        argv.addAll(listOf("/system/bin/sh", "-c", "sleep $sleepSecs & exit 0"))
        val pb = ProcessBuilder(argv)
        pb.environment()["PROOT_LOADER"] = File(nd, "libproot_loader.so").absolutePath
        pb.environment()["PROOT_LOADER_32"] = File(nd, "libproot_loader32.so").absolutePath
        pb.environment()["PROOT_TMP_DIR"] = Proot.prootTmpDir(ctx).absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = nd
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(ctx.cacheDir, "koe-fixture.log")))
        return pb.start().also { spawned.add(it) }
    }

    /** (pid to starttime) for own-uid processes whose cmdline contains the
     *  needle. cmdline is NUL-separated RAW: normalize before matching, and
     *  restrict to own uid - a match says "our process", nothing more. */
    private fun findByCmdline(needle: String): Map<Int, Long> {
        val out = LinkedHashMap<Int, Long>()
        val myUid = android.os.Process.myUid()
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: return out
        for (f in dir) {
            val st = try { android.system.Os.stat(f.absolutePath) } catch (t: Throwable) { continue }
            if (st.st_uid != myUid) continue
            val cmd = try { File(f, "cmdline").readText().replace('\u0000', ' ') } catch (t: Throwable) { continue }
            if (!cmd.contains(needle)) continue
            val stat = try { File(f, "stat").readText() } catch (t: Throwable) { continue }
            val close = stat.lastIndexOf(')')
            if (close < 0) continue
            val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 20) continue
            val start = try { rest[19].toLong() } catch (t: Throwable) { continue }
            out[f.name.toInt()] = start
        }
        return out
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(250)
        }
        return cond()
    }

    @Test fun flaggedRootReapsTraceesOnNormalExit() {
        val p = spawnProot(true, 121)
        // POSITIVE CONTROL first: the tracee must be observed ALIVE, or a
        // later "all gone" is vacuous (a failed spawn would pass it).
        assertTrue("positive control: backgrounded tracee must appear before any reap claim",
            waitUntil(10_000) { findByCmdline("sleep 121").isNotEmpty() })
        assertTrue("flagged root sh should exit promptly", p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
        val gone = waitUntil(10_000) { findByCmdline("sleep 121").isEmpty() }
        assertTrue(
            "--kill-on-exit: normal root exit must SIGKILL remaining tracees (terminate_tracee -> kill_all_tracees)",
            gone)
    }

    @Test fun unflaggedRootRetainsWorldOnRootExit() {
        val p = spawnProot(false, 122)
        Thread.sleep(3_000) // the root sh has exited by now
        assertTrue(
            "unflagged baseline: the backgrounded tracee SURVIVES the root's normal exit",
            findByCmdline("sleep 122").isNotEmpty())
        assertTrue(
            "unflagged baseline: proot itself stays alive to trace the survivor (the retention the GNOME launcher flag replaces)",
            p.isAlive)
    }

    @Test fun hostTracerSigkillBypassesFlagUserspace() {
        val p = spawnProot(true, 123)
        assertTrue("backgrounded tracee should appear", waitUntil(10_000) { findByCmdline("sleep 123").isNotEmpty() })
        val prootPid = pidOf(p)
        android.os.Process.sendSignal(prootPid, OsConstants.SIGKILL)
        val tracerGone = waitUntil(10_000) { !p.isAlive }
        Thread.sleep(1_500)
        val survivors = findByCmdline("sleep 123")
        Log.i(TAG, "tracer-SIGKILL outcome: tracer dead=" + (!p.isAlive) +
            " sleep-123 survivors=" + survivors.keys +
            " (flag userspace never ran; any survivor is a reparented orphan - reaped only by" +
            " identity-captured teardown such as XServer.stopDesktopMode, never by name)")
        assertTrue("tracer must be dead after host SIGKILL", tracerGone)
        // Reap survivors by CAPTURED IDENTITY: re-verify (pid, starttime)
        // immediately before the signal; a reused pid is never signaled.
        for ((pid, start) in survivors) {
            val now = findByCmdline("sleep 123")[pid]
            val uidOk = try {
                android.system.Os.stat("/proc/" + pid).st_uid == android.os.Process.myUid()
            } catch (t: Throwable) { false }
            if (now != null && now == start && uidOk) {
                try { android.os.Process.sendSignal(pid, OsConstants.SIGKILL) } catch (t: Throwable) { }
            }
        }
    }

    @Test fun resetHelperKeepsStartingSession() {
        val prev = XServer.retainedSessionStarting
        try {
            XServer.setRetainedSessionStartingForTest(true)
            val r = SessionReset.resetForFreshLaunch(ctx, Distros.UBUNTU) { }
            assertTrue(
                "a live/uncertain STARTING session must never be ended by a fresh-launch reset",
                r.startsWith("reset: KEPT"))
        } finally {
            XServer.setRetainedSessionStartingForTest(prev)
        }
    }

    @Test fun untrackedWorldFailsClosedAndIsNotKilled() {
        val fake = ProcessBuilder("/system/bin/sh", "-c", "exec -a gnome-session-binary sleep 124")
            .start().also { spawned.add(it) }
        Thread.sleep(500)
        val worlds = SessionReset.scanUntrackedGnomeWorlds()
        assertTrue("detection scan must see the fake world", worlds.values.any { it.contains("gnome-session-binary") })
        var threw = false
        try {
            SessionReset.failClosedOnUntracked(mapOf(424242 to "gnome-session-binary sleep 1"))
        } catch (a: AssertionError) {
            threw = true
            assertTrue(a.message!!.contains("FAIL CLOSED"))
        }
        assertTrue("a non-empty untracked set must fail closed", threw)
        assertTrue("the untracked world must NOT be killed by name", fake.isAlive)
    }

    // ---- captureSessionBusIdentityForTest record-binding fixtures ----

    private fun spawnFakeBus(sleepSecs: Int): Process =
        // argv0 carries the full dbus-daemon session shape after NUL
        // normalization; mksh exec -a sets argv[0] only.
        ProcessBuilder("/system/bin/sh", "-c",
            "exec -a 'dbus-daemon --fork --session' sleep " + sleepSecs).start().also { spawned.add(it) }

    private fun liveStartTicks(pid: Int): String {
        val stat = File("/proc/" + pid + "/stat").readText()
        val close = stat.lastIndexOf(')')
        assertTrue("fixture needs a parseable /proc stat", close > 0)
        val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
        assertTrue("fixture needs stat field 22", rest.size >= 20)
        return rest[19]
    }

    private fun writeBusRecord(pid: Int, ticks: String, uid: Int, nonce: String) {
        val f = busRecordFile()
        f.parentFile?.mkdirs()
        f.writeText("pid=" + pid + " startticks=" + ticks + " uid=" + uid + " nonce=" + nonce + "\n")
    }

    private fun validRecordFor(p: Process, nonce: String) =
        writeBusRecord(pidOf(p), liveStartTicks(pidOf(p)),
            android.os.Process.myUid(), nonce)

    @Test fun busCaptureRejectsMissingRecord() {
        saveBusRecordBoundary()
        busRecordFile().delete()
        assertFalse("no creation record, no capture - never adopt any bus",
            XServer.captureSessionBusIdentityForTest(ctx, "any-nonce"))
    }

    @Test fun busCaptureRejectsTickMismatchReuseInWindow() {
        // The reuse shape INSIDE any time window: the recorded bus died
        // and its pid was recycled moments later by ANOTHER same-uid
        // dbus-shaped process. Name, uid, role and freshness all match;
        // only the exact creation ticks expose the replacement.
        saveBusRecordBoundary()
        val fake = spawnFakeBus(130)
        assertTrue(waitUntil(10_000) { findByCmdline("dbus-daemon --fork --session").isNotEmpty() })
        val pid = pidOf(fake)
        val wrongTicks = (liveStartTicks(pid).toLong() + 1).toString()
        writeBusRecord(pid, wrongTicks, android.os.Process.myUid(), "nonce-reuse")
        assertFalse("live ticks != creation record ticks: a reused pid is never adopted",
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-reuse"))
        assertTrue("rejected reused pid must survive (preserve, never kill)", fake.isAlive)
    }

    @Test fun busCaptureRejectsNonceFromAnotherLaunch() {
        // A record left by ANOTHER launch: fully valid for the live fake,
        // but the caller's own launch logged a different nonce.
        saveBusRecordBoundary()
        val fake = spawnFakeBus(131)
        assertTrue(waitUntil(10_000) { findByCmdline("dbus-daemon --fork --session").isNotEmpty() })
        validRecordFor(fake, "nonce-other-launch")
        assertFalse("a record whose nonce is not THIS launch's is never adopted",
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-this-launch"))
        assertTrue("rejected other-launch bus must survive", fake.isAlive)
    }

    @Test fun busCaptureRejectsNonBusRole() {
        saveBusRecordBoundary()
        val fake = ProcessBuilder("/system/bin/sh", "-c", "sleep 133").start().also { spawned.add(it) }
        Thread.sleep(500)
        validRecordFor(fake, "nonce-role")
        assertFalse("an own-uid process without the dbus-daemon --session shape is not the session bus",
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-role"))
        assertTrue("rejected non-bus process must survive", fake.isAlive)
    }

    @Test fun busCaptureOlderGenerationNeverOverwritesNewerWorld() {
        saveBusRecordBoundary()
        val fakeA = spawnFakeBus(134)
        val fakeB = spawnFakeBus(135)
        assertTrue(waitUntil(10_000) { findByCmdline("dbus-daemon --fork --session").size >= 2 })
        validRecordFor(fakeA, "nonce-a")
        assertTrue("launch-owned record must capture",
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-a"))
        // A NEWER world (generation far ahead) already holds bus B.
        val bIdent = XServer.PIdent(pidOf(fakeB), liveStartTicks(pidOf(fakeB)),
            android.os.Process.myUid())
        XServer.installBusIdentForTest(bIdent, Long.MAX_VALUE - 1)
        // The stale older-generation capture (record still names A) must
        // NOT overwrite it.
        assertFalse("an older-generation capture never overwrites a newer world's bus identity",
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-a"))
        // Behavioral proof: the identity-verified Stop ends B (the stored
        // newer world), never A (the stale record's pid)...
        XServer.stopDesktopMode(ctx)
        assertTrue("the stored newer-world bus must be the one torn down",
            waitUntil(10_000) { !fakeB.isAlive })
        assertTrue("the stale capture's pid must survive untouched", fakeA.isAlive)
        // ...and Stop clears the stored identity.
        assertTrue("Stop must clear the bus identity",
            XServer.diagnosticSnapshot(ctx).contains("session_bus_ident=(none)"))
    }

    @Test fun busCaptureAcceptsLaunchOwnedRecordThenStopEndsAndClearsIt() {
        saveBusRecordBoundary()
        val fake = spawnFakeBus(136)
        assertTrue(waitUntil(10_000) { findByCmdline("dbus-daemon --fork --session").isNotEmpty() })
        validRecordFor(fake, "nonce-owned")
        assertTrue("a launch-owned record (exact live ticks, own uid, role, matching nonce) must capture",
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-owned"))
        // The identity-verified Stop must end the captured bus even with
        // no session root tracked (bus-ident-only teardown branch)...
        XServer.stopDesktopMode(ctx)
        assertTrue("captured bus must be ended by the identity-verified teardown",
            waitUntil(10_000) { findByCmdline("dbus-daemon --fork --session").isEmpty() })
        // ...and clear the stored identity.
        assertTrue("Stop must clear the bus identity",
            XServer.diagnosticSnapshot(ctx).contains("session_bus_ident=(none)"))
    }

    @Test fun busConfirmReuseWithoutNewLaunchKeepsRetainedIdentity() {
        // A REUSED session (healthy, or re-gated STILL-STARTING) wrote
        // its launch lines in the ORIGINAL launch window, not in the
        // current call's. Confirming against the current call's base
        // finds no launch there - UNCERTAIN, never contradictory: the
        // still-valid retained identity must be KEPT (never clobbered)
        // and no other record adopted.
        saveBusRecordBoundary()
        saveGnomeLogBoundary()
        val fake = spawnFakeBus(137)
        assertTrue(waitUntil(10_000) { findByCmdline("dbus-daemon --fork --session").isNotEmpty() })
        validRecordFor(fake, "nonce-originating")
        writeGnomeLog(
            "older world noise\n" +
            "gnome-launch: launch id=launch-1\n" +
            "gnome-launch: session bus unix:path=/tmp/bus nonce=nonce-originating\n" +
            "gnome-launch: shell registered on the session bus\n")
        // The reuse call confirms against the ORIGINATING base (the
        // sessionGateLogBase the gate kept): the launch is found and
        // the bus identity binds.
        XServer.mergeLaunchConfirmBusIdent(ctx, 0L, reused = true)
        val owned = "session_bus_ident=pid=" + pidOf(fake) + " "
        assertTrue("originating-window confirm must bind the reused session's bus",
            XServer.diagnosticSnapshot(ctx).contains(owned))
        // The NEXT reuse call's own window holds no new launch: the
        // null capture must KEEP the retained identity.
        XServer.mergeLaunchConfirmBusIdent(ctx, currentGnomeLogLength(), reused = true)
        assertTrue("reuse with no new launch must never clobber a retained identity",
            XServer.diagnosticSnapshot(ctx).contains(owned))
        // A FRESH launch's null capture still clears the old world's
        // identity (that world is gone).
        XServer.mergeLaunchConfirmBusIdent(ctx, currentGnomeLogLength(), reused = false)
        assertTrue("fresh launch with no capturable bus clears the old world's identity",
            XServer.diagnosticSnapshot(ctx).contains("session_bus_ident=(none)"))
        assertTrue("uncertainty never kills: the bus survives every merge", fake.isAlive)
    }

    @Test fun retainedRootExitedBeforeTeardownSkipsWalk() {
        val fake = ProcessBuilder("/system/bin/sh", "-c", "sleep 125").start().also { spawned.add(it) }
        XServer.retainFailedSessionOwnership(ctx, fake, currentGnomeLogLength())
        fake.destroy() // the captured root dies BEFORE the teardown
        assertTrue("fake root should die", waitUntil(10_000) { !fake.isAlive })
        val torn = XServer.teardownRetainedFailedSession(ctx)
        assertTrue("teardown of a retained failed session runs (fail-closed: root identity no longer matches -> tree walk skipped, nothing signaled by name)", torn)
        assertFalse("retained-failed flag cleared by the teardown", XServer.teardownRetainedFailedSession(ctx))
    }
}
