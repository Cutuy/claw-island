package com.cutuy.clawisland.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus

/**
 * vivo OriginOS 5/6 standard-capsule (普通胶囊) helper.
 *
 * Downgraded from the full SuperX Atomic Island path. The previous version
 * populated `notification.superx.island.*` (two-side island around the punch-hole)
 * but vivo requires apps to be on a whitelist for that — third-party apps get
 * silently downgraded to a regular notification. After talking with Jason about
 * this on 2026-08-22 we agreed: just take the status-bar pill for now and stop
 * pretending we can render the island.
 *
 * This version populates ONLY `notification.superx.capsule.*` (the status-bar pill).
 * Per vivo's published spec, the capsule sub-bundle is optional and, in practice,
 * available to all apps with notification permission — no whitelist, no scene
 * negotiation, no VPush SDK. Real-world behavior varies by OriginOS version; the
 * `notification.superx.showNotify = true` fallback ensures a rich regular
 * notification is always visible in the notification center even if OriginOS
 * rejects the capsule.
 *
 * No overlay hacks, no foreground hacks — just a notification + bundle.
 */
object VivoSuperXHelper {

    private const val TAG = "VivoSuperXHelper"
    const val CHANNEL_ID = "claw_island_live_capsule"
    const val NOTIFICATION_ID = 10086

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenClaw & OpenCode Live Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Status-bar capsule showing live OpenCode / OpenClaw activity"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun buildCapsuleNotification(
        context: Context,
        status: AgentStatus,
        isUpdate: Boolean = false
    ): Notification {
        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeLabel = if (status.formattedTime.isNotEmpty() && status.formattedTime != "00:00") {
            " · ${status.formattedTime}"
        } else ""

        val title = "${status.agent} • ${status.stateType.uppercase()}$timeLabel"
        val detail = if (status.detail.isNotEmpty()) status.detail else "Standing by..."

        val extras = Bundle().apply {
            attachCapsuleExtras(context, this, status, isUpdate, pendingIntent)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$detail\n\nTap to open Claw Island.")
                    .setBigContentTitle(title)
                    .setSummaryText("${status.agent} · ${status.model}")
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Live timestamp visible on every update — same trick as the Pixel helper,
            // so the user can SEE the notification updating even when content is static.
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setColor(stateColor(status))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .addExtras(extras)
            .build()
            .apply {
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }
    }

    /**
     * Populates only the standard-capsule sub-bundle. No island / no scene / no template
     * fields — those are whitelist-only. The capsule sub-bundle is optional in vivo's
     * spec and not explicitly gated by partner agreement.
     */
    private fun attachCapsuleExtras(
        context: Context,
        extras: Bundle,
        status: AgentStatus,
        isUpdate: Boolean,
        pendingIntent: PendingIntent
    ) {
        // 0: Create, 1: Update, 2: End
        val operation = when {
            isUpdate && !status.active -> 2  // transitioning to idle → end capsule
            isUpdate -> 1                    // any other update
            else -> 0                        // first notification ever
        }
        extras.putInt("notification.superx.operation", operation)

        // Always show a regular notification if OriginOS can't render the capsule —
        // this is the safe default so the user always sees *something*.
        extras.putBoolean("notification.superx.showNotify", true)
        extras.putBoolean("notification.superx.sound", false)
        extras.putBoolean("notification.superx.dismissWhenKill", false)

        // Top-level click handler (used as fallback if capsule.clickResp isn't set).
        extras.putParcelable("notification.superx.clickResp", pendingIntent)

        // Standard status-bar capsule (普通胶囊).
        val capsuleContent = if (status.active) {
            val timeStr = if (status.formattedTime.isNotEmpty() && status.formattedTime != "00:00") " ${status.formattedTime}" else ""
            "${status.agent} · ${status.stateType}$timeStr"
        } else {
            "${status.agent} · Idle"
        }

        val capsuleBundle = Bundle().apply {
            putInt("notification.superx.capsule.state", 1)
            putParcelable("notification.superx.capsule.icon", Icon.createWithResource(context, R.drawable.ic_claw_logo))
            putString("notification.superx.capsule.content", capsuleContent)
            putInt("notification.superx.capsule.bgColor", Color.parseColor("#1C1B1F"))
            putInt("notification.superx.capsule.contentColor", Color.parseColor("#FFD8E4"))
            putParcelable("notification.superx.capsule.clickResp", pendingIntent)
        }
        extras.putBundle("notification.superx.capsule", capsuleBundle)
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
