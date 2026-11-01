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
 * second caller RE-ATTACHES to the in-flight attempt (latest callbacks and
 * activity win, still exactly one attempt, no duplicate session), and the
 * overlay carries Stop session / Hide controls.
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
        // Never leak a simulated in-flight flag into later phases.
        if (XServer.preparing) XServer.finishAttempt(ctx, false, "test cleanup")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        XServer.resetOwnershipStateForTest()
    }

    @Test
    fun secondOpenWhilePreparingReattachesAndReceivesOutcome() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val stagesA = CopyOnWriteArrayList<String>()
        val errorsA = CopyOnWriteArrayList<String>()
        val stagesB = CopyOnWriteArrayList<String>()
        val errorsB = CopyOnWriteArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                Shot.dismissSystemDialogs(device)
                // Simulate an in-flight attempt owned by a previous screen:
                // the early path must re-attach, not refuse.
                XServer.setPreparingForTest(true)
                XServer.openDesktop(a, Distros.UBUNTU,
                    onStage = { stagesA.add(it) }, onError = { errorsA.add(it) })
                // A still newer screen re-attaches again: latest caller wins.
                XServer.openDesktop(a, Distros.UBUNTU,
                    onStage = { stagesB.add(it) }, onError = { errorsB.add(it) })
            }
            assertTrue("re-attach reported on stage line",
                stagesB.any { it.contains("already in progress") && it.contains("following") })
            assertTrue("attempt still marked in flight after re-attach", XServer.preparing)
            // The in-flight attempt completes with a failure: the LATEST
            // caller gets the outcome, the superseded caller stays silent.
            XServer.finishAttempt(ctx, false, "simulated attempt failure")
            instr.waitForIdleSync()
            assertEquals(listOf("simulated attempt failure"), errorsB.toList())
            assertTrue("superseded caller must not receive the outcome", errorsA.isEmpty())
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
