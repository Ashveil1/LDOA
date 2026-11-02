package dev.phonelinux.proto

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 entry-seam emulator test (custom-mobile lane entry lifecycle):
 * picker entry -> MOBILE start (consent words are the MOBILE ones) ->
 * repeat open REUSES the same session (no second process) -> a GNOME
 * open while mobile is active is REFUSED (cross-mode guard) -> Stop does
 * an identity-VERIFIED teardown and leaves NO recovery block.
 *
 * Preconditions: Ubuntu rootfs installed (the lane provisions it); the
 * mobile shell packages may provision in-guest on first start (bounded
 * wait below). Emulator-only evidence: actual screenshots (Shot) plus
 * identity facts from XServer's own ownership state. This test makes NO
 * smoothness/FPS/latency claim.
 */
@RunWith(AndroidJUnit4::class)
class MobileDesktopEntryTest {

    private lateinit var device: UiDevice
    private lateinit var ctx: Context

    companion object {
        private const val TAG = "MobileDesktopEntryTest"
        /** apt provisioning of the mobile set plus start, generous bound. */
        private const val START_BUDGET_MS = 15 * 60_000L
        private const val UI_WAIT_MS = 10_000L
    }

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instr)
        ctx = instr.targetContext
        XServer.resetOwnershipStateForTest()
        ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE).edit()
            .putBoolean("ubuntu_desktop_active", false)
            .putBoolean("mobile_desktop_active", false)
            .remove("mobile_recovery_blocked")
            .remove("mobile_recovery_boot")
            .apply()
    }

    @After
    fun tearDown() {
        try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SessionService.stop(ctx)
        XServer.resetOwnershipStateForTest()
    }

    private fun openPicker() {
        val picker = device.wait(Until.findObject(By.desc("show-picker")), UI_WAIT_MS)
        assertNotNull("picker entry affordance (show-picker)", picker)
        picker.click()
        device.waitForIdle(1_000)
    }

    @Test
    fun mobileEntryLifecycle() {
        val prefs = ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { Shot.dismissSystemDialogs(device) }

            // 1. Picker entry: the mobile prototype affordance exists.
            openPicker()
            val mobileBtn = device.wait(Until.findObject(By.desc("open-mobile-desktop")), UI_WAIT_MS)
            assertNotNull("picker shows open-mobile-desktop (Ubuntu installed, preview flag on)", mobileBtn)
            Shot.save("m1-picker-mobile-entry")
            mobileBtn.click()
            device.waitForIdle(1_000)

            // First start may ask the mode-aware consent: it must carry the
            // MOBILE words, never the GNOME download claim.
            val consent = device.wait(Until.findObject(By.textContains("mobile desktop (prototype)")), 3_000)
            if (consent != null) {
                Shot.save("m1-mobile-consent")
                val confirm = device.wait(Until.findObject(By.text("Download and start")), UI_WAIT_MS)
                assertNotNull("mobile consent confirm", confirm)
                confirm.click()
            }

            // Bounded wait for a live tracked session + active marker.
            var proc: Process? = null
            val deadline = System.currentTimeMillis() + START_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                proc = XServer.sessionProcess
                if (proc != null && proc.isAlive && prefs.getBoolean("mobile_desktop_active", false)) break
                Thread.sleep(5_000)
            }
            assertTrue(
                "mobile session live + marker set within budget (evidence: mobile-desktop.log)",
                proc != null && proc!!.isAlive && prefs.getBoolean("mobile_desktop_active", false))
            Shot.save("m1-mobile-desktop-visible")

            // 2. Repeat open: REUSE the same session - never a second one.
            device.pressBack()
            device.waitForIdle(1_000)
            openPicker()
            val again = device.wait(Until.findObject(By.desc("open-mobile-desktop")), UI_WAIT_MS)
            assertNotNull("picker re-entry shows open-mobile-desktop", again)
            again.click()
            Thread.sleep(8_000) // reuse decision is immediate; settle the X view bring-forward
            assertTrue("repeat open REUSED the tracked session (same handle, still alive)",
                XServer.sessionProcess === proc && proc!!.isAlive)

            // 3. Cross-mode refusal: GNOME open while MOBILE is active.
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            scenario.onActivity { a ->
                XServer.openDesktop(a, Distros.UBUNTU,
                    onError = { m -> errors.add(m) },
                    mode = XServer.DesktopMode.GNOME)
            }
            var waited = 0L
            while (errors.isEmpty() && waited < 20_000) { Thread.sleep(500); waited += 500 }
            assertTrue("GNOME open refused while MOBILE active: $errors",
                errors.any { it.contains("already running") || it.contains("unknown state") })
            assertTrue("mobile session untouched by the refused GNOME open",
                XServer.sessionProcess === proc && proc!!.isAlive)

            // 4. Stop: identity-verified teardown, no recovery block.
            XServer.stopDesktopMode(ctx)
            assertFalse("mobile marker cleared by Stop", prefs.getBoolean("mobile_desktop_active", false))
            assertFalse("NO recovery block after VERIFIED owned cleanup",
                prefs.getBoolean("mobile_recovery_blocked", false))
            assertNull("ownership released after Stop", XServer.sessionProcess)
            Shot.save("m1-after-stop")
        }
    }
}
