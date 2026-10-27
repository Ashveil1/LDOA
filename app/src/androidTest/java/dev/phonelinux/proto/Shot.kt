package dev.phonelinux.proto

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/** In-process screen capture: androidTest runs inside the app process, so the
 *  app-internal files dir is writable; CI pulls it via adb root. */
object Shot {
    fun save(name: String) {
        val instr = InstrumentationRegistry.getInstrumentation()
        val appCtx = instr.targetContext
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot()
            ?: throw AssertionError("UiAutomation.takeScreenshot returned null for $name")
        val dir = File(appCtx.filesDir, "shots").apply { mkdirs() }
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("PHONELINUX_SHOT", "$name -> ${f.absolutePath} size=${f.length()}")
        if (!f.isFile || f.length() == 0L) throw AssertionError("screenshot $name not written")
    }
}
