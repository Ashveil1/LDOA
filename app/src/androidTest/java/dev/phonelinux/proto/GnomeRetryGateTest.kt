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
 * Regression coverage for the 2026-10-30 approved GNOME retry/stop fixes
 * (Ash 10:35). Runs in the emulator-test job (no GNOME/rootfs needed: the
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
}
