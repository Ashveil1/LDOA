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

    /** Registered once per instrumentation: fires whenever UiAutomator's
     *  own finds stall, so node-based paths are covered even between
     *  explicit dismissSystemDialogs calls. Same allowlist + logging as
     *  dismissSystemDialogs: ONLY the observed Pixel-Launcher storm is
     *  auto-answered ("Wait"); anything else stays on screen. */
    private var watcherRegistered = false
    fun registerAnrWatcher(device: androidx.test.uiautomator.UiDevice) {
        if (watcherRegistered) return
        watcherRegistered = true
        device.registerWatcher("phonelinux-anr") {
            val t = device.findObject(androidx.test.uiautomator.By.textContains("isn't responding"))
                ?: device.findObject(androidx.test.uiautomator.By.textContains("Not Responding"))
            if (t != null && OBSERVED_SYSTEM_ANRS.any { (t.text ?: "").contains(it) }) {
                android.util.Log.i("PHONEDIAG", "watcher dismissing allowlisted ANR: " + t.text)
                device.findObject(androidx.test.uiautomator.By.text("Wait"))?.click()
                true
            } else false
        }
    }
    /** NARROW verified launcher-ANR dismissal for the P3 identity path.
     *  Identity is established THREE ways in ONE fresh resolution chain;
     *  substring text alone is NEVER enough:
     *   1. the title view belongs to package "android" (the system ANR
     *      dialog is served by the system package - same text from any
     *      other package is not it) AND names an allowlisted observed
     *      system ANR package;
     *   2. ascending from THAT title, staying inside package "android",
     *      reaches the exact alert-dialog container android:id/parentPanel
     *      (the walk stops AT it, never past it - whole-window/root scope
     *      is impossible);
     *   3. the "Wait" button is found INSIDE that container.
     *  The dismissal click uses a SECOND, immediately-preceding fresh
     *  resolution of the SAME full chain (fresh title + fresh container +
     *  fresh in-container Wait), so the clicked object is never older
     *  than its own verification. No watcher registration (direct finds
     *  only - nothing persists past the call), no "Allow" prompts, no
     *  "Close app". If the exact dialog cannot be established the answer
     *  is REFUSAL - nothing clicked, pre-dismissal evidence retained.
     *  Every dismissal and every refusal is PHONEDIAG-logged. */
    private fun resolveVerifiedAnrWait(device: androidx.test.uiautomator.UiDevice):
            androidx.test.uiautomator.UiObject2? {
        val title = try {
            device.wait(androidx.test.uiautomator.Until.findObject(
                androidx.test.uiautomator.By.textContains("Not Responding")), 300)
                ?: device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.textContains("isn't responding")), 200)
        } catch (e: androidx.test.uiautomator.StaleObjectException) { null } ?: return null
        val titlePkg = try { title.applicationPackage } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
        val titleText = try { title.text ?: "" } catch (e: androidx.test.uiautomator.StaleObjectException) { "" }
        if (titlePkg != "android" || !OBSERVED_SYSTEM_ANRS.any { titleText.contains(it) }) {
            android.util.Log.i("PHONEDIAG", "narrow-dismiss: REFUSAL - not a verified android-system" +
                " allowlisted ANR (pkg=" + titlePkg + " text=" + titleText + ")")
            return null
        }
        var container: androidx.test.uiautomator.UiObject2? = null
        var node: androidx.test.uiautomator.UiObject2? = title
        var hops = 0
        while (node != null && hops < 6) {
            val rn = try { node.resourceName } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
            if (rn != null && rn == "android:id/parentPanel") { container = node; break }
            val parent = try { node.parent } catch (e: androidx.test.uiautomator.StaleObjectException) { null } ?: break
            val pp = try { parent.applicationPackage } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
            if (pp != "android") break
            node = parent
            hops++
        }
        if (container == null) {
            android.util.Log.i("PHONEDIAG", "narrow-dismiss: REFUSAL - exact container" +
                " android:id/parentPanel not reached within " + hops + " in-package hops for: " + titleText)
            return null
        }
        val waitBtn = try { container.findObject(androidx.test.uiautomator.By.text("Wait")) }
                      catch (e: androidx.test.uiautomator.StaleObjectException) { null }
        if (waitBtn == null) {
            android.util.Log.i("PHONEDIAG", "narrow-dismiss: REFUSAL - verified container has NO" +
                " Wait button for: " + titleText)
            return null
        }
        return waitBtn
    }

    fun dismissVerifiedLauncherAnr(device: androidx.test.uiautomator.UiDevice): Int {
        var dismissed = 0
        repeat(4) {
            // Detection pass: full chain, fresh (result discarded - the
            // pre-click pass below is the only one whose object is used).
            resolveVerifiedAnrWait(device) ?: return dismissed
            // Pre-click pass: ANOTHER full fresh chain; the clicked Wait
            // is THIS resolution's object - never older than its proof.
            val freshWait = resolveVerifiedAnrWait(device)
            if (freshWait == null) {
                android.util.Log.i("PHONEDIAG", "narrow-dismiss: REFUSAL - dialog failed" +
                    " re-resolution immediately before click - nothing clicked, evidence retained")
                return dismissed
            }
            android.util.Log.i("PHONEDIAG", "narrow-dismiss: dismissing VERIFIED launcher ANR dialog" +
                " (android pkg + exact android:id/parentPanel container + fresh in-container Wait)")
            try { freshWait.click(); dismissed++ }
            catch (e: androidx.test.uiautomator.StaleObjectException) {
                android.util.Log.i("PHONEDIAG", "narrow-dismiss: Wait went stale before click - re-finding")
                return@repeat
            }
            device.waitForIdle(600)
        }
        return dismissed
    }

    fun dismissSystemDialogs(device: androidx.test.uiautomator.UiDevice) {
        registerAnrWatcher(device)
        repeat(6) {
            var clicked = false
            // Staleness hygiene: ANR-storm dialogs re-render constantly, so
            // a found UiObject2 can go stale between find and read/click
            // (run 41010399546: DesktopClientTest.kt:208 and
            // FailClosedDesktopTest.kt:55 both ended here). Skip to the next
            // repeat and re-find FRESH objects; the dismiss logic is
            // unchanged, only the object lifetime is respected.
            try {
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
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                android.util.Log.i("PHONEDIAG", "dismissSystemDialogs: dialog object went stale (re-render) - re-finding fresh objects")
                Thread.sleep(500)
                return@repeat
            }
            if (!clicked) { device.runWatchers(); return }
            device.waitForIdle(800)
        }
        device.runWatchers()
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
        // The sendStatus backup channel rides UTP's per-test result event;
        // a test with many shots exceeded gRPC's 4MB message cap and failed
        // the whole task AFTER 9/9 tests passed (run 40678896241). Logcat is
        // the proven channel (every artifact set complete), so sendStatus is
        // opt-in via -e shot_send_status true for non-UTP lanes.
        val sendStatus = "true" == InstrumentationRegistry.getArguments().getString("shot_send_status")
        var seq = 0
        while (seq < total) {
            val end = minOf((seq + 1) * CHUNK, b64.length)
            val chunk = b64.substring(seq * CHUNK, end)
            android.util.Log.i("PHONESHOT", name + "|" + String.format("%04d|%04d|", seq, total) + chunk)
            if (sendStatus) {
                val bundle = Bundle()
                bundle.putString("shot_b64_" + name + "_" + String.format("%04d", seq), chunk)
                instr.sendStatus(2, bundle)
            }
            seq++
        }
        if (sendStatus) {
            val done = Bundle()
            done.putString("shot_b64_" + name + "_count", total.toString())
            instr.sendStatus(2, done)
        }
        android.util.Log.i("PHONESHOT", name + " exported: " + bytes.size + " bytes, " + total + " chunks")
    }
}
