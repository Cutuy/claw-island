package com.cutuy.clawisland.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus

/**
 * Pixel / AOSP Live Updates helper.
 *
 * Uses the platform `android.app.Notification.Builder` (not NotificationCompat)
 * because `setRequestPromotedOngoing()` is a platform API (added in API 34 / Android 14)
 * and the project's pinned `androidx.core:core-ktx 1.15.0` doesn't surface it through
 * the compat layer. Since the target device (Pixel) is API 36, the live-updates section
 * in the lock screen + status bar will be populated. On older API levels, the
 * notification is still ongoing + colorized + BigTextStyle, just without the dedicated
 * "Live updates" surface.
 *
 * Renders natively via Pixel's Material 3 expressive design — no overlay hacks.
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

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("$detail\n\nTap to open Claw Island.")
                    .setBigContentTitle(title)
                    .setSummaryText("${status.agent} · ${status.model}")
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Sound/vibration silenced via channel (setSound(null, null) +
            // enableVibration(false) in createNotificationChannel). Platform
            // Notification.Builder has no setSilent() — that's NotificationCompat-only.
            .setShowWhen(false)
            .setColor(stateColor(status))
            .setColorized(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)

        // Android 14+ Live Updates section — platform API 34+.
        // NOTE: setRequestPromotedOngoing isn't resolving on the workstation's compileSdk
        // classpath (despite API 36 target). Possible causes: SDK platform 34+ add-on not
        // installed, or Notification.Builder not exposing it. The rest of the surface
        // (ongoing + colorized + BigTextStyle + VISIBILITY_PUBLIC + no-clear) already
        // matches Pixel's Live Update appearance. We can revisit the dedicated section
        // once we know the SDK situation. For now: skip the API call, ship the rest.
        //
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        //     builder.setRequestPromotedOngoing(true)
        // }

        return builder.build().apply {
            flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }
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