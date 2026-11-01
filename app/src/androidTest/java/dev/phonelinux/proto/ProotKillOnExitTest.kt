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
        // Fixture instances share one instrumentation process: the bus
        // ident clear alone left retained/gate/session statics leaking
        // into the next fixture.
        XServer.resetOwnershipStateForTest()
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
        val fake = spawnShapedSleep("gnome-session-binary", 124)
        assertShapedFakeAlive(fake, "gnome-session-binary")
        Thread.sleep(500)
        val worlds = SessionReset.scanUntrackedGnomeWorlds()
        assertTrue("detection scan must see the fake world " + procSnapshot(fake, "fake-world"),
            worlds.values.any { it.contains("gnome-session-binary") })
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

    /** A long-lived fake whose /proc cmdline CONTAINS [needleDir]. NOT
     *  `sh -c "exec -a '<shape>' sleep N"`: /system/bin/sleep is the
     *  toybox MULTICALL binary on Android 14, and toybox resolves the
     *  applet from basename(argv[0]) - a fake argv0 ("dbus-daemon ...",
     *  "gnome-session-binary") is no applet, so toybox prints "Unknown
     *  command" and exits 127 immediately (toybox 0.8.9 main.c
     *  toybox_main/unknown; AOSP android-14 README lists sleep as
     *  toybox). Every exec-a fake died within milliseconds; the sh -c
     *  wrapper's own cmdline transiently contains the needle, so
     *  waitUntil raced a dying wrapper. The shape here comes from a
     *  DIRECTORY NAME instead: argv[0]'s basename stays a real applet
     *  ("sleep") while the PATH carries the needle, so the fake truly
     *  lives. stderr/stdout go to a per-shape log for diagnostics. */
    private fun spawnShapedSleep(needleDir: String, sleepSecs: Int): Process {
        val dir = File(ctx.cacheDir, needleDir)
        dir.mkdirs()
        val link = File(dir, "sleep")
        link.delete()
        android.system.Os.symlink("/system/bin/sleep", link.absolutePath)
        val out = File(ctx.cacheDir, "koe-fake-" + needleDir.replace(Regex("[^A-Za-z0-9]+"), "_") + ".log")
        out.delete()
        val pb = ProcessBuilder(link.absolutePath, sleepSecs.toString())
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out))
        return pb.start().also { spawned.add(it) }
    }

    private fun spawnFakeBus(sleepSecs: Int): Process =
        spawnShapedSleep("dbus-daemon --fork --session", sleepSecs)

    /** Bounded diagnostic snapshot of ONE fixture-owned process (never a
     *  scan of others): pid, liveness/exit, uid, start ticks, cmdline. */
    private fun procSnapshot(p: Process, tag: String): String {
        val pid = pidOf(p)
        val cmd = try { File("/proc/" + pid + "/cmdline").readText().replace('\u0000', ' ').trim() }
            catch (t: Throwable) { "(unreadable: " + t.javaClass.simpleName + ")" }
        val stat = try { File("/proc/" + pid + "/stat").readText() }
            catch (t: Throwable) { "" }
        val ticks = if (stat.isEmpty()) "?" else {
            val rest = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
            if (rest.size >= 20) rest[19] else "?"
        }
        val uid = try { android.system.Os.stat("/proc/" + pid).st_uid.toString() } catch (t: Throwable) { "?" }
        val state = if (p.isAlive) "alive" else "exited rc=" + try { p.exitValue().toString() } catch (t: Throwable) { "?" }
        return "[" + tag + " pid=" + pid + " " + state + " uid=" + uid + " (mine " + android.os.Process.myUid() + ") ticks=" + ticks + " cmd=" + cmd + "]"
    }

    private fun rejection(): String = " rejection=" + (XServer.lastBusCaptureRejection ?: "(none)")

    /** Positive control for a shaped fake, verified on THE Process this
     *  fixture spawned (never a wrapper race): its own /proc/<pid>/
     *  cmdline has argv0 basename "sleep" (a real toybox applet, so the
     *  process actually lives), the full cmdline carries [needleDir] via
     *  the directory name, and it is still alive 2s after spawn - a
     *  dying sh -c wrapper can transiently contain the needle; a
     *  2-second-old sleep cannot. */
    private fun assertShapedFakeAlive(p: Process, needleDir: String) {
        val pid = pidOf(p)
        Thread.sleep(2_000)
        // argv0 is the FIRST NUL-separated field: the needle directory
        // name itself contains spaces, so splitting the joined display
        // string on spaces would cut argv0 in the middle of the path.
        val fields = try { File("/proc/" + pid + "/cmdline").readText().split('\u0000').filter { it.isNotEmpty() } }
            catch (t: Throwable) { emptyList() }
        val cmd = fields.joinToString(" ")
        assertTrue("shaped fake must be alive 2s after spawn " + procSnapshot(p, "fake"), p.isAlive)
        assertTrue("argv0 basename must be the real sleep applet, not a faked argv0: " + cmd,
            fields.firstOrNull()?.substringAfterLast('/') == "sleep")
        assertTrue("cmdline must carry the shape via the directory name: " + cmd,
            cmd.contains(needleDir))
    }

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
        // Positive control FIRST: a live valid bus with a valid record
        // CAPTURES - so the rejection below is the missing record alone.
        val fake = spawnFakeBus(129)
        assertShapedFakeAlive(fake, "dbus-daemon --fork --session")
        validRecordFor(fake, "nonce-control")
        assertTrue("positive control: valid record + live fake must capture " + procSnapshot(fake, "control") + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-control"))
        // Mutation: remove ONLY our own record (same pid/ticks/uid/nonce
        // stay valid). The saved boundary restores any pre-existing file
        // after the fixture.
        busRecordFile().delete()
        assertFalse("no creation record, no capture - never adopt any bus." + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-control"))
        assertTrue("rejected bus must survive " + procSnapshot(fake, "fake"), fake.isAlive)
    }

    @Test fun busCaptureRejectsTickMismatchReuseInWindow() {
        // The reuse shape INSIDE any time window: the recorded bus died
        // and its pid was recycled moments later by ANOTHER same-uid
        // dbus-shaped process. Name, uid, role and freshness all match;
        // only the exact creation ticks expose the replacement.
        saveBusRecordBoundary()
        val fake = spawnFakeBus(130)
        assertShapedFakeAlive(fake, "dbus-daemon --fork --session")
        val pid = pidOf(fake)
        // Positive control: exact live ticks + own uid + role + matching
        // nonce MUST capture - otherwise the rejection below is vacuous.
        validRecordFor(fake, "nonce-reuse")
        assertTrue("positive control: launch-owned record must capture " + procSnapshot(fake, "control") + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-reuse"))
        val wrongTicks = (liveStartTicks(pid).toLong() + 1).toString()
        writeBusRecord(pid, wrongTicks, android.os.Process.myUid(), "nonce-reuse")
        assertFalse("live ticks != creation record ticks: a reused pid is never adopted." + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-reuse"))
        assertTrue("rejected reused pid must survive (preserve, never kill) " + procSnapshot(fake, "fake"), fake.isAlive)
    }

    @Test fun busCaptureRejectsNonceFromAnotherLaunch() {
        // A record left by ANOTHER launch: fully valid for the live fake,
        // but the caller's own launch logged a different nonce.
        saveBusRecordBoundary()
        val fake = spawnFakeBus(131)
        assertShapedFakeAlive(fake, "dbus-daemon --fork --session")
        validRecordFor(fake, "nonce-other-launch")
        // Positive control: the record's OWN nonce captures - the
        // rejection below must then be the nonce difference alone.
        assertTrue("positive control: matching nonce must capture " + procSnapshot(fake, "control") + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-other-launch"))
        assertFalse("a record whose nonce is not THIS launch's is never adopted." + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-this-launch"))
        assertTrue("rejected other-launch bus must survive " + procSnapshot(fake, "fake"), fake.isAlive)
    }

    @Test fun busCaptureRejectsNonBusRole() {
        saveBusRecordBoundary()
        // Positive control FIRST: the SAME pid/record/ticks/uid/nonce
        // with a valid bus role CAPTURES.
        val fake = spawnFakeBus(133)
        assertShapedFakeAlive(fake, "dbus-daemon --fork --session")
        validRecordFor(fake, "nonce-role")
        assertTrue("positive control: live valid bus must capture " + procSnapshot(fake, "control") + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-role"))
        // Mutation A: deny ONLY the session-bus role binding for that
        // same pid via the test seam - every other field stays valid.
        XServer.denyBusRoleForTest(pidOf(fake))
        assertFalse("a process denied the session-bus role is never adopted." + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-role"))
        assertTrue("mutation A rejection must come from the seam: " + rejection(),
            XServer.lastBusCaptureRejection?.contains("test seam") == true)
        assertTrue("seam-rejected process must survive " + procSnapshot(fake, "fake"), fake.isAlive)
        // Subcase B (the REAL cmdline parser, no seam): a plain live
        // own-uid sleep with an otherwise-valid record - alive, exact
        // live ticks, own uid, matching nonce; only the role shape is
        // wrong. The seam above can never detect a broken real role
        // check; this one must be rejected by the actual parser.
        val plain = spawnShapedSleep("not-a-bus", 137)
        assertShapedFakeAlive(plain, "not-a-bus")
        validRecordFor(plain, "nonce-plain")
        assertFalse("a live own-uid process without the session-bus shape is never adopted." + rejection(),
            XServer.captureSessionBusIdentityForTest(ctx, "nonce-plain"))
        assertTrue("subcase B rejection must come from the real role parser: " + rejection(),
            XServer.lastBusCaptureRejection?.contains("session-bus shape") == true)
        assertTrue("rejected plain process must survive " + procSnapshot(plain, "plain"), plain.isAlive)
    }

    @Test fun busCaptureOlderGenerationNeverOverwritesNewerWorld() {
        saveBusRecordBoundary()
        val fakeA = spawnFakeBus(134)
        val fakeB = spawnFakeBus(135)
        assertShapedFakeAlive(fakeA, "dbus-daemon --fork --session")
        assertShapedFakeAlive(fakeB, "dbus-daemon --fork --session")
        assertTrue("both fake buses must coexist " + procSnapshot(fakeA, "fakeA") + procSnapshot(fakeB, "fakeB"),
            findByCmdline("dbus-daemon --fork --session").size >= 2)
        validRecordFor(fakeA, "nonce-a")
        assertTrue("launch-owned record must capture " + procSnapshot(fakeA, "fakeA") + rejection(),
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
        assertTrue("a launch-owned record (exact live ticks, own uid, role, matching nonce) must capture " + procSnapshot(fake, "fake") + rejection(),
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
        assertTrue("originating-window confirm must bind the reused session's bus " + procSnapshot(fake, "fake") + rejection(),
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
