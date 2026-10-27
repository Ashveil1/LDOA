package dev.phonelinux.proto

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The GUI milestone: a REAL guest X client (xclock installed via apk inside
 * the proot guest) draws into the embedded X server through the bound
 * socket, displayed by the vendored lorie activity. Evidence: socket probe,
 * xclock stays alive, and 07-desktop-client.png captured via shell screencap
 * (inspected as a CI artifact).
 */
@RunWith(AndroidJUnit4::class)
class DesktopClientTest {

    @Test
    fun guestXclockDrawsThroughEmbeddedServer() = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val installer = DistroInstaller(appCtx)
        val distro = Distros.ALPINE
        if (!installer.isInstalled(distro)) {
            assertTrue("distro install failed", installer.install(distro) { })
        }
        val rootfs = installer.rootfsDir(distro)

        XServer.start(appCtx) // provisions xkb data, spawns server

        val sock = File(XServer.socketDir(appCtx), "X0").absolutePath
        val deadline = System.currentTimeMillis() + 60_000
        var up = false
        while (System.currentTimeMillis() < deadline) {
            val ls = try { device.executeShellCommand("run-as dev.phonelinux.proto ls $sock 2>&1") } catch (t: Throwable) { "" }
            if (ls.trim() == sock) { up = true; break }
            Thread.sleep(1_000)
        }
        val logTail = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 2000 files/xserver.log 2>&1") } catch (t: Throwable) { "(log unreadable)" }
        assertTrue("X socket never appeared. Server log tail:\n$logTail", up)

        if (!GuestDesktop.hasXclock(rootfs)) {
            val add = GuestDesktop.provisionXclock(appCtx, rootfs)
            assertTrue("apk add xclock failed (exit ${add.exitCode}): ${(add.stdout + add.stderr).takeLast(600)}", add.exitCode == 0)
        }
        assertTrue("xclock missing after provisioning", GuestDesktop.hasXclock(rootfs))

        val client = GuestDesktop.launchXclock(appCtx, rootfs)
        appCtx.startActivity(
            Intent(appCtx, com.termux.x11.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(12_000)
        val guiLog = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 1000 files/guest-gui.log 2>&1") } catch (t: Throwable) { "" }
        assertTrue("xclock exited early - not drawing. Guest log:\n$guiLog", client.isAlive)

        Shot.dismissSystemDialogs(device)
        Shot.save("07-desktop-client")

        client.destroy()

        // Desktop shell phase: real wm + panel + file manager, the
        // computer-like desktop the user asked for.
        if (!GuestDesktop.hasDesktopShell(rootfs)) {
            val add = GuestDesktop.provisionDesktopShell(appCtx, rootfs)
            assertTrue("apk add desktop shell failed (exit ${add.exitCode}): ${(add.stdout + add.stderr).takeLast(600)}", add.exitCode == 0)
        }
        assertTrue("desktop shell missing after provisioning", GuestDesktop.hasDesktopShell(rootfs))

        val session = GuestDesktop.launchDesktopSession(appCtx, rootfs)
        Thread.sleep(25_000)
        val wmAlive = GuestDesktop.guestOk(appCtx, rootfs, "pgrep openbox")
        val panelAlive = GuestDesktop.guestOk(appCtx, rootfs, "pgrep lxpanel")
        val fmAlive = GuestDesktop.guestOk(appCtx, rootfs, "pgrep pcmanfm")
        val deskLog = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 1500 files/guest-desktop.log 2>&1") } catch (t: Throwable) { "" }
        assertTrue("openbox not running. Desktop log:\n$deskLog", wmAlive)
        assertTrue("lxpanel not running. Desktop log:\n$deskLog", panelAlive)
        assertTrue("pcmanfm not running. Desktop log:\n$deskLog", fmAlive)
        assertTrue("desktop session exited early. Desktop log:\n$deskLog", session.isAlive)

        Shot.dismissSystemDialogs(device)
        Shot.save("08-desktop-shell")

        session.destroy()
    }
}
