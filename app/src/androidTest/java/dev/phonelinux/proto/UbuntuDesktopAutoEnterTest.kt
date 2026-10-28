package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * User-spec proof (GNOME lane, runs BEFORE UbuntuGnomeSpikeTest on a fresh
 * install): ONE tap on Install Ubuntu -> install -> GNOME provisioning ->
 * desktop session -> X11 surface, with NO further taps. The auto-enter is
 * product code (MainActivity + XServer), not test glue: the test only taps
 * once and then watches files/logcat + screenshots.
 */
@RunWith(AndroidJUnit4::class)
class UbuntuDesktopAutoEnterTest {
    private val TAG = "PHONELINUX_UBUNTU"

    @Test
    fun coldInstallAutoEntersDesktop(): Unit = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val device = UiDevice.getInstance(instr)

        // Fresh state assumption for THIS lane position: Ubuntu not yet
        // installed. If a previous class installed it, this test's premise
        // is gone - fail loudly rather than faking the cold path.
        val d = Distros.UBUNTU
        assertTrue("auto-enter test must run on a fresh Ubuntu state", !DistroInstaller(ctx).isInstalled(d))

        device.executeShellCommand(
            "am start -n dev.phonelinux.proto/.MainActivity -a android.intent.action.MAIN")
        device.wait(Until.findObject(By.text("Allow")), 15_000)?.click()
        Shot.dismissSystemDialogs(device)

        val installBtn = device.wait(Until.findObject(By.desc("install-ubuntu")), 30_000)
        assertTrue("install-ubuntu button not found on the picker", installBtn != null)
        installBtn.click()
        Log.i(TAG, "auto-enter: install tapped - NO further taps from here")
        Shot.save("28-ubuntu-installing")

        // Auto-enter chain: install -> VM autoDesktop -> XServer.openDesktop
        // -> GNOME provision (long) -> session launch writes guest-gnome.log.
        // Success marker: the session script's alive/fallback line. No taps.
        val logFile = File(ctx.filesDir, "guest-gnome.log")
        val deadline = System.currentTimeMillis() + 55 * 60_000
        var marker = ""
        while (System.currentTimeMillis() < deadline) {
            val text = if (logFile.isFile) logFile.readText() else ""
            val m = text.lineSequence().firstOrNull {
                it.contains("gnome-launch: gnome-session alive") ||
                    it.contains("gnome-launch: gnome-session died") ||
                    it.contains("falling back to bare gnome-shell") }
            if (m != null) { marker = m.trim(); break }
            Thread.sleep(20_000)
        }
        Log.i(TAG, "auto-enter: session marker='" + marker + "'")
        // Let the shell render, then capture the auto-entered desktop.
        Thread.sleep(120_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("29-ubuntu-autoenter-desktop")
        assertTrue("install did not auto-enter the desktop (no session marker in guest-gnome.log)",
            marker.isNotEmpty())
        Log.i(TAG, "UBUNTU-AUTOENTER-OK one tap -> install -> desktop session (" + marker + ")")
    }
}
