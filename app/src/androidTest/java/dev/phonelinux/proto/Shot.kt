package dev.phonelinux.proto

import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

/** In-process screen capture, exported through the instrumentation status
 *  stream. AGP uninstalls the app+test APKs right after connectedAndroidTest
 *  (wiping internal dirs before CI can pull them) and run-as from
 *  UiAutomation's shell proved unreliable, so the screenshot (scaled JPEG)
 *  is chunked into INSTRUMENTATION_STATUS lines that land in the gradle
 *  log; CI reassembles them with .github/scripts/extract_shots.py. */
object Shot {
    private const val CHUNK = 6000

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
        var seq = 0
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + CHUNK, b64.length)
            val bundle = Bundle()
            bundle.putString("shot_b64_" + name + "_" + String.format("%04d", seq), b64.substring(i, end))
            instr.sendStatus(2, bundle)
            i = end
            seq++
        }
        val done = Bundle()
        done.putString("shot_b64_" + name + "_count", seq.toString())
        instr.sendStatus(2, done)
        android.util.Log.i("PHONELINUX_SHOT", "$name exported via status stream: ${bytes.size} bytes, $seq chunks")
    }
}
