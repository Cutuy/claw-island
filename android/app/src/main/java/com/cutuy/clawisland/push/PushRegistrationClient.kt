package com.cutuy.clawisland.push

import android.content.Context
import android.util.Log
import com.cutuy.clawisland.R
import com.cutuy.clawisland.service.ClawIslandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object PushRegistrationClient {
    private const val TAG = "PushRegistration"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    fun register(context: Context, token: String) {
        if (token.isEmpty() || !MonitorPreferences.monitorEnabled(context)) return
        MonitorPreferences.setFcmToken(context, token)
        scope.launch {
            runCatching {
                val base = MonitorPreferences.serverUrl(context, ClawIslandService.DEFAULT_SERVER_URL).trimEnd('/')
                val body = JSONObject()
                    .put("device_id", MonitorPreferences.deviceId(context))
                    .put("platform", "android")
                    .put("token", token)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val builder = Request.Builder().url("$base/api/push/devices").post(body)
                pairingToken(context).takeIf { it.isNotEmpty() }?.let { builder.header("X-Claw-Pairing-Token", it) }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) error("registration HTTP ${response.code}")
                }
            }.onFailure { Log.w(TAG, "FCM registration failed: ${it.message}") }
        }
    }

    fun unregister(context: Context) {
        scope.launch {
            runCatching {
                val base = MonitorPreferences.serverUrl(context, ClawIslandService.DEFAULT_SERVER_URL).trimEnd('/')
                val deviceId = MonitorPreferences.deviceId(context)
                val builder = Request.Builder().url("$base/api/push/devices/$deviceId").delete()
                pairingToken(context).takeIf { it.isNotEmpty() }?.let { builder.header("X-Claw-Pairing-Token", it) }
                client.newCall(builder.build()).execute().close()
            }.onFailure { Log.w(TAG, "FCM unregister failed: ${it.message}") }
        }
    }

    private fun pairingToken(context: Context): String = runCatching { context.getString(R.string.claw_push_pairing_token) }.getOrDefault("")
}
