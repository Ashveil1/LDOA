package dev.phonelinux.proto

import android.content.Context
import android.content.Intent
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
 * The socket lives in the app's internal files dir, so existence is probed
 * via `run-as` through the shell (debuggable build), not a direct File call.
 * The desktop activity is then launched and screenshotted for inspection
 * (artifact), not asserted.
 */
@RunWith(AndroidJUnit4::class)
class XServerSmokeTest {

    private fun shell(device: UiDevice, cmd: String): String =
        device.executeShellCommand(cmd)

    @Test
    fun xServerSpawnsAndListens() {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        XServer.start(appCtx)

        val sock = File(XServer.socketDir(appCtx), "X0").absolutePath
        val deadline = System.currentTimeMillis() + 60_000
        var ok = false
        var listing = ""
        while (System.currentTimeMillis() < deadline) {
            listing = try { shell(device, "run-as dev.phonelinux.proto ls $sock 2>&1") } catch (t: Throwable) { "shell failed: $t" }
            if (listing.trim() == sock) { ok = true; break }
            Thread.sleep(1_000)
        }
        val logTail = try { shell(device, "run-as dev.phonelinux.proto tail -c 3000 files/xserver.log 2>&1") } catch (t: Throwable) { "(log unreadable: $t)" }
        assertTrue("X socket never appeared at $sock.\nls said: $listing\nServer log tail:\n$logTail", ok)

        // Best-effort: open the desktop activity and screenshot what renders.
        try {
            appCtx.startActivity(
                Intent(appCtx, com.termux.x11.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Thread.sleep(8_000)
            val testCtx = InstrumentationRegistry.getInstrumentation().context
            val shots = File(testCtx.getExternalFilesDir(null), "shots").apply { mkdirs() }
            val out = File(shots, "06-desktop-activity.png")
            val saved = device.takeScreenshot(out)
            android.util.Log.i("PHONELINUX_SHOT", "06-desktop-activity -> ${out.absolutePath} ok=$saved exists=${out.exists()} size=${out.length()}")
        } catch (t: Throwable) {
            android.util.Log.e("PHONELINUX_SHOT", "desktop screenshot attempt failed", t)
        }
    }
}
