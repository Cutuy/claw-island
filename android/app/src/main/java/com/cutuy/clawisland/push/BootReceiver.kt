package com.cutuy.clawisland.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cutuy.clawisland.service.ClawIslandService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !MonitorPreferences.monitorEnabled(context)) return
        val service = Intent(context, ClawIslandService::class.java).apply {
            action = ClawIslandService.ACTION_START
            putExtra(
                ClawIslandService.EXTRA_SERVER_URL,
                MonitorPreferences.serverUrl(context, ClawIslandService.DEFAULT_SERVER_URL)
            )
        }
        ContextCompat.startForegroundService(context, service)
    }
}
