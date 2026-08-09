package com.deepmost.rabbitav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.deepmost.rabbitav.MainActivity
import com.deepmost.rabbitav.R

/** Live drive notification (Section 5.11): speed, alerts, hazards, stop action. */
class NotificationHelper(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW, // silent; alert audio is SoundPool's job
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    fun build(hud: HudState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, DriveForegroundService::class.java).setAction(DriveForegroundService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (hud.mode) {
            DriveMode.POCKET -> context.getString(R.string.notif_title_pocket)
            DriveMode.REPLAY -> context.getString(R.string.notif_title_replay)
            else -> context.getString(R.string.notif_title_drive)
        }
        val text = context.getString(
            R.string.notif_text_format,
            hud.speedKmh.toInt(),
            hud.tripDistanceKm,
            hud.hazardsThisTrip,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rabbit)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "rabbitav_drive"
        const val NOTIFICATION_ID = 42
    }
}
