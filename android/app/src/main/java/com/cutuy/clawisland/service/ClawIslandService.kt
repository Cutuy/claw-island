package com.cutuy.clawisland.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.cutuy.clawisland.models.AgentStatus
import com.cutuy.clawisland.notification.PixelLiveUpdateHelper
import com.cutuy.clawisland.notification.StandardNotificationHelper
import com.cutuy.clawisland.notification.VivoSuperXHelper
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class ClawIslandService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventSource: EventSource? = null
    private var currentStatus = AgentStatus()
    private var isFirstNotification = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // 30s heartbeat: even when SSE delivers static content (e.g. OpenClaw idle with the
    // same current_action.md), re-post the notification so the visible setWhen() timestamp
    // keeps advancing and the user knows updates are flowing.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatTick = object : Runnable {
        override fun run() {
            Log.d(TAG, "Heartbeat: re-posting notification (timestamp only changes)")
            updateNotification(currentStatus)
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "ClawIslandService"
        const val ACTION_START = "com.cutuy.clawisland.action.START"
        const val ACTION_STOP = "com.cutuy.clawisland.action.STOP"
        const val ACTION_SIMULATE = "com.cutuy.clawisland.action.SIMULATE"
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_SIM_PAYLOAD = "extra_sim_payload"
        const val DEFAULT_SERVER_URL = "http://100.64.0.4:18818"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }

    override fun onCreate() {
        super.onCreate()
        if (isVivoDevice()) {
            VivoSuperXHelper.createNotificationChannel(this)
        } else {
            PixelLiveUpdateHelper.createNotificationChannel(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // SIMULATE: route sim test through foreground service so OriginOS promotes it to atomic island.
        // Plain manager.notify() from an Activity context never gets promoted on OriginOS 5/6.
        if (action == ACTION_SIMULATE) {
            val payload = intent?.getStringExtra(EXTRA_SIM_PAYLOAD)
            if (payload.isNullOrEmpty()) {
                Log.w(TAG, "SIMULATE action received without payload, ignoring")
                return START_NOT_STICKY
            }
            try {
                val status = json.decodeFromString<AgentStatus>(payload)
                isFirstNotification = true
                val initial = try {
                    if (isVivoDevice()) {
                        VivoSuperXHelper.buildCapsuleNotification(this, status, isUpdate = false)
                    } else {
                        PixelLiveUpdateHelper.buildLiveUpdateNotification(this, status, isUpdate = false)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Sim notification build failed; using standard fallback", e)
                    StandardNotificationHelper.buildStandardNotification(this, status)
                }
                startForeground(notificationId(), initial)
                currentStatus = status
                updateNotification(status)
                Log.d(TAG, "Sim status posted: ${status.agent} ${status.stateType} ${status.formattedTime}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse SIMULATE payload: $payload", e)
            }
            return START_STICKY
        }

        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: DEFAULT_SERVER_URL

        // Start Foreground Notification immediately. Both helpers are now
        // self-defensive (never throw), but wrap in try/catch anyway — if
        // either path ever does throw, fall back to StandardNotificationHelper
        // rather than killing the service process.
        val initialNotification = try {
            if (isVivoDevice()) {
                VivoSuperXHelper.buildCapsuleNotification(this, currentStatus, isUpdate = false)
            } else {
                PixelLiveUpdateHelper.buildLiveUpdateNotification(this, currentStatus, isUpdate = false)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Initial notification build failed; using standard fallback", e)
            StandardNotificationHelper.buildStandardNotification(this, currentStatus)
        }
        startForeground(notificationId(), initialNotification)

        connectToSse(serverUrl)
        mainHandler.removeCallbacks(heartbeatTick)
        mainHandler.postDelayed(heartbeatTick, HEARTBEAT_INTERVAL_MS)
        Log.d(TAG, "Service started — SSE connect + heartbeat scheduled")

        return START_STICKY
    }

    private fun connectToSse(serverUrl: String) {
        eventSource?.cancel()

        val endpoint = if (serverUrl.endsWith("/")) "${serverUrl}api/events" else "$serverUrl/api/events"
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "SSE Connection Opened to $endpoint")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d(TAG, "SSE event received: ${data.take(120)}${if (data.length > 120) "..." else ""}")
                try {
                    val status = json.decodeFromString<AgentStatus>(data)
                    updateNotification(status)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse SSE payload: $data", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.w(TAG, "SSE Connection Closed. Retrying in 5s...")
                scheduleReconnect(serverUrl)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e(TAG, "SSE Connection Error: ${t?.message}. Retrying in 5s...")
                scheduleReconnect(serverUrl)
            }
        }

        eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, listener)
    }

    private fun scheduleReconnect(serverUrl: String) {
        serviceScope.launch {
            delay(5000)
            connectToSse(serverUrl)
        }
    }

    private fun updateNotification(status: AgentStatus) {
        val isUpdate = !isFirstNotification
        isFirstNotification = false
        currentStatus = status

        val notification = try {
            if (isVivoDevice()) {
                // vivo standard-capsule path (普通胶囊). No whitelist required —
                // status-bar pill only, no two-side island.
                VivoSuperXHelper.buildCapsuleNotification(this, status, isUpdate = isUpdate)
            } else {
                // Pixel / AOSP path — Android 14+ Live Updates. Real system feature,
                // rendered by Material 3, no overlay hacks required.
                PixelLiveUpdateHelper.buildLiveUpdateNotification(this, status, isUpdate = isUpdate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notification build fallback: ${e.message}")
            StandardNotificationHelper.buildStandardNotification(this, status)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = notificationId()
        Log.d(TAG, "notify(id=$id, agent=${status.agent}, state=${status.stateType}, t=${status.formattedTime})")
        notificationManager.notify(id, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        eventSource?.cancel()
        serviceScope.cancel()
        mainHandler.removeCallbacks(heartbeatTick)
        Log.d(TAG, "Service destroyed")
    }

    private fun notificationId(): Int =
        if (isVivoDevice()) VivoSuperXHelper.NOTIFICATION_ID else PixelLiveUpdateHelper.NOTIFICATION_ID

    private fun isVivoDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("vivo") || brand.contains("vivo") ||
            manufacturer.contains("iqoo") || brand.contains("iqoo")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
