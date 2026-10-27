package dev.phonelinux.proto

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** UI flow: launch app, distro card + install button visible and tappable. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @Test
    fun pickerAndInstallVisible() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(ctx.packageName)), 10_000)
        assertNotNull("distro name visible", device.wait(Until.hasObject(By.text("Alpine Linux")), 10_000))
        assertNotNull("install button visible", device.wait(Until.hasObject(By.textStartsWith("Install")), 10_000))
    }
}
