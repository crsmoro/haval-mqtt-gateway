package com.crsmoro.havalmqttgateway

internal data class TelemetryBroadcastEvent(val key: String, val value: String)

internal object TelemetryBroadcastParser {
    fun parse(key: Any?, value: Any?): TelemetryBroadcastEvent? {
        val typedKey = key as? String ?: return null
        val typedValue = value as? String ?: return null
        return TelemetryBroadcastEvent(typedKey, typedValue)
    }
}
