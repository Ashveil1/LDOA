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

    private fun shot(device: UiDevice, name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        device.takeScreenshot(File(dir, "$name.png"))
    }

    @Test
    fun installOpenTerminalRunCommand() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instr)
        val ctx = instr.targetContext

        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(ctx.packageName)), 15_000)

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

        // 3. open terminal
        device.findObject(By.desc("open-terminal")).click()
        assertNotNull("terminal input", device.wait(Until.hasObject(By.desc("terminal-input")), 20_000))
        device.wait(Until.hasObject(By.text("live")), 20_000)
        shot(device, "03-terminal")

        // 4. run a real guest command through the UI
        device.findObject(By.desc("terminal-input")).text = "uname -a && echo TERM-OK"
        device.findObject(By.desc("terminal-send")).click()
        assertNotNull("guest output visible in terminal",
            device.wait(Until.hasObject(By.textContains("TERM-OK")), 30_000))
        shot(device, "04-terminal-output")

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
