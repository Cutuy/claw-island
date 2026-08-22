package com.cutuy.clawisland.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.cutuy.clawisland.models.AgentStatus
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

    companion object {
        private const val TAG = "ClawIslandService"
        const val ACTION_START = "com.cutuy.clawisland.action.START"
        const val ACTION_STOP = "com.cutuy.clawisland.action.STOP"
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val DEFAULT_SERVER_URL = "http://100.64.0.4:18818"
    }

    override fun onCreate() {
        super.onCreate()
        VivoSuperXHelper.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: DEFAULT_SERVER_URL

        // Start Foreground Notification immediately
        val initialNotification = VivoSuperXHelper.buildIslandNotification(
            this,
            currentStatus,
            isUpdate = false
        )
        startForeground(VivoSuperXHelper.NOTIFICATION_ID, initialNotification)

        connectToSse(serverUrl)

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
            // Attempt OriginOS 5/6 SuperX Atomic Island bundle
            VivoSuperXHelper.buildIslandNotification(this, status, isUpdate = isUpdate)
        } catch (e: Exception) {
            Log.w(TAG, "SuperX build fallback: ${e.message}")
            StandardNotificationHelper.buildStandardNotification(this, status)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(VivoSuperXHelper.NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        eventSource?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
