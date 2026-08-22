package com.cutuy.clawisland.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus

object StandardNotificationHelper {

    fun buildStandardNotification(
        context: Context,
        status: AgentStatus
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

        val title = "${status.agent} [${if (status.active) status.stateType.uppercase() else "IDLE"}]: ${status.title}"
        val detail = status.detail.ifEmpty { "Standing by for tasks..." }

        return NotificationCompat.Builder(context, VivoSuperXHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText("$detail\nModel: ${status.model}\nProcesses: ${status.procCount}")
            )
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
