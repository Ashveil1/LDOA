package dev.phonelinux.proto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * L1 lifecycle (accepted requirements File v2, Ash 2026-10-29 19:26):
 * keep the Linux desktop session alive in the background with a
 * user-visible persistent notification ("Linux is running, tap to
 * return"), a MANUAL wakelock toggle the user presses themselves, and a
 * Stop action. NEVER acquires a wakelock on its own, never holds one
 * automatically, and never touches battery-optimization settings.
 *
 * Scope honesty: the service lives in the app process (no process move -
 * that is the unbuilt L2 design). Swiping the app from recents can still
 * kill everything; the notification survives only ordinary backgrounding.
 */
class SessionService : Service() {
    companion object {
        private const val TAG = "LdoaSession"
        private const val CHANNEL = "ldoa_session"
        internal const val NOTIF_ID = 42
        internal const val ACTION_STOP = "dev.phonelinux.proto.action.SESSION_STOP"
        internal const val ACTION_WAKELOCK = "dev.phonelinux.proto.action.WAKELOCK_TOGGLE"

        @Volatile private var running = false
        @Volatile private var wakelockHeld = false

        fun isRunning() = running
        fun isWakelockHeld() = wakelockHeld

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "user stop: ending desktop session, releasing wakelock, stopping service")
                // Same UI-thread ANR hazard as the dialog's Stop button:
                // stopDesktopMode blocks seconds; onStartCommand runs on the
                // main thread. Background the teardown.
                thread(name = "desktop-stop") { XServer.stopDesktopMode(applicationContext) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WAKELOCK -> {
                toggleWakelock()
                return START_STICKY
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Linux session", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        running = true
        watchSession()
        Log.i(TAG, "session service foreground (specialUse); wakelockHeld=" + wakelockHeld + " (manual toggle only)")
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val content = PendingIntent.getActivity(this, 0,
            Intent(this, com.termux.x11.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val wake = PendingIntent.getService(this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_WAKELOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Linux desktop running")
            .setContentText("Tap to return. Wakelock: " + (if (wakelockHeld) "on" else "off"))
            .setOngoing(true)
            .setContentIntent(content)
            .addAction(0, if (wakelockHeld) "Wakelock: on" else "Wakelock: off", wake)
            .addAction(0, "Stop", stop)
            .build()
    }

    /** The ONLY wakelock path in the app: user-pressed toggle. Acquisition
     *  is never automatic; release happens on the second press or whenever
     *  the service dies (onDestroy), whichever comes first. */
    private fun toggleWakelock() {
        if (wakelockHeld) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wakelockHeld = false
            Log.i(TAG, "wakelock RELEASED by user toggle")
        } else {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ldoa:session").apply { acquire() }
            wakelockHeld = true
            Log.i(TAG, "wakelock ACQUIRED by user toggle (manual only)")
        }
        // Trace the ACTUAL posted text (run 40925177246: held-flag flipped but
        // the active notification kept the old text; NMS-side capture lives in
        // SessionServiceTest.a03's failure path).
        val n = buildNotification()
        Log.i(TAG, "posting toggle notification text=[" +
            n.extras.getCharSequence("android.text") + "] wakelockHeld=" + wakelockHeld)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    /** Session end = service end: when the tracked guest session process
     *  exits, the notification and any held wakelock go away with it. */
    private fun watchSession() {
        watcher?.interrupt()
        watcher = thread(name = "ldoa-session-watch") {
            while (running) {
                val p = XServer.sessionProcess
                if (p != null && !p.isAlive) {
                    Log.i(TAG, "tracked session process exited - stopping service")
                    stopSelf()
                    break
                }
                try { Thread.sleep(2_000) } catch (t: InterruptedException) { break }
            }
        }
    }

    override fun onDestroy() {
        running = false
        watcher?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wakelockHeld = false
        Log.i(TAG, "session service destroyed; wakelock released")
        super.onDestroy()
    }
}
