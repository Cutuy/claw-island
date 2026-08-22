package com.cutuy.clawisland.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus

/**
 * vivo OriginOS 5/6 SuperX Atomic Island (原子岛/原子通知) Helper
 * Wraps parameters according to vivo SuperX Bundle Protocol.
 */
object VivoSuperXHelper {

    const val CHANNEL_ID = "claw_island_live_capsule"
    const val NOTIFICATION_ID = 10086

    fun createNotificationChannel(context: Context) {
        val name = "OpenClaw & OpenCode Live Island"
        val descriptionText = "Real-time Agent execution tracker for OriginOS Atomic Island"
        // OriginOS suppresses Island/Capsule on LOW importance. Must be DEFAULT or HIGH.
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun buildIslandNotification(
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
            "[${status.formattedTime}] "
        } else ""

        val title = "${status.agent} $timeLabel• ${status.stateType.uppercase()}"
        val detail = if (status.detail.isNotEmpty()) status.detail else "Standing by..."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)

        // Non-dismissible flags
        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        // Attach SuperX extras for vivo OriginOS Atomic Island
        val extras = Bundle()
        attachVivoSuperXExtras(context, extras, status, isUpdate, pendingIntent)
        builder.addExtras(extras)

        val finalNotification = builder.build()
        finalNotification.flags = finalNotification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return finalNotification
    }

    private fun attachVivoSuperXExtras(
        context: Context,
        extras: Bundle,
        status: AgentStatus,
        isUpdate: Boolean,
        pendingIntent: PendingIntent
    ) {
        // 0: Create, 1: Update, 2: End
        val operation = if (status.status == "idle" && !status.active) 1 else (if (isUpdate) 1 else 0)
        extras.putInt("notification.superx.operation", operation)

        // 0x111 = Status bar / Island (0x100) + Lockscreen (0x010) + Notification Center (0x001)
        extras.putInt("notification.superx.displays", 0x111)
        extras.putBoolean("notification.superx.sound", false)
        extras.putBoolean("notification.superx.dismissWhenKill", false) // Do not dismiss on kill

        // --- Capsule / Island Bundle (Left & Right Island) ---
        val islandBundle = Bundle()

        // Left Island (Left of camera hole: Icon + Name)
        islandBundle.putInt("island.superx.leftTemplate", 1) // 1: Icon + Text
        val leftInfo = Bundle().apply {
            putString("title", status.agent)
            putParcelable("icon", Icon.createWithResource(context, R.drawable.ic_claw_logo))
        }
        islandBundle.putBundle("island.superx.leftInfo", leftInfo)

        // Right Island (Right of camera hole: Dynamic state / elapsed time / capsule badge)
        islandBundle.putInt("island.superx.rightTemplate", 6) // 6: Capsule text
        val rightInfo = Bundle().apply {
            val badgeText = if (status.active) {
                val timer = if (status.formattedTime.isNotEmpty() && status.formattedTime != "00:00") {
                    " ${status.formattedTime}"
                } else ""
                when (status.stateType) {
                    "coding" -> "🔨$timer"
                    "thinking" -> "🧠$timer"
                    "executing" -> "⚡$timer"
                    else -> "Active$timer"
                }
            } else {
                "Idle"
            }
            putString("content", badgeText)
        }
        islandBundle.putBundle("island.superx.rightInfo", rightInfo)

        // Island Click behavior: 0 = Expand large card, 1 = Jump to app
        islandBundle.putInt("island.superx.islandClick", 0)
        islandBundle.putParcelable("island.superx.clickResp", pendingIntent)

        // Expanded Large Card Template: 1 = Highlight Template, 4 = Basic Template
        islandBundle.putInt("island.superx.template", 1)
        val baseInfos = Bundle().apply {
            val timeStr = if (status.formattedTime.isNotEmpty()) " (${status.formattedTime})" else ""
            putString("title", "${status.agent} • ${status.model}$timeStr")
            putString("state", if (status.active) status.stateType.uppercase() else "IDLE")
            putString("content", status.title)
            putString("subContent", status.detail)
            putParcelable("icon", Icon.createWithResource(context, R.drawable.ic_claw_logo))
        }
        islandBundle.putBundle("island.superx.baseInfos", baseInfos)

        // Put full island structure into extras
        extras.putBundle("notification.superx.island", islandBundle)

        // Status bar classic Capsule compatibility for OriginOS 4/5
        val capsuleContent = if (status.active) {
            val timeStr = if (status.formattedTime.isNotEmpty()) " ${status.formattedTime}" else ""
            "${status.agent}: ${status.stateType}$timeStr"
        } else {
            "${status.agent}: Idle"
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
}
