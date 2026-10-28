package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * User-facing Ubuntu terminal path on the packaged app: picker -> Install
 * (Ubuntu card) -> Open terminal -> type a command -> output rendered in the
 * app. Proves the interactive path, not only proot-level guest exec.
 */
@RunWith(AndroidJUnit4::class)
class UbuntuTerminalUiTest {

    @Test
    fun ubuntuTerminalInstallTypeOutput() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Shot.dismissSystemDialogs(device)
        device.pressHome()
        // Deterministic start: a prior test may have left the app on any
        // screen; force-stop so the picker is guaranteed.
        device.executeShellCommand("am force-stop dev.phonelinux.proto")
        device.executeShellCommand(
            "am start -n dev.phonelinux.proto/.MainActivity -a android.intent.action.MAIN")

        // First-launch notification prompt (see UiFlowTest).
        val allow = device.wait(Until.findObject(By.text("Allow")), 15_000)
        allow?.click()
        device.waitForIdle(2_000)
        Shot.dismissSystemDialogs(device)

        // Ubuntu may already be installed (UbuntuBaseSpikeTest installs it in
        // the same run): then the card shows open-terminal-ubuntu, no Install.
        var openBtn = device.wait(Until.findObject(By.desc("open-terminal-ubuntu")), 5_000)
        if (openBtn == null) {
            val installBtn = device.wait(Until.findObject(By.desc("install-ubuntu")), 20_000)
            assertNotNull("install-ubuntu button (picker must show the Ubuntu card)", installBtn)
            shot(device, "20-ubuntu-picker")
            installBtn.click()
            openBtn = device.wait(Until.findObject(By.desc("open-terminal-ubuntu")), 120_000)
        }
        assertNotNull("open-terminal-ubuntu clickable", openBtn)
        shot(device, "21-ubuntu-installed")
        openBtn.click()
        assertNotNull("terminal input", device.wait(Until.findObject(By.desc("terminal-input")), 20_000))
        shot(device, "22-ubuntu-terminal")

        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        assertNotNull("terminal editable field", field)
        field.click()
        device.waitForIdle(1_000)
        field.setText("head -2 /etc/os-release && echo UBUNTU-TERM-OK")
        val typeDeadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < typeDeadline) {
            val t = field.text
            if (t != null && t.contains("UBUNTU-TERM-OK")) break
            Thread.sleep(300)
        }
        assertTrue("command text never landed in field", field.text?.contains("UBUNTU-TERM-OK") == true)
        val sendBtn = device.wait(Until.findObject(By.desc("terminal-send")), 10_000)
        assertNotNull("send button", sendBtn)
        assertTrue("send button enabled", sendBtn.isEnabled)
        sendBtn.click()

        // Assert on the OUTPUT node (its own contentDescription) so the input
        // field text can never satisfy the check vacuously.
        val outDeadline = System.currentTimeMillis() + 90_000
        var outText: String? = null
        while (System.currentTimeMillis() < outDeadline) {
            Shot.dismissSystemDialogs(device)
            val n = device.findObject(By.desc("terminal-output"))
            val t = n?.text
            if (t != null && t.contains("UBUNTU-TERM-OK")) { outText = t; break }
            Thread.sleep(1_000)
        }
        shot(device, "23-ubuntu-terminal-output")
        assertNotNull("UBUNTU-TERM-OK marker never reached terminal-output (last=$outText)", outText)
        assertTrue("os-release does not identify Ubuntu: $outText", outText!!.contains("Ubuntu"))
    }

    private fun shot(device: UiDevice, name: String) = Shot.save(name)
}
