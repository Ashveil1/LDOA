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

        // Copy-full-log regression (Ash 2026-10-30 10:35): seed a log LARGER
        // than the 1500-char dialog tail so the clipboard proof cannot be
        // satisfied by tail-sized content. Unique marker anchors the check.
        val logMarker = "LDOA-COPYLOG-MARKER-" + System.currentTimeMillis()
        val seedLog = java.io.File(ctx.filesDir, "guest-gnome.log")
        seedLog.writeText(logMarker + "\n" + ("seed line for copy-full-log regression coverage\n".repeat(60)))

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

            // NONTRIVIAL failure, not a short-circuit: the dialog body must
            // name the GNOME package-install failure (the apt stage that a
            // network cut actually breaks), and the RESUMED activity must
            // still be ours - the X11 activity provably never opened.
            val body = device.wait(Until.findObject(By.textContains("GNOME package install failed")), 10_000)
            assertTrue("dialog body is not the apt/network failure (short-circuit?)",
                body != null)
            Log.i(TAG, "failvis: dialog body = " + (body?.text ?: "").take(400))
            assertResumedIsPickerActivity(device)

            // COPY FULL LOG: the seeded file exceeds the 1500-char tail; the
            // clipboard must carry the WHOLE file incl. the seed marker.
            val copyBtn = device.wait(Until.findObject(By.text("Copy full log")), 10_000)
            assertTrue("Copy full log button missing from error dialog", copyBtn != null)
            copyBtn.click()
            Thread.sleep(1_000)
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            assertTrue("clipboard does not contain the seeded full log marker (len=" + clip.length + ")",
                clip.contains(logMarker))
            assertTrue("clipboard shorter than the 1500-char tail bound (len=" + clip.length + ") - tail copy suspected",
                clip.length > 1500)
            Log.i(TAG, "failvis: copy-full-log verified, " + clip.length + " chars incl marker")

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
            assertResumedIsPickerActivity(device)
            // STOP SESSION button (Ash 2026-10-30 10:35): must dismiss the
            // dialog and leave our activity resumed (no session retained on
            // this apt-failure path - teardown is a no-op but must not break
            // the flow).
            val stopBtn = device.wait(Until.findObject(By.text("Stop session")), 10_000)
            assertTrue("Stop session button missing from error dialog", stopBtn != null)
            stopBtn.click()
            val gone = device.wait(Until.gone(By.text("Ubuntu desktop did not start")), 10_000)
            assertTrue("error dialog still visible after Stop session", gone == true)
            assertResumedIsPickerActivity(device)
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
        }
        Log.i(TAG, "FAILCLOSED-VIS-OK fail-closed dialog + retry proven with pixels")
    }

    /** Foreground proof, not a code-path claim: the resumed activity must be
     *  dev.phonelinux.proto/.MainActivity; the vendored X11 activity
     *  (com.termux.x11.MainActivity) must NOT be on top. */
    private fun assertResumedIsPickerActivity(device: UiDevice) {
        val dump = device.executeShellCommand("dumpsys activity activities")
        val resumed = dump.lineSequence()
            .firstOrNull { it.contains("mResumedActivity") || it.contains("topResumedActivity") } ?: ""
        Log.i(TAG, "failvis: resumed = " + resumed.trim())
        assertTrue("X11 activity is in the foreground (fail-closed violated): " + resumed.trim(),
            resumed.contains("dev.phonelinux.proto/.MainActivity") && !resumed.contains("termux.x11"))
    }
}
