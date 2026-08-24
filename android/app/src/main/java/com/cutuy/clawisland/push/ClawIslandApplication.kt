package com.cutuy.clawisland.push

import android.app.Application
import android.util.Log
import com.cutuy.clawisland.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

class ClawIslandApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val values = listOf(
            getString(R.string.firebase_application_id),
            getString(R.string.firebase_api_key),
            getString(R.string.firebase_project_id),
            getString(R.string.firebase_sender_id)
        )
        if (values.any { it.isEmpty() }) {
            Log.i(TAG, "Firebase identifiers absent; SSE-only mode")
            return
        }
        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setApplicationId(values[0])
                        .setApiKey(values[1])
                        .setProjectId(values[2])
                        .setGcmSenderId(values[3])
                        .build()
                )
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { PushRegistrationClient.register(this, it) }
        }.onFailure { Log.w(TAG, "Firebase unavailable; SSE-only mode: ${it.message}") }
    }

    companion object { private const val TAG = "ClawIslandApp" }
}
