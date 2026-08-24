package com.cutuy.clawisland.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.service.ClawIslandService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ClawFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistrationClient.register(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val eventId = message.data["event_id"].orEmpty()
        if (eventId.isEmpty() || MonitorPreferences.hasSeenEvent(this, eventId)) return
        MonitorPreferences.markEventSeen(this, eventId)
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("push_event_id", eventId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = message.data["title"] ?: "Session update"
        val body = message.data["body"] ?: message.data["repo"] ?: "Agent session changed"
        val notification = NotificationCompat.Builder(this, ClawIslandService.SESSION_EVENTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(eventId.hashCode(), notification)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                ClawIslandService.SESSION_EVENTS_CHANNEL_ID,
                "Session Events",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
