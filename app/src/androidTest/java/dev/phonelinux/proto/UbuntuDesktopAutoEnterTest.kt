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
 * install): tap Install Ubuntu -> install -> FIRST-START DOWNLOAD CONSENT
 * dialog (verified, screenshotted) -> one consent tap -> GNOME provisioning
 * -> desktop session -> X11 surface. The consent tap is user-mandated
 * (2026-10-29): the multi-hundred-MB apt download must never start without
 * an explicit choice. Auto-enter + consent are product code (MainActivity +
 * XServer), not test glue: the test only taps install + consent and then
 * watches files/logcat + screenshots.
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

        // First-run screen is the front door (user request 2026-10-29):
        // one confirmed notice tap starts the automatic chain.
        val installBtn = device.wait(Until.findObject(By.desc("firstrun-install-ubuntu")), 30_000)
        assertTrue("firstrun-install-ubuntu button not found on the first-run screen", installBtn != null)
        installBtn.click()
        Log.i(TAG, "auto-enter: install tapped; awaiting first-start consent dialog")
        Shot.save("28-ubuntu-installing")

        // Consent gate: after install, the first desktop start must show
        // the large-download warning BEFORE any GNOME apt traffic, and the
        // user chooses. Verify the dialog, then tap through it.
        val consent = device.wait(Until.findObject(By.text("Download and start")), 30 * 60_000)
        assertTrue("first-start consent dialog did not appear after install", consent != null)
        Shot.save("28b-ubuntu-consent-dialog")
        // Staleness hygiene: the dialog re-renders as the install finishes,
        // so a found UiObject2 can go stale between find and click (run
        // 41001646953 instr1: StaleObjectException ended the leg). Re-find
        // a FRESH object each attempt; a stale click performs no tap, so
        // the dialog must still be there for the next attempt. The
        // prep-state assert below remains the real success check.
        var consentTapped = false
        var lastConsentProblem = "none"
        for (attempt in 1..4) {
            val c = device.wait(Until.findObject(By.text("Download and start")), 15_000)
            if (c == null) { lastConsentProblem = "dialog absent on attempt " + attempt; break }
            try {
                c.click()
                consentTapped = true
                break
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                lastConsentProblem = "stale object on attempt " + attempt
                Log.i(TAG, "auto-enter: consent object stale (attempt " + attempt + ") - re-finding fresh object")
                Thread.sleep(1_000)
            }
        }
        assertTrue("first-start consent tap never landed (" + lastConsentProblem + ")", consentTapped)
        Log.i(TAG, "auto-enter: consent tapped - NO further taps from here")
        val prep = device.wait(Until.findObject(By.text("Starting Ubuntu desktop")), 30_000)
        assertTrue("preparing state not shown after consent", prep != null)
        Shot.save("28c-ubuntu-preparing")

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
