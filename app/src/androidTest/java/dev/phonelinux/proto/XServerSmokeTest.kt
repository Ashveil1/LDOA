package dev.phonelinux.proto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the new packaging path end to end on the emulator:
 *  1. app_process spawns com.termux.x11.CmdEntryPoint from our APK
 *  2. the spawned process dlopens libXlorie.so from inside the APK
 *     (extractNativeLibs=false) - failure would abort before listen
 *  3. the X server creates its listening socket at $TMPDIR/.X11-unix/X0
 *
 * The desktop activity is then launched and screenshotted for human/agent
 * inspection (artifact), not asserted.
 */
@RunWith(AndroidJUnit4::class)
class XServerSmokeTest {

    @Test
    fun xServerSpawnsAndListens() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        XServer.start(ctx)

        val sock = File(XServer.socketDir(ctx), "X0")
        val deadline = System.currentTimeMillis() + 60_000
        var ok = false
        while (System.currentTimeMillis() < deadline) {
            if (sock.exists()) { ok = true; break }
            Thread.sleep(500)
        }
        val logFile = File(ctx.filesDir, "xserver.log")
        val log = if (logFile.exists()) logFile.readText().takeLast(4000) else "(no log)"
        assertTrue("X socket never appeared at $sock. Server log tail:\n$log", ok)

        // Best-effort: open the desktop activity and screenshot what renders.
        try {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            ctx.startActivity(
                android.content.Intent(ctx, com.termux.x11.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Thread.sleep(8_000)
            val shots = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
            val out = File(shots, "06-desktop-activity.png")
            device.takeScreenshot(out)
            android.util.Log.i("PhoneLinuxShot", "PHONELINUX_SHOT saved=${out.exists()} path=${out.absolutePath}")
        } catch (t: Throwable) {
            android.util.Log.e("PhoneLinuxShot", "desktop screenshot attempt failed", t)
        }
    }
}
