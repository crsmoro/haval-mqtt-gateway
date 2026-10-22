package com.crsmoro.havalmqttgateway

import android.content.Context

internal object GatewayStatusStore {
    private const val PREFERENCES = "mqtt_gateway_status"
    private const val KEY_STATUS = "status"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_API_READY = "api_ready"

    fun save(context: Context, status: String, connected: Boolean, apiReady: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .putBoolean(KEY_CONNECTED, connected)
            .putBoolean(KEY_API_READY, apiReady)
            .apply()
    }

    fun load(context: Context, fallback: String): String =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, fallback) ?: fallback
}
