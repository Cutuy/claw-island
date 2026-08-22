package com.cutuy.clawisland.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    const val CHANNEL_ID = "claw_island_agent_status"
    const val NOTIFICATION_ID = 10086

    fun createNotificationChannel(context: Context) {
        val name = "OpenClaw & OpenCode Live Status"
        val descriptionText = "Real-time Agent execution tracker for OriginOS Atomic Island"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle("${status.agent}: ${status.title}")
            .setContentText(status.detail.ifEmpty { "Standing by..." })
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Attach SuperX extras for vivo OriginOS Atomic Island
        val extras = Bundle()
        attachVivoSuperXExtras(context, extras, status, isUpdate, pendingIntent)
        builder.addExtras(extras)

        return builder.build()
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
        extras.putBoolean("notification.superx.dismissWhenKill", true)

        // --- Capsule / Island Bundle (Left & Right Island) ---
        val islandBundle = Bundle()

        // Left Island (Left of camera hole: Icon + Name)
        islandBundle.putInt("island.superx.leftTemplate", 1) // 1: Icon + Text
        val leftInfo = Bundle().apply {
            putString("title", status.agent)
            putParcelable("icon", Icon.createWithResource(context, R.drawable.ic_claw_logo))
        }
        islandBundle.putBundle("island.superx.leftInfo", leftInfo)

        // Right Island (Right of camera hole: Dynamic state / pulse / mini text)
        if (status.active) {
            // 3: Loading animation or 6: Capsule text
            islandBundle.putInt("island.superx.rightTemplate", 6)
            val rightInfo = Bundle().apply {
                val badgeText = when (status.stateType) {
                    "coding" -> "🔨 Coding"
                    "thinking" -> "🧠 Thinking"
                    "executing" -> "⚡ Executing"
                    else -> "Active"
                }
                putString("content", badgeText)
            }
            islandBundle.putBundle("island.superx.rightInfo", rightInfo)
        } else {
            islandBundle.putInt("island.superx.rightTemplate", 6)
            val rightInfo = Bundle().apply {
                putString("content", "Idle")
            }
            islandBundle.putBundle("island.superx.rightInfo", rightInfo)
        }

        // Island Click behavior: 0 = Expand large card, 1 = Jump to app
        islandBundle.putInt("island.superx.islandClick", 0)
        islandBundle.putParcelable("island.superx.clickResp", pendingIntent)

        // Expanded Large Card Template: 1 = Highlight Template, 4 = Basic Template
        islandBundle.putInt("island.superx.template", 1)
        val baseInfos = Bundle().apply {
            putString("title", "${status.agent} • ${status.model}")
            putString("state", if (status.active) "Running" else "Idle")
            putString("content", status.title)
            putString("subContent", status.detail)
            putParcelable("icon", Icon.createWithResource(context, R.drawable.ic_claw_logo))
        }
        islandBundle.putBundle("island.superx.baseInfos", baseInfos)

        // Put full island structure into extras
        extras.putBundle("notification.superx.island", islandBundle)

        // Compatibility capsule for older OriginOS versions
        val capsuleBundle = Bundle().apply {
            putInt("notification.superx.capsule.state", 1)
            putParcelable("notification.superx.capsule.icon", Icon.createWithResource(context, R.drawable.ic_claw_logo))
            putString("notification.superx.capsule.content", "${status.agent}: ${if (status.active) status.stateType else "idle"}")
            putInt("notification.superx.capsule.bgColor", Color.parseColor("#1E1E24"))
            putInt("notification.superx.capsule.contentColor", Color.WHITE)
            putParcelable("notification.superx.capsule.clickResp", pendingIntent)
        }
        extras.putBundle("notification.superx.capsule", capsuleBundle)
    }
}
