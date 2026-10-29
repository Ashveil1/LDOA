package dev.phonelinux.proto

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * L1 lifecycle tests (accepted requirements File v2): the session
 * foreground service must (a) run even when the notification permission
 * is denied, (b) post its ongoing notification when granted, (c) never
 * hold a wakelock automatically, (d) acquire/release it only on the
 * user's toggle, (e) release everything on user stop, and (f) stop
 * itself when the tracked session ends.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SessionServiceTest {
    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val device get() = UiDevice.getInstance(instr)
    private val pkg get() = ctx.packageName

    private fun shell(cmd: String) = device.executeShellCommand(cmd)

    private fun awaitTrue(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(500)
        }
        fail("timed out waiting for: $what")
    }

    private fun toggleWakelock() {
        ctx.startService(Intent(ctx, SessionService::class.java).setAction(SessionService.ACTION_WAKELOCK))
    }

    @Test
    fun a01_notificationDenied_serviceStillRunsNoWakelock() {
        shell("pm revoke $pkg android.permission.POST_NOTIFICATIONS")
        SessionService.stop(ctx)
        Thread.sleep(1_500)
        SessionService.start(ctx)
        awaitTrue(10_000, "service runs with notification permission denied") { SessionService.isRunning() }
        assertFalse("no wakelock may be held on service start (no auto wakelock)",
            SessionService.isWakelockHeld())
    }

    @Test
    fun a02_notificationGranted_postsOngoingNotification() {
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        SessionService.start(ctx)
        awaitTrue(10_000, "service running") { SessionService.isRunning() }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        awaitTrue(10_000, "ongoing session notification posted") {
            nm.activeNotifications.any { it.id == SessionService.NOTIF_ID }
        }
    }

    @Test
    fun a03_manualWakelockToggle_acquiresThenReleases() {
        SessionService.start(ctx)
        awaitTrue(5_000, "service running") { SessionService.isRunning() }
        assertFalse("no auto wakelock", SessionService.isWakelockHeld())
        toggleWakelock()
        awaitTrue(5_000, "wakelock held after user toggle on") { SessionService.isWakelockHeld() }
        toggleWakelock()
        awaitTrue(5_000, "wakelock released after user toggle off") { !SessionService.isWakelockHeld() }
    }

    @Test
    fun a04_userStop_releasesWakelockAndStopsService() {
        SessionService.start(ctx)
        awaitTrue(5_000, "service running") { SessionService.isRunning() }
        toggleWakelock()
        awaitTrue(5_000, "wakelock held") { SessionService.isWakelockHeld() }
        ctx.startService(Intent(ctx, SessionService::class.java).setAction(SessionService.ACTION_STOP))
        awaitTrue(15_000, "service stopped after user stop") { !SessionService.isRunning() }
        assertFalse("user stop must release the wakelock", SessionService.isWakelockHeld())
    }

    @Test
    fun a05_sessionEnd_stopsServiceAndReleasesWakelock() {
        SessionService.start(ctx)
        awaitTrue(5_000, "service running") { SessionService.isRunning() }
        toggleWakelock()
        awaitTrue(5_000, "wakelock held") { SessionService.isWakelockHeld() }
        val fake = ProcessBuilder("/system/bin/sleep", "300").start()
        XServer.sessionProcess = fake
        Thread.sleep(500)
        fake.destroy()
        awaitTrue(15_000, "service stops when the tracked session exits") { !SessionService.isRunning() }
        assertFalse("session end must release the wakelock", SessionService.isWakelockHeld())
        XServer.sessionProcess = null
    }
}
