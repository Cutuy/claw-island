package com.cutuy.clawisland.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus

/**
 * Pixel / AOSP Live Updates helper.
 *
 * Conservative platform-Builder implementation. Earlier revisions carried
 * `setColorized(true)` and post-build `FLAG_PROMOTED_ONGOING` mutation —
 * both of those caused AndroidRuntimeExceptions on Android 17 (API 36,
 * Pixel). They have been removed; the system itself decides whether to
 * promote an ongoing foreground-service notification to the "Live updates"
 * lock-screen surface on Pixel, and `setColorized` is only honored with
 * media/decoration styles (which we're not using here).
 *
 * What stays:
 * - BigTextStyle for the expandable body
 * - setOngoing(true) + FLAG_ONGOING_EVENT + FLAG_NO_CLEAR for foreground-service
 *   behavior (stays put, can't be swiped, appears in "Ongoing" section)
 * - setColor() for the small-icon accent (visible on lock screen + status bar)
 * - setWhen + setShowWhen(true) so each post shows a fresh timestamp
 *
 * The helper is self-defensive: every build path is wrapped in try/catch
 * and returns a minimal fallback notification rather than throwing — so
 * the foreground service can always start on Pixel.
 */
object PixelLiveUpdateHelper {

    private const val TAG = "PixelLiveUpdateHelper"
    const val CHANNEL_ID = "claw_island_pixel_live_update"
    const val NOTIFICATION_ID = 10086

    fun createNotificationChannel(context: Context) {
        try {
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
        } catch (e: Throwable) {
            Log.e(TAG, "createNotificationChannel failed (non-fatal)", e)
        }
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

        // Primary build path — rich BigTextStyle with timestamp + color accent.
        return try {
            Notification.Builder(context, CHANNEL_ID)
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
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setColor(stateColor(status))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .build()
                .apply {
                    flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Rich notification build failed; falling back to minimal", e)
            buildMinimalNotification(context, status, pendingIntent)
        }
    }

    /**
     * Last-ditch fallback. No styles, no color, no extras — just enough to keep
     * the foreground service happy and the user oriented. Should never throw.
     */
    private fun buildMinimalNotification(
        context: Context,
        status: AgentStatus,
        pendingIntent: PendingIntent
    ): Notification {
        return try {
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_claw_logo)
                .setContentTitle("${status.agent}: ${status.stateType}")
                .setContentText(status.detail.take(200).ifEmpty { "Standing by..." })
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(pendingIntent)
                .build()
                .apply {
                    flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Minimal fallback also failed; returning bare notification", e)
            // Absolute last resort — no channel, no extras. Should still work
            // because startForeground will accept any non-null Notification.
            Notification()
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
