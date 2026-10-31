package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression coverage for the GNOME retry/stop fixes (2026-10-30).
 * Runs in the emulator-test job (no GNOME/rootfs needed: the
 * gate only reads a log file and polls a live Process; a sleep process
 * stands in for the session). Bounded: ~20s total.
 *
 * Covers what the happy-path suite cannot:
 *  - retry on a REUSED session: the registration marker must be accepted
 *    via the tracked bus address when the fresh window has no bus line;
 *  - a marker naming a STALE/FOREIGN bus must be rejected;
 *  - failure-path ownership retention + Stop teardown by identity.
 */
@RunWith(AndroidJUnit4::class)
class GnomeRetryGateTest {
    private val TAG = "PHONELINUX_RETRYGATE"

    @Test
    fun reusedSessionRetryAcceptsOwnBusAndRejectsForeign() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val log = File(ctx.filesDir, "test-retry-gate.log")
        val proc = ProcessBuilder("/system/bin/sh", "-c", "sleep 60").start()
        try {
            log.writeText("gnome-launch: session bus unix:path=/tmp/bus-AAA,guid=AAA\n")
            val afterBus = log.length()

            // Phase 1: fresh launch window, bus line present, no marker yet
            // -> null, and the gate LEARNS this session's bus address.
            assertNull("phase1: no marker yet must not pass",
                XServer.awaitShellRegistered(proc, log, 0L, 3_000))

            // Phase 2 (the reuse defect): a retry's fresh window has NO bus
            // line. The own-bus marker must now be accepted via the tracked
            // address - before the fix this returned null forever.
            log.appendText("gnome-launch: shell registered launch=1 bus=unix:path=/tmp/bus-AAA,guid=AAA\n")
            val hit = XServer.awaitShellRegistered(proc, log, afterBus, 4_000)
            assertTrue("reused-session own-bus marker not accepted: $hit",
                hit != null && hit.contains("guid=AAA"))

            // Phase 3: a later launch in the same log re-teaches the bus.
            log.appendText("gnome-launch: session bus unix:path=/tmp/bus-CCC,guid=CCC\n")
            val afterCcc = log.length()
            assertNull("phase3: marker-less window must not pass",
                XServer.awaitShellRegistered(proc, log, afterBus, 3_000))

            // Phase 4: STALE marker (old bus AAA) in the fresh window while
            // CCC is tracked -> rejected. Cross-session acceptance would
            // pass a shell that belongs to a dead session.
            val beforeStale = log.length()
            log.appendText("gnome-launch: shell registered launch=0 bus=unix:path=/tmp/bus-AAA,guid=AAA\n")
            val afterStale = log.length()
            assertNull("stale-bus marker must be rejected",
                XServer.awaitShellRegistered(proc, log, beforeStale, 3_000))

            // Phase 5: the tracked bus's own marker -> accepted.
            log.appendText("gnome-launch: shell registered launch=2 bus=unix:path=/tmp/bus-CCC,guid=CCC\n")
            val hit2 = XServer.awaitShellRegistered(proc, log, afterStale, 4_000)
            assertTrue("tracked-bus marker not accepted: $hit2",
                hit2 != null && hit2.contains("guid=CCC"))
            Log.i(TAG, "RETRYGATE-OK own-bus reuse accepted, stale/foreign rejected")
        } finally {
            proc.destroy()
            log.delete()
        }
    }

    @Test
    fun failedSessionOwnershipIsRetainedAndStopTearsDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val proc = ProcessBuilder("/system/bin/sh", "-c", "sleep 60").start()
        try {
            XServer.retainFailedSessionOwnership(ctx, proc)
            assertTrue("retained handle must be the failed session process",
                XServer.sessionProcess === proc)
            assertTrue("failed session process not alive pre-stop", proc.isAlive)
        } finally {
            XServer.stopDesktopMode(ctx)
        }
        var waited = 0
        while (proc.isAlive && waited < 10) { Thread.sleep(500); waited++ }
        assertTrue("stopDesktopMode did not kill the retained failed session", !proc.isAlive)
        assertNull("sessionProcess must be cleared after stop", XServer.sessionProcess)
        Log.i(TAG, "RETRYGATE-STOP-OK retained failed session torn down by identity")
    }

    @Test
    fun gateNullClassificationDistinguishesStartingFromDefinitive() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val log = File(ctx.filesDir, "test-gate-classify.log")
        val proc = ProcessBuilder("/system/bin/sh", "-c", "sleep 60").start()
        try {
            log.writeText("gnome-launch: session bus unix:path=/tmp/bus-AAA,guid=AAA\n")
            assertEquals("alive process without definitive verdict must be STILL_STARTING",
                XServer.GateNullClass.STILL_STARTING, XServer.classifyGateNull(proc, log, 0L))
            log.appendText("gnome-launch: fallback shell missing after 90s - giving up\n")
            assertEquals("script definitive fallback verdict must be DEFINITIVE_SCRIPT",
                XServer.GateNullClass.DEFINITIVE_SCRIPT, XServer.classifyGateNull(proc, log, 0L))
        } finally {
            proc.destroy()
            log.delete()
        }
        val dead = ProcessBuilder("/system/bin/sh", "-c", "true").start()
        dead.waitFor()
        log.writeText("")
        try {
            assertEquals("exited process must be DEFINITIVE_EXITED",
                XServer.GateNullClass.DEFINITIVE_EXITED, XServer.classifyGateNull(dead, log, 0L))
        } finally { log.delete() }
        Log.i(TAG, "RETRYGATE-CLASSIFY-OK starting vs definitive distinguished")
    }

    @Test
    fun startingSessionIsRetainedForRegateAndStopTearsDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val proc = ProcessBuilder("/system/bin/sh", "-c", "sleep 60").start()
        try {
            XServer.retainStartingSessionOwnership(ctx, proc, 0L)
            assertTrue("starting session must be marked STARTING",
                XServer.retainedSessionStarting)
            assertTrue("retained handle must be the starting session process",
                XServer.sessionProcess === proc)
            assertTrue("starting session process not alive pre-stop", proc.isAlive)
        } finally {
            XServer.stopDesktopMode(ctx)
        }
        var waited = 0
        while (proc.isAlive && waited < 10) { Thread.sleep(500); waited++ }
        assertTrue("stopDesktopMode did not kill the retained starting session", !proc.isAlive)
        assertTrue("retainedSessionStarting must clear after stop", !XServer.retainedSessionStarting)
        assertNull("sessionProcess must be cleared after stop", XServer.sessionProcess)
        Log.i(TAG, "RETRYGATE-STARTING-OK starting session retained, Stop tears it down by identity")
    }
}
