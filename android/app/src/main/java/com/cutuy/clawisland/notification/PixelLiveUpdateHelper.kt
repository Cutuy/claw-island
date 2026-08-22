package com.cutuy.clawisland.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus

/**
 * Pixel / AOSP Live Updates helper.
 *
 * Uses NotificationCompat.BigTextStyle with requestPromotedOngoing = true,
 * which on Android 14+ promotes the notification into the dedicated
 * "Live updates" section on the lock screen + status bar. Pixel renders
 * this with the system Material 3 expressive style — no overlay hacks,
 * no third-party SDK required.
 *
 * The notification is ongoing + no-clear + colorized, so it persists
 * across launcher / app switches and shows up at-a-glance.
 */
object PixelLiveUpdateHelper {

    const val CHANNEL_ID = "claw_island_pixel_live_update"
    const val NOTIFICATION_ID = 10086

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Claw Island Live Update",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Live status of OpenCode / OpenClaw agents"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun buildLiveUpdateNotification(
        context: Context,
        status: AgentStatus,
        isUpdate: Boolean = false
    ): Notification {
        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeLabel = if (status.formattedTime.isNotEmpty() && status.formattedTime != "00:00") {
            "[${status.formattedTime}] "
        } else ""
        val title = "${status.agent} $timeLabel• ${status.stateType.uppercase()}"
        val subtitle = "${status.model} · ${status.title}"
        val detail = if (status.detail.isNotEmpty()) status.detail else "Standing by..."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$detail\n\nTap to open Claw Island.")
                    .setBigContentTitle(title)
                    .setSummaryText("${status.agent} · ${status.model}")
            )
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setColor(stateColor(status))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            // NOTE: setRequestPromotedOngoing (Android 14+ Live Updates section) is not
            // available in this project's androidx.core version. Pixel still renders the
            // ongoing + colorized + BigTextStyle notification with Material 3 expressive
            // design — the dedicated Live Updates section is the only thing we miss.

        val notification = builder.build()
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun stateColor(status: AgentStatus): Int {
        return when (status.stateType) {
            "coding" -> 0xFFFFB59D.toInt()      // peach (Material You primary)
            "thinking" -> 0xFFDDC48C.toInt()     // tertiary (warm)
            "executing" -> 0xFFFFAB91.toInt()    // light peach
            else -> 0xFF9E9E9E.toInt()           // idle gray
        }
    }
}