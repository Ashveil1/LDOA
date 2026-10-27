package dev.phonelinux.proto

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.FileOutputStream

/** In-process screen capture. AGP uninstalls the app+test APKs right after
 *  connectedAndroidTest, wiping internal dirs before CI can pull, so each
 *  shot is also exported to /data/local/tmp (shell space) through run-as:
 *  read as the app uid, write as shell. */
object Shot {
    private const val EXPORT_DIR = "/data/local/tmp/phonelinux-shots"

    fun save(name: String) {
        val instr = InstrumentationRegistry.getInstrumentation()
        val appCtx = instr.targetContext
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot()
            ?: throw AssertionError("UiAutomation.takeScreenshot returned null for $name")
        val dir = File(appCtx.filesDir, "shots").apply { mkdirs() }
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!f.isFile || f.length() == 0L) throw AssertionError("screenshot $name not written")

        val device = UiDevice.getInstance(instr)
        device.executeShellCommand("mkdir -p $EXPORT_DIR")
        val out = "$EXPORT_DIR/$name.png"
        device.executeShellCommand("run-as ${appCtx.packageName} cat files/shots/$name.png > $out")
        val probe = device.executeShellCommand("test -s $out && echo SHOT_OK").trim()
        if (probe != "SHOT_OK") throw AssertionError("screenshot $name not exported (probe='$probe')")
        android.util.Log.i("PHONELINUX_SHOT", "$name -> $out size=${f.length()}")
    }
}
