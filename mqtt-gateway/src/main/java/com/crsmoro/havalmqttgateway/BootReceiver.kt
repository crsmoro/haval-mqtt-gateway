package com.crsmoro.havalmqttgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = GatewayConfigStore(context).load()
        if (!config.enabled || !config.autoStart) return
        val serviceIntent = Intent(context, GatewayService::class.java)
            .setAction(GatewayService.ACTION_START)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
