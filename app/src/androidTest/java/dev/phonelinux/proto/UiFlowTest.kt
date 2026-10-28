package dev.phonelinux.proto

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Full journey with real evidence: picker -> install -> open terminal ->
 * run a command in the guest -> output visible -> landscape. Screenshots at
 * each milestone are pulled by CI and uploaded as artifacts.
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    companion object {
        /** Assertion messages land in result XML; control bytes (ESC etc from
         *  shell/proot output) are invalid XML chars - strip them. */
        fun sanitize(s: String): String =
            s.map { c -> if (c.code in 32..126 || c == '\n') c else '?' }.joinToString("")
    }

    private fun shot(device: UiDevice, name: String) = Shot.save(name)

    @Test
    fun installOpenTerminalRunCommand() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instr)
        val ctx = instr.targetContext

        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(ctx.packageName)), 15_000)
        // The app requests POST_NOTIFICATIONS at startup; answer once here so
        // the dialog never covers later screenshots.
        Shot.dismissSystemDialogs(device)

        // 1. picker
        assertNotNull("distro card", device.wait(Until.hasObject(By.text("Alpine Linux")), 10_000))
        shot(device, "01-picker")

        // 2. install if needed (button desc flips to open-terminal when done)
        val installBtn = device.wait(Until.findObject(By.desc("install-alpine")), 5_000)
        if (installBtn != null) {
            installBtn.click()
            val done = device.wait(Until.hasObject(By.desc("open-terminal-alpine")), 240_000)
            assertNotNull("install completed (open-terminal button)", done)
        }
        shot(device, "02-installed")

        // 3. open terminal (take the object returned by wait: no hasObject/findObject race)
        // Install load can trigger a Pixel Launcher ANR dialog on the
        // emulator; clear any stacked system dialogs before re-finding.
        Shot.dismissSystemDialogs(device)
        // After install the card recomposes several times while state settles;
        // a short findObject window can lose the button mid-recomposition.
        val openBtn = device.wait(Until.findObject(By.desc("open-terminal-alpine")), 120_000)
        assertNotNull("open-terminal clickable", openBtn)
        openBtn.click()
        val termInput = device.wait(Until.findObject(By.desc("terminal-input")), 20_000)
        assertNotNull("terminal input", termInput)
        device.wait(Until.hasObject(By.text("live")), 20_000)
        shot(device, "03-terminal")

        // 4. run a real guest command through the UI.
        // The editable node is the Compose TextField inner node, exposed as
        // className android.widget.EditText; setText on the desc-marked
        // container fails silently (field stays empty, Run stays disabled).
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        assertNotNull("terminal editable field", field)
        field.click()
        device.waitForIdle(1_000)
        field.setText("uname -a && echo TERM-OK")
        val typeDeadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < typeDeadline) {
            val t = field.text
            if (t != null && t.contains("TERM-OK")) break
            Thread.sleep(300)
        }
        val typed = field.text
        assertTrue("command text never landed in field (text=$typed)",
            typed != null && typed.contains("TERM-OK"))
        val sendBtn = device.wait(Until.findObject(By.desc("terminal-send")), 10_000)
        assertNotNull("send button", sendBtn)
        assertTrue("send button enabled after typing", sendBtn.isEnabled)
        sendBtn.click()
        // Assert on the OUTPUT node only: a plain textContains would also
        // match the input field itself and pass vacuously.
        // Locate the output node by its known session-start content: the
        // sh -i job-control notice is unique to it, so this cannot match the
        // input field. (Two textContains on one BySelector are illegal, so
        // poll the node manually.)
        Thread.sleep(8_000)
        shot(device, "04-terminal-output") // pixel evidence regardless of semantics
        var out: androidx.test.uiautomator.UiObject2? = null
        // Guest output latency under proot on the emulator measured 8-40s;
        // a short window flakes between "not arrived" and "rendered".
        val outDeadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < outDeadline) {
            // Stacked system dialogs (launcher ANR etc) hide the whole app
            // tree: foreground pkg flips to "android" and every find fails.
            Shot.dismissSystemDialogs(device)
            val n = device.findObject(By.textContains("job control"))
            if (n != null && n.text != null && n.text.contains("TERM-OK")) { out = n; break }
            Thread.sleep(1_000)
        }
        if (out == null) {
            val sessLog = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 800 files/terminal-session.log 2>&1") } catch (t: Throwable) { "(unreadable)" }
            val dump = "session log: " + sessLog + "\n" + device.findObjects(By.pkg(ctx.packageName))
                .filter { it.text != null || it.contentDescription != null }
                .joinToString("\n") { n -> "desc=${n.contentDescription} text=${n.text?.takeLast(100)}" }
                .takeLast(1800)
            val fg = try { device.currentPackageName } catch (t: Throwable) { "(unknown)" }
            val allNodes = try { device.findObjects(By.pkg(fg)).size } catch (t: Throwable) { -1 }
            throw AssertionError("guest output visible in terminal FAILED" +
                "\nforeground pkg: " + fg + " (nodes in it: " + allNodes + ")" +
                "\napp-pkg nodes:\n" + sanitize(dump))
        }
        assertNotNull("guest output visible in terminal", out)
        // Ground truth at the assert moment: semantics text vs session log vs
        // node bounds (the IME-open frame renders wrong; diagnose precisely).
        val sessTail = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 500 files/terminal-session.log 2>&1") } catch (t: Throwable) { "(unreadable)" }
        android.util.Log.i("PHONEDIAG", "out.bounds=" + out.visibleBounds +
            " out.text=" + (out.text ?: "").takeLast(300).replace("\n", "|") +
            " sessionLogTail=" + sessTail.replace("\n", "|"))
        // Dismiss the IME for the clean rendered-proof frame; the IME-open
        // layout itself is proven separately by shot 04 and 04d. Guarded:
        // a system dialog can steal focus and close the IME first, and a
        // blind pressBack then hits BackHandler and leaves the terminal.
        dismissImeIfShown(device)
        device.waitForIdle(2_000)
        Thread.sleep(1_500)
        shot(device, "04b-terminal-output-rendered")

        // 4b. A second command must also run and render (repeat use).
        runCommand(device, "echo SECOND-OK", "SECOND-OK")

        // 4c. Long output: seq overflows the viewport, forcing real scroll.
        runCommand(device, "seq 1 60 && echo SEQ-END-MARKER", "SEQ-END-MARKER")
        Thread.sleep(1_000)
        shot(device, "04c-long-output-bottom") // auto-scroll pinned to bottom
        // Scrollback proof is PIXEL-level: the whole scrollback lives in one
        // semantics node, so only an inspected frame shows the viewport moved.
        val outBox = device.wait(Until.findObject(By.desc("terminal-output")), 10_000)
        assertNotNull("terminal-output node for scroll", outBox)
        val b = outBox.visibleBounds
        device.swipe(b.centerX(), b.top + b.height() / 4, b.centerX(), b.bottom - b.height() / 8, 20)
        device.waitForIdle(1_000)
        // Re-open the IME over the scrolled view: layout repair must hold
        // with long content and a moved viewport, not just a fresh screen.
        val fieldAgain = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        fieldAgain?.click()
        Thread.sleep(1_500)
        shot(device, "04d-long-output-scrolled-ime")
        dismissImeIfShown(device)
        device.waitForIdle(1_000)

        // 5. orientation: landscape must keep the terminal usable
        device.setOrientationLandscape()
        device.waitForIdle(3_000)
        // Rotation under emulator load can outlast waitForIdle: wait for the
        // node explicitly instead of asserting on an instant snapshot.
        assertTrue("terminal still visible in landscape",
            device.wait(Until.hasObject(By.desc("terminal-input")), 10_000))
        shot(device, "05-landscape")
        device.setOrientationNatural()
        device.waitForIdle(2_000)
    }

    /** pressBack only when the IME is genuinely on screen; otherwise back
     *  hits TerminalScreen's BackHandler and navigates away. */
    private fun dismissImeIfShown(device: UiDevice) {
        val shown = try {
            device.executeShellCommand("dumpsys input_method | grep -m1 mInputShown")
                .contains("mInputShown=true")
        } catch (t: Throwable) { false }
        if (shown) device.pressBack()
    }

    /** Type-and-run one command through the real UI, asserting the input
     *  behavior (text lands, field clears after send) and that its output
     *  renders in the terminal-output node. */
    private fun runCommand(device: UiDevice, cmd: String, needle: String) {
        var field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        if (field == null) {
            // Launcher ANR dialogs can STORM (observed every ~10-25s after
            // the desktop test): a single dismiss+wait loses whole windows.
            // Ride the storm out: dismiss, re-enter the terminal if we were
            // pushed back to the picker, retry the field.
            val fieldDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < fieldDeadline && field == null) {
                Shot.dismissSystemDialogs(device)
                val reopen = device.wait(Until.findObject(By.desc("open-terminal-alpine")), 2_000)
                if (reopen != null) {
                    android.util.Log.i("PHONEDIAG", "runCommand: terminal screen lost, re-opening for: " + cmd)
                    reopen.click()
                }
                field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            }
        }
        assertNotNull("terminal editable field for: " + cmd, field)
        field.click()
        device.waitForIdle(500)
        field.setText(cmd)
        val typeDeadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < typeDeadline) {
            val t = field.text
            if (t != null && t.contains(needle.take(6))) break
            Thread.sleep(300)
        }
        var send = device.wait(Until.findObject(By.desc("terminal-send")), 5_000)
        assertNotNull("send button for: " + cmd, send)
        val enableDeadline = System.currentTimeMillis() + 5_000
        var sendEnabled = false
        while (System.currentTimeMillis() < enableDeadline) {
            // Re-query on staleness: the Compose row recomposes when the
            // field text lands and the held node goes stale mid-poll
            // (runs 8302b1e/425ab98: StaleObjectException at this poll).
            try { if (send.isEnabled) { sendEnabled = true; break } }
            catch (e: androidx.test.uiautomator.StaleObjectException) { }
            Thread.sleep(300)
            send = device.findObject(By.desc("terminal-send")) ?: send
        }
        assertTrue("send button never enabled for: " + cmd, sendEnabled)
        try { send.click() } catch (e: androidx.test.uiautomator.StaleObjectException) {
            // A system dialog (launcher ANR) can hide the whole app tree:
            // the bare re-find then returns null and NPEs (run 1a81e3d).
            Shot.dismissSystemDialogs(device)
            val send2 = device.wait(Until.findObject(By.desc("terminal-send")), 10_000)
            assertTrue("send button never re-found after stale node", send2 != null)
            send2!!.click()
        }
        // Input behavior: Run must clear the field.
        val clearDeadline = System.currentTimeMillis() + 5_000
        var cleared = false
        while (System.currentTimeMillis() < clearDeadline) {
            if (field.text.isNullOrEmpty()) { cleared = true; break }
            Thread.sleep(300)
        }
        assertTrue("input field not cleared after Run", cleared)
        val deadline = System.currentTimeMillis() + 90_000
        var ok = false
        while (System.currentTimeMillis() < deadline) {
            Shot.dismissSystemDialogs(device)
            try {
                val n = device.findObject(By.desc("terminal-output"))
                if (n != null && n.text != null && n.text.contains(needle)) { ok = true; break }
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                // node vanished mid-read (ANR-storm churn, run 70d796c) - the
                // poll loop retries with a fresh node on the next pass.
            }
            Thread.sleep(1_000)
        }
        assertTrue("output for '" + cmd + "' never contained '" + needle + "'", ok)
    }
}
