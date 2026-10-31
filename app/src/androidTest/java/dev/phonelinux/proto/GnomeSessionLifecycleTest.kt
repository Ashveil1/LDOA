package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression coverage for the GNOME session retry/teardown lifecycle.
 * POCO VD 2026-10-31: a retry on a retained wedged session produced the
 * identical 60s gate failure with "guest-gnome.log has no new output"
 * because the wedged process was silently reused. Uses plain sleep
 * processes as fake session roots: identity-verified teardown works on
 * any live process, so no rootfs is needed.
 */
@RunWith(AndroidJUnit4::class)
class GnomeSessionLifecycleTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val spawned = ArrayList<Process>()

    private fun sleeper(): Process =
        Runtime.getRuntime().exec(arrayOf("sh", "-c", "sleep 600")).also { spawned.add(it) }

    private fun pidOf(p: Process): Int {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; return f.getInt(p)
    }

    private fun alive(pid: Int): Boolean = File("/proc/" + pid).exists()

    @After fun cleanup() {
        XServer.teardownHookForTest = null
        try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { }
        for (p in spawned) try { p.destroy() } catch (t: Throwable) { }
    }

    /** Retry implies stop: a retained FAILED session must be torn down
     *  before relaunch. Pre-fix it was "reused" and no new session script
     *  ever ran - the gate could only time out on a frozen log. */
    @Test fun retainedFailedSessionIsTornDownBeforeRelaunch() {
        val old = sleeper(); val oldPid = pidOf(old)
        assertTrue(alive(oldPid))
        XServer.retainFailedSessionOwnership(ctx, old)
        assertTrue(XServer.teardownRetainedFailedSession(ctx))
        assertFalse("retained failed session must be torn down", alive(oldPid))
        assertNull(XServer.sessionProcess)
        assertFalse("second call must find nothing retained", XServer.teardownRetainedFailedSession(ctx))
    }

    /** Gate success: a registration marker naming THIS launch's own bus
     *  satisfies the gate (bus-pinned so a stale/foreign owner can never
     *  satisfy it). Uses a static log + live sleeper process fixture;
     *  this validates gate LOGIC, not the real GNOME session lifecycle. */
    @Test fun gateSucceedsOnRegisteredMarkerForThisBus() {
        val log = File.createTempFile("gate", ".log", ctx.cacheDir)
        log.writeText(
            "gnome-launch: session bus unix:path=/tmp/dbus-TEST,guid=abc\n" +
            "gnome-launch: shell registered launch=1 bus=unix:path=/tmp/dbus-TEST,guid=abc\n")
        val p = sleeper()
        val hit = XServer.awaitShellRegistered(p, log, 0L, 10_000)
        assertNotNull(hit)
        assertTrue(hit!!.contains("shell registered"))
    }

    /** The fallback-phase marker is DISTINCT and satisfies the gate as a
     *  degraded success the caller identifies by marker text: a bare-shell
     *  desktop is never indistinguishable from a full GNOME session. */
    @Test fun gateSucceedsOnDistinctFallbackMarker() {
        val log = File.createTempFile("gate", ".log", ctx.cacheDir)
        log.writeText(
            "gnome-launch: session bus unix:path=/tmp/dbus-TEST,guid=abc\n" +
            "gnome-launch: fallback shell registered launch=1 bus=unix:path=/tmp/dbus-TEST,guid=abc\n")
        val p = sleeper()
        val hit = XServer.awaitShellRegistered(p, log, 0L, 10_000)
        assertNotNull(hit)
        assertTrue(hit!!.contains("fallback shell registered"))
    }

    /** Gate fail-fast: the fallback-phase verdict is definitive (POCO log
     *  2026-10-30: collapse AFTER registration; first-phase verdict is not
     *  final because the bare-shell fallback may still register). */
    @Test fun gateFailsFastOnDefinitiveFallbackVerdict() {
        val log = File.createTempFile("gate", ".log", ctx.cacheDir)
        log.writeText(
            "gnome-launch: session bus unix:path=/tmp/dbus-TEST,guid=abc\n" +
            "gnome-launch: shell missing after 60s launch=1\n" +
            "gnome-launch: fallback shell missing after 90s launch=1\n")
        val p = sleeper()
        val t0 = System.currentTimeMillis()
        val hit = XServer.awaitShellRegistered(p, log, 0L, 60_000)
        assertNull(hit)
        assertTrue("fail-fast on definitive verdict, not the full budget",
            System.currentTimeMillis() - t0 < 30_000)
    }

    /** Gate patience: the first-phase verdict alone must NOT end the gate;
     *  the unified fallback may still be starting. Expiry must come from
     *  the budget, not an early exit. */
    @Test fun gateKeepsWaitingAfterFirstPhaseVerdict() {
        val log = File.createTempFile("gate", ".log", ctx.cacheDir)
        log.writeText(
            "gnome-launch: session bus unix:path=/tmp/dbus-TEST,guid=abc\n" +
            "gnome-launch: shell missing after 60s launch=1\n")
        val p = sleeper()
        val t0 = System.currentTimeMillis()
        val hit = XServer.awaitShellRegistered(p, log, 0L, 8_000)
        assertNull(hit)
        assertTrue(System.currentTimeMillis() - t0 >= 8_000)
    }

    /** Clobber window: if ownership changes hands mid-teardown (concurrent
     *  relaunch/retain), the teardown must still kill its snapshot but leave
     *  the NEW session's handles, X server and active-pref intact. */
    @Test fun stopSkipsClearsWhenOwnershipChangesHandsMidTeardown() {
        val old = sleeper(); val oldPid = pidOf(old)
        XServer.retainFailedSessionOwnership(ctx, old)
        val newer = sleeper(); val newPid = pidOf(newer)
        XServer.teardownHookForTest = { XServer.retainFailedSessionOwnership(ctx, newer) }
        XServer.stopDesktopMode(ctx)
        assertFalse("snapshot (old) session must be torn down", alive(oldPid))
        assertTrue("concurrently established session must survive", alive(newPid))
        assertSame("new session's ownership must not be clobbered", newer, XServer.sessionProcess)
    }

    /** Simultaneous stop/retry: two concurrent teardowns plus a fully
     *  idempotent third must settle with the session dead and ownership
     *  cleared, without signaling a reused pid. */
    @Test fun concurrentStopsSettleConsistently() {
        val s = sleeper(); val pid = pidOf(s)
        XServer.retainFailedSessionOwnership(ctx, s)
        val t = Thread { XServer.stopDesktopMode(ctx) }
        t.start(); XServer.stopDesktopMode(ctx); t.join(30_000)
        assertFalse("concurrent stop must not wedge", t.isAlive)
        assertFalse(alive(pid))
        assertNull(XServer.sessionProcess)
        XServer.stopDesktopMode(ctx) // empty-state idempotency
    }
}
