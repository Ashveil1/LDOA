package dev.phonelinux.proto

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * POCO 2026-11-01 reentry trap: with a desktop start in flight, a second
 * openDesktop (recreated MainActivity / cold re-entry / Continue tap) was
 * refused with "tap ignored" and NO callback - the caller's preparing
 * overlay never cleared, had no controls, and with the session notification
 * dismissed the user had no route to the desktop and no Stop. Fix: the
 * second caller RE-ATTACHES to the in-flight attempt (one atomically
 * swapped routing holder; still exactly one attempt, no duplicate
 * session), the overlay carries Stop session / Hide controls, and attempt
 * epochs keep a stale completion from reopening the X view over a stopped
 * session or clearing a newer attempt's preparing.
 */
@RunWith(AndroidJUnit4::class)
class OpenDesktopReentryTest {

    private lateinit var device: UiDevice
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instr)
        ctx = instr.targetContext
        XServer.resetOwnershipStateForTest()
        // No cold auto-enter racing these tests.
        ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
            .edit().putBoolean("ubuntu_desktop_active", false).apply()
    }

    @After
    fun tearDown() {
        // Never leak a simulated in-flight attempt or the session service
        // into later phases.
        if (XServer.preparing) XServer.finishAttempt(ctx, false, "test cleanup")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SessionService.stop(ctx)
        XServer.resetOwnershipStateForTest()
    }

    @Test
    fun reattachReceivesFailureOutcome() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val errorsA = CopyOnWriteArrayList<String>()
        val stagesB = CopyOnWriteArrayList<String>()
        val errorsB = CopyOnWriteArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var seq = 0L
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                seq = XServer.beginAttemptForTest()
                XServer.openDesktop(a, Distros.UBUNTU,
                    onStage = { }, onError = { errorsA.add(it) })
                // A still newer screen re-attaches again: latest caller wins.
                XServer.openDesktop(a, Distros.UBUNTU,
                    onStage = { stagesB.add(it) }, onError = { errorsB.add(it) })
            }
            assertTrue("re-attach reported on stage line",
                stagesB.any { it.contains("already in progress") && it.contains("following") })
            assertTrue("attempt still marked in flight after re-attach", XServer.preparing)
            // The in-flight attempt completes with a failure: the LATEST
            // caller gets the outcome, the superseded caller stays silent.
            XServer.finishAttempt(ctx, false, "simulated attempt failure", seq)
            instr.waitForIdleSync()
            assertEquals(listOf("simulated attempt failure"), errorsB.toList())
            assertTrue("superseded caller must not receive the outcome", errorsA.isEmpty())
            assertFalse("preparing cleared on completion", XServer.preparing)
        }
    }

    @Test
    fun successCompletionRoutesToLatestAttachedActivity() {
        val instr = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var act: MainActivity? = null
            var seq = 0L
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                act = a
                seq = XServer.beginAttemptForTest()
                XServer.openDesktop(a, Distros.UBUNTU, onStage = { }, onError = { })
            }
            XServer.finishAttempt(ctx, true, null, seq)
            instr.waitForIdleSync()
            assertTrue("X view opened on the latest attached activity",
                XServer.lastOpenedXForTest === act)
            assertFalse("preparing cleared on completion", XServer.preparing)
        }
    }

    @Test
    fun stopInvalidatesInFlightAttemptCompletion() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val errors = CopyOnWriteArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var seq = 0L
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                seq = XServer.beginAttemptForTest()
                XServer.openDesktop(a, Distros.UBUNTU,
                    onStage = { }, onError = { errors.add(it) })
            }
            // User Stop while the attempt is in flight (empty world here:
            // teardown is fast but runs the full invalidation path).
            val t = Thread { XServer.stopDesktopMode(ctx) }
            t.start(); t.join(30_000)
            assertFalse("Stop clears preparing - a post-Stop open starts fresh",
                XServer.preparing)
            // The stopped world's completion arrives late: dropped entirely.
            XServer.finishAttempt(ctx, true, null, seq)
            instr.waitForIdleSync()
            assertNull("no X view reopen over a stopped session",
                XServer.lastOpenedXForTest)
            assertTrue("no outcome delivered for a stopped attempt", errors.isEmpty())
            assertFalse("preparing stays cleared", XServer.preparing)
        }
    }

    @Test
    fun staleCompletionCannotClearNewerAttempt() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val errors = CopyOnWriteArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var seqOld = 0L
            var seqNew = 0L
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                seqOld = XServer.beginAttemptForTest()
                XServer.openDesktop(a, Distros.UBUNTU,
                    onStage = { }, onError = { errors.add(it) })
            }
            val t = Thread { XServer.stopDesktopMode(ctx) }
            t.start(); t.join(30_000)
            // A fresh attempt starts after the Stop.
            scenario.onActivity { seqNew = XServer.beginAttemptForTest() }
            // The OLD worker's completion lands now: must not clear the
            // newer attempt's preparing or deliver anything.
            XServer.finishAttempt(ctx, true, null, seqOld)
            instr.waitForIdleSync()
            assertTrue("newer attempt keeps its in-flight flag", XServer.preparing)
            assertNull("old worker must not open the X view", XServer.lastOpenedXForTest)
            // The newer attempt completes normally.
            XServer.finishAttempt(ctx, false, "new attempt failure", seqNew)
            instr.waitForIdleSync()
            assertFalse("newer attempt completion clears preparing", XServer.preparing)
            assertEquals(listOf("new attempt failure"), errors.toList())
        }
    }

    @Test
    fun hideDetachesRoutingAndCompletionStaysSilent() {
        val instr = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var seq = 0L
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                seq = XServer.beginAttemptForTest()
                XServer.openDesktop(a, Distros.UBUNTU, onStage = { }, onError = { })
                // Overlay "Hide - keep starting": detach, no auto-open.
                XServer.detachAttemptRouting()
            }
            XServer.finishAttempt(ctx, true, null, seq)
            instr.waitForIdleSync()
            assertNull("Hide: completion must not steal the foreground",
                XServer.lastOpenedXForTest)
            assertFalse("preparing cleared on completion", XServer.preparing)
        }
    }

    @Test
    fun stopBetweenClaimAndOpenRevokesTheOpen() {
        val instr = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var seq = 0L
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                seq = XServer.beginAttemptForTest()
                XServer.openDesktop(a, Distros.UBUNTU, onStage = { }, onError = { })
                // Drive a user Stop INTO the claim->open window: the claim
                // passes (attempt still valid there), the Stop lands, and
                // the revoke re-check at the open point must catch it.
                XServer.beforeOpenHookForTest = { XServer.stopDesktopMode(ctx) }
            }
            XServer.finishAttempt(ctx, true, null, seq)
            instr.waitForIdleSync()
            assertNull("Stop between claim and open: no X view may open",
                XServer.lastOpenedXForTest)
            assertFalse("preparing cleared by the Stop", XServer.preparing)
        }
    }

    @Test
    fun hideBetweenClaimAndOpenRevokesTheOpen() {
        val instr = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var seq = 0L
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                seq = XServer.beginAttemptForTest()
                XServer.openDesktop(a, Distros.UBUNTU, onStage = { }, onError = { })
                // Hide lands after the claim but before the actual open.
                XServer.beforeOpenHookForTest = { XServer.detachAttemptRouting() }
            }
            XServer.finishAttempt(ctx, true, null, seq)
            instr.waitForIdleSync()
            assertNull("Hide between claim and open: no foreground steal",
                XServer.lastOpenedXForTest)
            assertFalse("preparing cleared on completion", XServer.preparing)
        }
    }

    @Test
    fun preparingOverlayShowsWorkingEscapeControls() {
        val instr = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                a.vm.desktopStage = "a desktop start is already in progress - following it now"
                a.vm.desktopPrep = true
            }
            instr.waitForIdleSync()
            val stop = device.wait(Until.findObject(By.desc("preparing-stop-session")), 10_000)
            val hide = device.wait(Until.findObject(By.desc("preparing-hide-wait")), 10_000)
            assertNotNull("Stop session control on the preparing overlay", stop)
            assertNotNull("Hide control on the preparing overlay", hide)
            Shot.save("reentry-preparing-overlay")

            // Hide: overlay gone, the (simulated) start untouched.
            hide!!.click()
            assertNotNull("hide returns to the first-run screen",
                device.wait(Until.hasObject(By.desc("show-picker")), 10_000))
            assertNull("overlay dismissed after hide",
                device.wait(Until.hasObject(By.desc("preparing-stop-session")), 2_000))

            // Stop: ends the (empty) session world and clears the overlay.
            scenario.onActivity { a -> a.vm.desktopPrep = true }
            instr.waitForIdleSync()
            device.wait(Until.findObject(By.desc("preparing-stop-session")), 10_000)!!.click()
            assertNotNull("stop returns to the first-run screen",
                device.wait(Until.hasObject(By.desc("show-picker")), 30_000))
            scenario.onActivity { a -> assertFalse("overlay cleared after stop", a.vm.desktopPrep) }
            Shot.save("reentry-after-stop")
        }
    }
}
