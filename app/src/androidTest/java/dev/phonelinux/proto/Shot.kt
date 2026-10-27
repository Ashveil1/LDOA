package dev.phonelinux.proto

import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

/** In-process screen capture, exported through two log channels because AGP
 *  uninstalls the app right after connectedAndroidTest (wiping internal
 *  dirs before CI can pull them) and run-as from UiAutomation's shell is
 *  unreliable:
 *   1. logcat tag PHONESHOT (system log buffer survives uninstall; CI dumps
 *      it with adb logcat -d), primary channel
 *   2. instrumentation sendStatus stream, backup channel
 *  CI reassembles either with .github/scripts/extract_shots.py. */
object Shot {
    private const val CHUNK = 3000

    /** Dismiss one-time SYSTEM dialogs so screenshots and clicks reach the
     *  app. Allowlist only: an ANR dialog is answered ("Wait", never "Close
     *  app") ONLY when its title names a package/label we have OBSERVED
     *  ANR-ing as emulator noise - currently just the Pixel Launcher, seen
     *  in logcat as "Application Not Responding:
     *  com.google.android.apps.nexuslauncher" while our app was foreground.
     *  Any other ANR (our own app, third party, anything unverified) is left
     *  on screen so the test fails on it instead of masking a real hang.
     *  Crash dialogs have no "Wait" button and are never touched. The
     *  POST_NOTIFICATIONS prompt ("Allow") is the app's own startup request;
     *  answering it is test setup. Every dismissal is logged to logcat
     *  (PHONEDIAG) BEFORE the click, so evidence survives in the CI shotlog.
     *  Loops since dialogs can stack. */
    private val OBSERVED_SYSTEM_ANRS = listOf(
        "com.google.android.apps.nexuslauncher", "Pixel Launcher")
    fun dismissSystemDialogs(device: androidx.test.uiautomator.UiDevice) {
        repeat(6) {
            var clicked = false
            val anrTitle = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textContains("Not Responding")), 800)
                ?: device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textContains("isn't responding")), 400)
            if (anrTitle != null) {
                val title = anrTitle.text ?: ""
                if (OBSERVED_SYSTEM_ANRS.any { title.contains(it) }) {
                    android.util.Log.i("PHONEDIAG", "dismissing allowlisted system ANR dialog: " + title)
                    device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Wait")), 400)?.let { it.click(); clicked = true }
                } else {
                    android.util.Log.i("PHONEDIAG", "ANR dialog NOT auto-dismissed (not on observed-system allowlist): " + title)
                    return // unknown or own-app ANR stays visible; test fails on it
                }
            }
            val allow = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Allow")), 400)
            if (allow != null) {
                android.util.Log.i("PHONEDIAG", "answering app permission prompt: Allow")
                allow.click(); clicked = true
            }
            if (!clicked) return
            device.waitForIdle(800)
        }
    }

    fun save(name: String) {
        val instr = InstrumentationRegistry.getInstrumentation()
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot()
            ?: throw AssertionError("UiAutomation.takeScreenshot returned null for $name")
        if (bmp.width < 100 || bmp.height < 100) {
            throw AssertionError("screenshot $name implausibly small: ${bmp.width}x${bmp.height}")
        }
        val w = 720
        val h = bmp.height * w / bmp.width
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        val bytes = baos.toByteArray()
        if (bytes.isEmpty()) throw AssertionError("screenshot $name encoded empty")

        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val total = (b64.length + CHUNK - 1) / CHUNK
        var seq = 0
        while (seq < total) {
            val end = minOf((seq + 1) * CHUNK, b64.length)
            val chunk = b64.substring(seq * CHUNK, end)
            android.util.Log.i("PHONESHOT", name + "|" + String.format("%04d|%04d|", seq, total) + chunk)
            val bundle = Bundle()
            bundle.putString("shot_b64_" + name + "_" + String.format("%04d", seq), chunk)
            instr.sendStatus(2, bundle)
            seq++
        }
        val done = Bundle()
        done.putString("shot_b64_" + name + "_count", total.toString())
        instr.sendStatus(2, done)
        android.util.Log.i("PHONESHOT", name + " exported: " + bytes.size + " bytes, " + total + " chunks")
    }
}
