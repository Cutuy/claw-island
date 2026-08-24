package com.cutuy.clawisland.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cutuy.clawisland.MainActivity
import com.cutuy.clawisland.R
import com.cutuy.clawisland.models.AgentStatus
import com.cutuy.clawisland.models.SessionEvent
import com.cutuy.clawisland.models.SessionInfo
import com.cutuy.clawisland.models.SessionsPayload
import com.cutuy.clawisland.notification.PixelLiveUpdateHelper
import com.cutuy.clawisland.notification.SessionEventNotificationIdentity
import com.cutuy.clawisland.notification.StandardNotificationHelper
import com.cutuy.clawisland.notification.VivoSuperXHelper
import com.cutuy.clawisland.overlay.FloatingIslandController
import com.cutuy.clawisland.push.MonitorPreferences
import com.cutuy.clawisland.push.PushRegistrationClient
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class ClawIslandService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventSource: EventSource? = null
    private var currentPayload = SessionsPayload()
    private var isFirstNotification = true

    // Notification throttle: the notification center is re-posted at most every
    // ~10s; the in-app StateFlow (ClawIslandStore) updates on EVERY SSE tick (2s).
    private var lastNotifyAt = 0L
    private var lastSseAt = 0L

    // Lifecycle event notifications: one-shot, auto-cancel, on a dedicated
    // HIGH-importance channel. Dedupe by event id so reconnects / redeliveries
    // never double-notify (ring of the last 50 seen ids).
    private val seenEventIds = ArrayDeque<String>(50)
    private val seenEventIdsSet = HashSet<String>(64)
    private var lastEventSeq = 0L

    // Reconnect discipline (v2.2): OkHttp can invoke BOTH onClosed and
    // onFailure for one EventSource, and canceling a superseded source also
    // fires onClosed — naive per-callback scheduling accumulated multiple
    // independent reconnect loops (observed: a new connection every ~1.2s).
    // Guards: (a) a monotonic connect epoch — callbacks from superseded
    // sources are ignored; (b) at most ONE pending reconnect coroutine;
    // (c) exponential backoff 5s → 30s, reset only after a connection has
    // delivered data for >10s (i.e. it was genuinely healthy).
    private val connectEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    private var reconnectJob: Job? = null
    private var backoffMs = 5_000L
    private var connectionOpenedAt = 0L

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Floating overlay (option B) — independent of notification rendering path.
    // Works on both Pixel and Vivo as long as SYSTEM_ALERT_WINDOW is granted.
    private var floatingIsland: FloatingIslandController? = null

    // 30s heartbeat fallback: re-posts the notification when SSE has gone
    // silent (liveness signal) OR when nothing has re-posted it in >30s
    // (during idle the server sends ~80-byte heartbeats and the SSE-driven
    // update path is skipped, so this keeps the visible setWhen() advancing).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val sseAge = now - lastSseAt
            val notifyAge = now - lastNotifyAt
            if (!sseTimeKnown() || sseAge > HEARTBEAT_INTERVAL_MS || notifyAge > HEARTBEAT_INTERVAL_MS) {
                Log.d(TAG, "Heartbeat: re-posting notification (sseAge=${sseAge}ms, notifyAge=${notifyAge}ms)")
                updateNotification(currentPayload, force = true)
            }
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun sseTimeKnown(): Boolean = lastSseAt > 0L

    companion object {
        private const val TAG = "ClawIslandService"
        const val ACTION_START = "com.cutuy.clawisland.action.START"
        const val ACTION_STOP = "com.cutuy.clawisland.action.STOP"
        const val ACTION_SIMULATE = "com.cutuy.clawisland.action.SIMULATE"
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_SIM_PAYLOAD = "extra_sim_payload"
        const val DEFAULT_SERVER_URL = "http://100.64.0.4:18818"
        const val SESSION_EVENTS_CHANNEL_ID = "session_events"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val NOTIFY_THROTTLE_MS = 10_000L
    }

    override fun onCreate() {
        super.onCreate()
        lastEventSeq = MonitorPreferences.lastEventSeq(this)
        if (isVivoDevice()) {
            VivoSuperXHelper.createNotificationChannel(this)
        } else {
            PixelLiveUpdateHelper.createNotificationChannel(this)
        }
        createSessionEventsChannel()
        floatingIsland = FloatingIslandController(this).also {
            Log.d(TAG, "FloatingIsland ready, canShow=${it.canShow()}")
        }
    }

    /** Dedicated HIGH-importance channel for one-shot lifecycle event pings. */
    private fun createSessionEventsChannel() {
        try {
            val channel = NotificationChannel(
                SESSION_EVENTS_CHANNEL_ID,
                "Session Events",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "One-shot notifications when opencode sessions start, resume, finish or exit"
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        } catch (e: Throwable) {
            Log.e(TAG, "createSessionEventsChannel failed (non-fatal)", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            MonitorPreferences.setMonitorEnabled(this, false)
            PushRegistrationClient.unregister(this)
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
                val sessions = json.decodeFromString<SessionsPayload>(payload)
                isFirstNotification = true
                val initial = try {
                    buildSessionsNotification(sessions, isUpdate = false)
                } catch (e: Throwable) {
                    Log.e(TAG, "Sim notification build failed; using standard fallback", e)
                    StandardNotificationHelper.buildSessionsNotification(this, sessions)
                }
                startForeground(notificationId(), initial)
                currentPayload = sessions
                ClawIslandStore.payload.value = sessions
                handleLifecycleEvents(sessions.events)
                updateNotification(sessions, force = true)
                Log.d(TAG, "Sim sessions posted: ${sessions.active.size} active")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse SIMULATE payload: $payload", e)
            }
            return START_STICKY
        }

        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: DEFAULT_SERVER_URL
        MonitorPreferences.setServerUrl(this, serverUrl)
        MonitorPreferences.setMonitorEnabled(this, true)
        refreshPushRegistration()

        // Start Foreground Notification immediately. Both helpers are now
        // self-defensive (never throw), but wrap in try/catch anyway — if
        // either path ever does throw, fall back to StandardNotificationHelper
        // rather than killing the service process.
        val initialNotification = try {
            buildSessionsNotification(currentPayload, isUpdate = false)
        } catch (e: Throwable) {
            Log.e(TAG, "Initial notification build failed; using standard fallback", e)
            StandardNotificationHelper.buildSessionsNotification(this, currentPayload)
        }
        startForeground(notificationId(), initialNotification)

        connectToSse(serverUrl)
        mainHandler.removeCallbacks(heartbeatTick)
        mainHandler.postDelayed(heartbeatTick, HEARTBEAT_INTERVAL_MS)
        Log.d(TAG, "Service started — SSE connect + heartbeat scheduled")

        return START_STICKY
    }

    private fun buildSessionsNotification(payload: SessionsPayload, isUpdate: Boolean) =
        if (isVivoDevice()) {
            VivoSuperXHelper.buildSessionsNotification(this, payload, isUpdate = isUpdate)
        } else {
            PixelLiveUpdateHelper.buildSessionsNotification(this, payload, isUpdate = isUpdate)
        }

    private fun connectToSse(serverUrl: String) {
        val epoch = connectEpoch.incrementAndGet()
        eventSource?.cancel()
        reconnectJob = null

        val base = if (serverUrl.endsWith("/")) "${serverUrl}api/sessions/events" else "$serverUrl/api/sessions/events"
        // Per-connection cursor: backfill anything missed since the last seen
        // event seq (kept in memory for the service lifetime).
        val endpoint = requireNotNull(base.toHttpUrlOrNull()) { "Invalid server URL: $serverUrl" }
            .newBuilder()
            .addQueryParameter("device_id", MonitorPreferences.deviceId(this))
            .apply { if (lastEventSeq > 0) addQueryParameter("after", lastEventSeq.toString()) }
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                connectionOpenedAt = System.currentTimeMillis()
                Log.d(TAG, "SSE Connection Opened to $endpoint (epoch=$epoch)")
                ClawIslandStore.connected.value = true
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                lastSseAt = System.currentTimeMillis()
                ClawIslandStore.connected.value = true
                // A connection that has streamed data for >10s was healthy —
                // future reconnects may start from the short backoff again.
                if (System.currentTimeMillis() - connectionOpenedAt > 10_000L) {
                    backoffMs = 5_000L
                }
                try {
                    val payload = json.decodeFromString<SessionsPayload>(data)
                    // Server seq reset detection: if our cursor is ahead of the
                    // server's max seq (bridge restarted with a wiped event
                    // state), re-sync the cursor so ?after stops clamping.
                    if (payload.maxSeq > 0 && lastEventSeq > payload.maxSeq) {
                        lastEventSeq = payload.maxSeq
                        MonitorPreferences.setLastEventSeq(this@ClawIslandService, lastEventSeq)
                    }
                    if (payload.events.isNotEmpty()) {
                        handleLifecycleEvents(payload.events)
                    }
                    if (payload.delta) {
                        ClawIslandStore.payload.value = payload
                        updateNotification(payload)
                    }
                    // delta=false heartbeat: content unchanged server-side —
                    // keep the last full payload and the current notification;
                    // the 1s client-side tickers keep elapsed/staleness live.
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse SSE payload: ${data.take(200)}", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // Ignore callbacks from superseded connections — the newer
                // connectToSse() owns the stream now.
                if (connectEpoch.get() != epoch) return
                Log.w(TAG, "SSE Connection Closed (epoch=$epoch). Reconnecting with backoff...")
                ClawIslandStore.connected.value = false
                scheduleReconnect(serverUrl, epoch)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (connectEpoch.get() != epoch) return
                Log.e(TAG, "SSE Connection Error: ${t?.message} (epoch=$epoch). Reconnecting with backoff...")
                ClawIslandStore.connected.value = false
                scheduleReconnect(serverUrl, epoch)
            }
        }

        eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, listener)
    }

    /** At most ONE pending reconnect, only for the current connection epoch,
     *  with exponential backoff (5s → 30s max). */
    private fun scheduleReconnect(serverUrl: String, epoch: Int) {
        if (reconnectJob?.isActive == true) return
        val delayMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        Log.d(TAG, "SSE reconnect scheduled in ${delayMs}ms (next backoff ${backoffMs}ms)")
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (connectEpoch.get() == epoch) {
                connectToSse(serverUrl)
            }
        }
    }

    /** One-shot lifecycle event notifications (dedupe, collapse spam, store). */
    private fun handleLifecycleEvents(events: List<SessionEvent>) {
        if (events.isEmpty()) return
        ClawIslandStore.pushEvents(events)

        val newestSeq = events.maxOfOrNull { it.seq } ?: 0L
        if (newestSeq > lastEventSeq) {
            lastEventSeq = newestSeq
            MonitorPreferences.setLastEventSeq(this, lastEventSeq)
        }

        val fresh = events.filter { evt ->
            if (evt.id.isNotEmpty() && (evt.id in seenEventIdsSet || MonitorPreferences.hasSeenEvent(this, evt.id))) {
                false
            } else {
                if (evt.id.isNotEmpty()) {
                    seenEventIds.addLast(evt.id)
                    seenEventIdsSet.add(evt.id)
                    if (seenEventIds.size > 50) {
                        seenEventIds.pollFirst()?.let(seenEventIdsSet::remove)
                    }
                    MonitorPreferences.markEventSeen(this, evt.id)
                }
                true
            }
        }
        if (fresh.isEmpty()) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fresh.forEach { evt ->
            val s = evt.session
            val (title, text) = when (evt.type) {
                "session_started" -> "🆕 Session started · ${s?.repo ?: "?"}" to
                    "${s?.title ?: "Unknown"} (${s?.model ?: "?"})"
                "session_resumed" -> "▶️ Session resumed · ${s?.repo ?: "?"}" to
                    "${s?.title ?: "Unknown"} (${s?.model ?: "?"})"
                "session_finished" -> "✅ Session finished · ${s?.repo ?: "?"}" to
                    "${s?.title ?: "Unknown"} · ⏱ ${formatElapsedShort(s?.elapsedSeconds ?: 0)}"
                "session_exited" -> "⚠️ Session exited · ${s?.repo ?: "?"}" to
                    "${s?.title ?: "Unknown"} · ⏱ ${formatElapsedShort(s?.elapsedSeconds ?: 0)}"
                else -> "Session event" to evt.type
            }
            val notificationId = SessionEventNotificationIdentity.notificationId(s?.id, evt.id)
            manager.notify(notificationId, buildEventNotification(title, text, evt.ts, notificationId))
        }
    }

    private fun refreshPushRegistration() {
        if (FirebaseApp.getApps(this).isEmpty()) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { PushRegistrationClient.register(this, it) }
            .addOnFailureListener { Log.w(TAG, "FCM token unavailable: ${it.message}") }
    }

    private fun formatElapsedShort(seconds: Int): String {
        val m = seconds / 60
        val h = m / 60
        return when {
            h > 0 -> "${h}h ${m % 60}m"
            m > 0 -> "${m}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    /** Per-session, auto-cancel notification on the dedicated session_events channel. */
    private fun buildEventNotification(title: String, text: String, whenTs: Long, notificationId: Int): android.app.Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SESSION_EVENTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claw_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup(SessionEventNotificationIdentity.GROUP_KEY)
            .setWhen(if (whenTs > 0) whenTs else System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(payload: SessionsPayload, force: Boolean = false) {
        currentPayload = payload

        // During server-side heartbeat periods the payload's elapsed fields
        // are frozen (they're excluded from the change hash) — re-derive them
        // from wall-clock drift so the notification/overlay stay live.
        val fresh = freshenElapsed(payload)

        // Floating overlay mirrors every update (never throttled — it's UI).
        floatingIsland?.let { overlay ->
            runCatching { overlay.show(toAgentStatus(fresh)) }
                .onFailure { Log.w(TAG, "FloatingIsland show failed: ${it.message}") }
        }

        val now = System.currentTimeMillis()
        if (!force && !isFirstNotification && now - lastNotifyAt < NOTIFY_THROTTLE_MS) {
            return
        }
        isFirstNotification = false
        lastNotifyAt = now

        val isUpdate = true
        val notification = try {
            buildSessionsNotification(fresh, isUpdate = isUpdate)
        } catch (e: Exception) {
            Log.w(TAG, "Notification build fallback: ${e.message}")
            StandardNotificationHelper.buildSessionsNotification(this, fresh)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = notificationId()
        Log.d(TAG, "notify(id=$id, active=${fresh.active.size}, recent=${fresh.recent.size})")
        notificationManager.notify(id, notification)
    }

    /** Re-derive active-session elapsed from the (frozen) payload baseline +
     *  wall-clock drift. Never stored — computed fresh on every use. */
    private fun freshenElapsed(payload: SessionsPayload): SessionsPayload {
        if (payload.active.isEmpty() || payload.nowTs <= 0) return payload
        val driftSec = (System.currentTimeMillis() - payload.nowTs) / 1000L
        if (driftSec <= 0) return payload
        return payload.copy(active = payload.active.map { s ->
            if (s.isActive && s.elapsedSeconds >= 0) {
                s.copy(
                    elapsedSeconds = s.elapsedSeconds + driftSec.toInt(),
                    formattedTime = formatDuration(s.elapsedSeconds + driftSec.toInt())
                )
            } else s
        })
    }

    private fun formatDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val m = s / 60
        val h = m / 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m % 60, s % 60)
        else String.format("%02d:%02d", m, s % 60)
    }

    /** Derives a legacy AgentStatus for the floating overlay from the sessions payload. */
    private fun toAgentStatus(payload: SessionsPayload): AgentStatus {
        val top = payload.active.firstOrNull() ?: return AgentStatus(
            active = false,
            agent = "OpenClaw",
            status = "idle",
            stateType = "idle",
            title = "All Agents Idle",
            detail = "Standing by for instructions",
            model = "ox-alpha-free"
        )
        return AgentStatus(
            active = true,
            agent = if (top.source == "codex") "Codex" else "OpenCode",
            status = "running",
            stateType = "coding",
            title = top.title,
            detail = top.lastActivity.ifEmpty { top.repo },
            model = top.model.ifEmpty { if (top.source == "codex") "Codex" else "glm-5.3" },
            elapsedSeconds = top.elapsedSeconds,
            formattedTime = top.formattedTime,
            procCount = top.procCount
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        connectEpoch.incrementAndGet()  // invalidate all listener callbacks
        eventSource?.cancel()
        reconnectJob?.cancel()
        serviceScope.cancel()
        mainHandler.removeCallbacks(heartbeatTick)
        ClawIslandStore.connected.value = false
        floatingIsland?.let { runCatching { it.hide() } }
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
