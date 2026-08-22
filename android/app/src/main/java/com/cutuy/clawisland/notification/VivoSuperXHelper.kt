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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus

/**
 * vivo OriginOS 5/6 — multi-strategy capsule attempt.
 *
 * History:
 * - Initial version: tried the full `notification.superx.island.*` bundle
 *   (leftInfo / rightInfo / baseInfos / template). OriginOS rejected it
 *   silently because we're not in the partner whitelist.
 * - Second version: only `notification.superx.capsule.*`. Capsule sub-bundle
 *   got ignored too, because vivo's atomic-notification wrapper requires a
 *   whitelisted `scene` value. Jason was right: "根本没有药丸，你在骗人".
 * - This version: present the notification as **ongoing media playback**
 *   via MediaStyle + a MediaSessionCompat. Rationale: vivo's spec lists
 *   "音乐播放" as an atomic-island category, and OriginOS 5/6 has system-
 *   level rendering for ongoing media notifications that doesn't appear to
 *   require explicit partner approval (the system music player uses the
 *   same path). We keep the SuperX capsule bundle as a fallback in case
 *   vivo's classifier picks it up via the media rendering path.
 *
 * If THIS still doesn't surface a capsule, the realistic options are:
 * 1. Accept notification-center-only on vivo (works today).
 * 2. Apply for the vivo atomic-notification whitelist via dev.vivo.com.cn.
 */
object VivoSuperXHelper {

    private const val TAG = "VivoSuperXHelper"
    const val CHANNEL_ID = "claw_island_live_capsule"
    const val NOTIFICATION_ID = 10086
    private const val MEDIA_SESSION_TAG = "ClawIslandMediaSession"

    private var mediaSession: MediaSessionCompat? = null

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenClaw & OpenCode Live Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Live status of OpenCode / OpenClaw agents (rendered as ongoing media for vivo atomic-island classifier)"
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

        val sessionToken = ensureMediaSession(context, status)

        val playPauseLabel = if (status.active) "Pause" else "Resume"
        val playPauseAction = NotificationCompat.Action.Builder(
            R.drawable.ic_claw_logo, playPauseLabel, pendingIntent
        ).build()
        val openAction = NotificationCompat.Action.Builder(
            R.drawable.ic_claw_logo, "Open", pendingIntent
        ).build()

        val timeLabel = if (status.formattedTime.isNotEmpty() && status.formattedTime != "00:00") {
            " · ${status.formattedTime}"
        } else ""

        val title = "${status.agent} · ${status.stateType.uppercase()}$timeLabel"
        val detail = if (status.detail.isNotEmpty()) status.detail else "Standing by..."

        val extras = Bundle().apply {
            attachCapsuleExtras(context, this, status, isUpdate, pendingIntent)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(detail)
            .setSubText("${status.agent} · ${status.model}")
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(pendingIntent)
            )
            .addAction(playPauseAction)
            .addAction(openAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setColor(stateColor(status))
            // CATEGORY_TRANSPORT is the standard category for ongoing media. OriginOS
            // is more likely to route this to its media-handling UI (which may include
            // the atomic island) than CATEGORY_SERVICE or CATEGORY_PROGRESS.
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .addExtras(extras)
            .build()
            .apply {
                this.flags = this.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }
    }

    /**
     * Lazily creates and updates a MediaSession that mirrors the agent's status.
     * Kept alive while the foreground service runs so OriginOS sees a real
     * ongoing media session.
     */
    private fun ensureMediaSession(context: Context, status: AgentStatus): MediaSessionCompat.Token {
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(context, MEDIA_SESSION_TAG).apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                isActive = true
            }
            Log.d(TAG, "MediaSession created")
        }

        val session = mediaSession!!

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "${status.agent} · ${status.stateType}")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, status.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "OpenClaw Live Status")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "${status.agent} · ${status.stateType}")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, status.title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build()
        )

        val playbackState = if (status.active) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setState(playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )

        // MediaSessionCompat.sessionToken returns the framework MediaSession.Token,
        // which MediaStyle.setMediaSession() accepts directly.
        return session.sessionToken
    }

    fun releaseMediaSession() {
        mediaSession?.let {
            try {
                it.isActive = false
                it.release()
            } catch (e: Throwable) {
                Log.w(TAG, "MediaSession release failed", e)
            }
        }
        mediaSession = null
        Log.d(TAG, "MediaSession released")
    }

    /**
     * Capsule sub-bundle (kept as fallback in case OriginOS classifies the
     * media notification as an atomic-notification candidate).
     */
    private fun attachCapsuleExtras(
        context: Context,
        extras: Bundle,
        status: AgentStatus,
        isUpdate: Boolean,
        pendingIntent: PendingIntent
    ) {
        val operation = when {
            isUpdate && !status.active -> 2
            isUpdate -> 1
            else -> 0
        }
        extras.putInt("notification.superx.operation", operation)
        extras.putBoolean("notification.superx.showNotify", true)
        extras.putBoolean("notification.superx.sound", false)
        extras.putBoolean("notification.superx.dismissWhenKill", false)
        extras.putParcelable("notification.superx.clickResp", pendingIntent)

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
            "coding" -> 0xFFFFB59D.toInt()
            "thinking" -> 0xFFDDC48C.toInt()
            "executing" -> 0xFFFFAB91.toInt()
            else -> 0xFF9E9E9E.toInt()
        }
    }
}
