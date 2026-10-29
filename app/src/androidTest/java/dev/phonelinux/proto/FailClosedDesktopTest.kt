package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 fail-closed VISIBILITY proof (bounded, deterministic, fast - runs in
 * the emulator-test job, NOT the cold-chain job; user-facing failure
 * surfacing is approved core scope, 2026-10-29).
 *
 * Deterministic failure injection WITHOUT product hooks: cut the network
 * (svc wifi/data disable), then drive the REAL resume flow - consent ->
 * apt provisioning fails fast -> the fail-closed error dialog MUST appear
 * (and the X11 activity must NOT open; both live in the same if/else) ->
 * Try again -> consent -> fails visibly again. Pixels at each stage.
 * Ends with the dialog closed and the network restored in `finally` so
 * later classes in the shared emulator are untouched.
 */
@RunWith(AndroidJUnit4::class)
class FailClosedDesktopTest {
    private val TAG = "PHONELINUX_FAILCLOSED"

    @Test
    fun failurePathsAreVisibleAndRetryable(): Unit = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val device = UiDevice.getInstance(instr)
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)

        // Premise: Ubuntu installed, GNOME not provisioned (this job never
        // provisions GNOME). If Ubuntu is missing, run the REAL installer
        // once (product code, no fixture) so the resume variant shows.
        if (!installer.isInstalled(d)) {
            Log.i(TAG, "failvis: Ubuntu absent - running the real installer once")
            val ok = installer.install(d) { }
            assertTrue("failvis: premise install failed", ok)
        }
        assertTrue("failvis: premise broken - GNOME already provisioned here",
            !GuestUbuntu.hasGnome(installer.rootfsDir(d)))

        // A fresh MainActivity instance gets a fresh ViewModel, which reads
        // the install state and lands on the FIRST_RUN resume variant.
        device.executeShellCommand(
            "am start -n dev.phonelinux.proto/.MainActivity -a android.intent.action.MAIN")
        device.wait(Until.findObject(By.text("Allow")), 5_000)?.click()
        Shot.dismissSystemDialogs(device)
        val resume = device.wait(Until.findObject(By.desc("firstrun-resume-desktop")), 30_000)
        assertTrue("firstrun-resume-desktop not shown (resume variant expected)", resume != null)
        Shot.save("40-firstrun-resume")

        try {
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("svc data disable")

            resume.click()
            val consent = device.wait(Until.findObject(By.text("Download and start")), 30_000)
            assertTrue("first-start consent dialog did not appear", consent != null)
            Shot.save("41-failvis-consent")
            consent.click()

            // apt-get update with no network fails after its own timeouts;
            // the product retries once, then the fail-closed dialog.
            val err = device.wait(Until.findObject(By.text("Ubuntu desktop did not start")), 240_000)
            assertTrue("fail-closed error dialog did not appear (silent failure!)", err != null)
            Shot.save("42-failclosed-error-dialog")

            // Retry: Try again re-enters the flow; still no network, so it
            // must fail VISIBLY again rather than hanging or going black.
            val retry = device.wait(Until.findObject(By.text("Try again")), 10_000)
            assertTrue("Try again button missing", retry != null)
            retry.click()
            val consent2 = device.wait(Until.findObject(By.text("Download and start")), 30_000)
            assertTrue("consent did not reappear on retry", consent2 != null)
            consent2.click()
            val err2 = device.wait(Until.findObject(By.text("Ubuntu desktop did not start")), 240_000)
            assertTrue("fail-closed dialog did not reappear after retry", err2 != null)
            Shot.save("43-failclosed-retry-dialog")
            device.wait(Until.findObject(By.text("Close")), 10_000)?.click()
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
        }
        Log.i(TAG, "FAILCLOSED-VIS-OK fail-closed dialog + retry proven with pixels")
    }
}
