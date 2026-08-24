package com.cutuy.clawisland.push

import android.content.Context
import java.util.UUID

object MonitorPreferences {
    private const val NAME = "claw_island_monitor"
    private const val DEVICE_ID = "device_id"
    private const val LAST_EVENT_SEQ = "last_event_seq"
    private const val SEEN_EVENT_IDS = "seen_event_ids"
    private const val MONITOR_ENABLED = "monitor_enabled"
    private const val SERVER_URL = "server_url"
    private const val FCM_TOKEN = "fcm_token"

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val stored = prefs(context).getString(DEVICE_ID, null)
        if (!stored.isNullOrEmpty()) return stored
        return "android-${UUID.randomUUID()}".also { prefs(context).edit().putString(DEVICE_ID, it).apply() }
    }

    fun lastEventSeq(context: Context): Long = prefs(context).getLong(LAST_EVENT_SEQ, 0L)
    fun setLastEventSeq(context: Context, seq: Long) = prefs(context).edit().putLong(LAST_EVENT_SEQ, seq).apply()

    fun hasSeenEvent(context: Context, id: String): Boolean = id.isNotEmpty() && seenIds(context).contains(id)

    fun markEventSeen(context: Context, id: String) {
        if (id.isEmpty()) return
        val ids = seenIds(context).toMutableList().apply {
            remove(id)
            add(id)
        }.takeLast(50).toSet()
        prefs(context).edit().putStringSet(SEEN_EVENT_IDS, ids).commit()
    }

    fun monitorEnabled(context: Context): Boolean = prefs(context).getBoolean(MONITOR_ENABLED, false)
    fun setMonitorEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(MONITOR_ENABLED, enabled).apply()

    fun serverUrl(context: Context, fallback: String): String = prefs(context).getString(SERVER_URL, fallback) ?: fallback
    fun setServerUrl(context: Context, url: String) = prefs(context).edit().putString(SERVER_URL, url).apply()

    fun fcmToken(context: Context): String = prefs(context).getString(FCM_TOKEN, "") ?: ""
    fun setFcmToken(context: Context, token: String) = prefs(context).edit().putString(FCM_TOKEN, token).apply()

    private fun seenIds(context: Context): Set<String> = prefs(context).getStringSet(SEEN_EVENT_IDS, emptySet()) ?: emptySet()
}
