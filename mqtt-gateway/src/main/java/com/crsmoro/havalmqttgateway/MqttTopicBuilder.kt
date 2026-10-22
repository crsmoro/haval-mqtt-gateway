package com.crsmoro.havalmqttgateway

/** Builds stable publish-only topics and rejects MQTT wildcard characters in user input. */
object MqttTopicBuilder {
    private val safeDeviceId = Regex("^[A-Za-z0-9._-]{1,96}$")
    private val safeSegment = Regex("^[A-Za-z0-9_.-]+$")

    fun build(prefix: String, vehicleId: String, key: String): String? {
        val normalizedPrefix = prefix.trim().trim('/')
        if (normalizedPrefix.isEmpty() || !safeDeviceId.matches(vehicleId)) return null
        if (!TelemetryKeyPolicy.isPublishable(key)) return null

        val prefixSegments = normalizedPrefix.split('/')
        if (prefixSegments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." ||
                    !safeSegment.matches(segment) || '+' in segment || '#' in segment
            }
        ) return null

        if ('/' in key || '+' in key || '#' in key) return null
        return (prefixSegments + listOf(vehicleId, key)).joinToString("/")
    }
}
