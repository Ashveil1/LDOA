package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B feasibility spike: Ubuntu Base rootfs + GNOME session on the vendored
 * lorie X server. This RECORDS evidence (guest-gnome.log lines, screenshots
 * via the shot pipeline); it asserts only the launch-path facts. Whether the
 * result LOOKS like standard Ubuntu (top bar + dock + Yaru + wallpaper) is a
 * pixel-inspection call on the artifact screenshots, not a test assert.
 *
 * Runs in its own CI job (ubuntu-gnome-spike): apt-in-emulator is far too
 * slow for the every-push lane.
 */
@RunWith(AndroidJUnit4::class)
class UbuntuGnomeSpikeTest {
    private val TAG = "PHONELINUX_UBUNTU"

    @Test
    fun ubuntuGnomeSessionStarts() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)

        if (!installer.isInstalled(d)) {
            var last: InstallState = InstallState.NotInstalled
            val ok = installer.install(d) { last = it }
            assertTrue("ubuntu install failed at state=$last", ok)
        }
        assertTrue(installer.isInstalled(d))
        val rootfs = installer.rootfsDir(d)

        // Guest sanity: right distro, right arch, apt present.
        val g = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "uname -m; . /etc/os-release; echo \"os=\${NAME}-\${VERSION_ID}\"; apt-get --version | head -1"))
        Log.i(TAG, "guest sanity exit=${g.exitCode} out=${g.stdout.trim()}")
        assertEquals("guest exec must exit 0. stderr=${g.stderr}", 0, g.exitCode)
        val expectedArch = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "aarch64"
        assertTrue("guest arch $expectedArch, got: ${g.stdout}", g.stdout.contains(expectedArch))
        assertTrue("guest must be Ubuntu, got: ${g.stdout}", g.stdout.contains("Ubuntu"))

        // Provision GNOME + Ubuntu appearance set (long: apt download+install).
        if (!GuestUbuntu.hasGnome(rootfs)) {
            val r = GuestUbuntu.provisionGnome(ctx, rootfs)
            Log.i(TAG, "provision exit=${r.exitCode} tail=${r.stdout.takeLast(800)} errtail=${r.stderr.takeLast(400)}")
            assertTrue("gnome provision failed exit=${r.exitCode}: ${r.stdout.takeLast(400)}", r.exitCode == 0)
        }
        assertTrue("gnome-shell binary present", GuestUbuntu.hasGnome(rootfs))

        // X server + GNOME session.
        XServer.start(ctx)
        val proc = GuestUbuntu.launchGnomeSession(ctx, rootfs)

        // Evidence window: poll guest-gnome.log for launch markers, up to 10 min.
        val logFile = File(ctx.filesDir, "guest-gnome.log")
        val deadline = System.currentTimeMillis() + 600_000
        var sawLaunchMarker = false
        while (System.currentTimeMillis() < deadline) {
            val text = if (logFile.isFile) logFile.readText() else ""
            if (text.contains("gnome-launch:")) sawLaunchMarker = true
            if (text.contains("gnome-launch: gnome-session alive") ||
                text.contains("gnome-launch: gnome-session died") ||
                text.contains("falling back to bare gnome-shell")) break
            // Decisive failures end the poll immediately (no blind waiting):
            // gnome-shell's own "sad computer" screen and hard X/dbus errors.
            if (text.contains("Oh no! Something has gone wrong") ||
                text.contains("Cannot open display") ||
                text.contains("gnome-shell: not found")) {
                Log.i(TAG, "decisive failure marker in gnome log; failing fast")
                break
            }
            if (!proc.isAlive) break
            Thread.sleep(10_000)
        }
        Log.i(TAG, "gnome log tail: " + (if (logFile.isFile) logFile.readText().takeLast(1500) else "<missing>"))
        assertTrue("session script never reached the launch marker", sawLaunchMarker)
        assertTrue("gnome session process died before the evidence window closed", proc.isAlive)

        // Pixel evidence: show the X surface and capture early + settled
        // shots (GNOME's first start on an emulator can take minutes; the
        // two shots let pixel inspection see the progression).
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instr)
        ctx.startActivity(
            android.content.Intent(ctx, com.termux.x11.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(30_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("20-ubuntu-gnome-early")
        Thread.sleep(90_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("21-ubuntu-gnome-settled")
        Log.i(TAG, "gnome log tail2: " + (if (logFile.isFile) logFile.readText().takeLast(1500) else "<missing>"))
        assertTrue("gnome session died during the render window", proc.isAlive)
        Log.i(TAG, "UBUNTU-GNOME-SESSION-ALIVE (visual Ubuntu appearance is judged on artifact screenshots)")
    }
}
