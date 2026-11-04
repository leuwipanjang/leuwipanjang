package id.biz.id.leuwipanjang.ssh

import android.annotation.SuppressLint
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.TaskStackBuilder
import id.biz.id.leuwipanjang.R
import id.biz.id.leuwipanjang.ui.MainActivity
import id.biz.id.leuwipanjang.ui.terminal.TerminalActivity

/**
 * Foreground service that keeps the process (and so its SSH sockets) running while any
 * session in [SessionRegistry] is open. Without it Android freezes the app shortly after it
 * leaves the screen and the server drops the connection. Optionally holds a partial wake
 * lock so keep-alives continue while the screen is off.
 */
class SshSessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "ssh_sessions"
        private const val NOTIFICATION_ID = 22
        private const val ACTION_DISCONNECT_ALL = "id.biz.id.leuwipanjang.action.DISCONNECT_ALL"
        private const val ACTION_REFRESH = "id.biz.id.leuwipanjang.action.REFRESH"

        const val PREFS = "leuwipanjang_settings"
        const val PREF_WAKE_LOCK = "keep_cpu_awake"

        fun intent(context: Context) = Intent(context, SshSessionService::class.java)

        /** Re-reads settings (e.g. the wake lock toggle) if the service is running. */
        fun refresh(context: Context) {
            if (SessionRegistry.all.isEmpty()) return
            context.startService(intent(context).setAction(ACTION_REFRESH))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val onSessionsChanged: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        SessionRegistry.observe(onSessionsChanged)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: startForegroundService() requires it even if
        // there turns out to be nothing left to keep alive.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            SessionRegistry.closeAll()
        }
        update()
        // Sessions cannot survive the process dying, so there is nothing to restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SessionRegistry.removeObserver(onSessionsChanged)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun update() {
        if (SessionRegistry.all.isEmpty()) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
        val wantWakeLock = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_WAKE_LOCK, false)
        if (wantWakeLock) acquireWakeLock() else releaseWakeLock()
    }

    private fun buildNotification(): android.app.Notification {
        val sessions = SessionRegistry.all
        val count = sessions.size
        val title = resources.getQuantityString(R.plurals.session_notification_title, count, count)
        val text = sessions.joinToString { "${it.host.username}@${it.host.hostname}" }
            .ifEmpty { getString(R.string.session_notification_idle) }

        // Tap: open the most recent session's terminal on top of the host list.
        val contentIntent = sessions.lastOrNull()?.let { latest ->
            TaskStackBuilder.create(this)
                .addNextIntent(Intent(this, MainActivity::class.java))
                .addNextIntent(
                    Intent(this, TerminalActivity::class.java)
                        .putExtra(TerminalActivity.EXTRA_HOST_ID, latest.host.id)
                )
                .getPendingIntent(
                    0,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        }
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            intent(this).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.session_notification_disconnect_all), disconnectIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.session_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.session_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout") // held exactly as long as sessions are open, by user choice
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LeuwiPanjang:ssh-sessions")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
