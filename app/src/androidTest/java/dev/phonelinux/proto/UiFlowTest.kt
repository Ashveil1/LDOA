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
        val installBtn = device.wait(Until.findObject(By.desc("install-distro")), 5_000)
        if (installBtn != null) {
            installBtn.click()
            val done = device.wait(Until.hasObject(By.desc("open-terminal")), 240_000)
            assertNotNull("install completed (open-terminal button)", done)
        }
        shot(device, "02-installed")

        // 3. open terminal (take the object returned by wait: no hasObject/findObject race)
        // Install load can trigger a Pixel Launcher ANR dialog on the
        // emulator; clear any stacked system dialogs before re-finding.
        Shot.dismissSystemDialogs(device)
        // After install the card recomposes several times while state settles;
        // a short findObject window can lose the button mid-recomposition.
        val openBtn = device.wait(Until.findObject(By.desc("open-terminal")), 120_000)
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
        val outDeadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < outDeadline) {
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
            throw AssertionError("guest output visible in terminal FAILED; nodes:\n" + dump)
        }
        assertNotNull("guest output visible in terminal", out)

        // 5. orientation: landscape must keep the terminal usable
        device.setOrientationLandscape()
        device.waitForIdle(3_000)
        assertTrue("terminal still visible in landscape",
            device.hasObject(By.desc("terminal-input")))
        shot(device, "05-landscape")
        device.setOrientationNatural()
        device.waitForIdle(2_000)
    }
}
