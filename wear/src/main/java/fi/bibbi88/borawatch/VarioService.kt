package fi.bibbi88.borawatch

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

/**
 * Foreground service: keeps sensors, filters and Bluetooth running with the screen off.
 * Android only delivers accelerometer/gyro/barometer data to apps in the foreground,
 * and a foreground service counts as foreground.
 */
class VarioService : Service() {

    companion object {
        private const val CHANNEL = "vario"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "fi.bibbi88.borawatch.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, VarioService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, VarioService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Vario running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Bora Watch")
            .setContentText("Vario running – sending over Bluetooth")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            // e.g. Bluetooth permission missing: cannot run as a connected-device service
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoraWatch:vario").apply { acquire() }
        }
        Engine.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Engine.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
