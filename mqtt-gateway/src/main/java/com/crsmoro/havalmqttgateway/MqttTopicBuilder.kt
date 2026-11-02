package com.crsmoro.havalmqttgateway

/** Builds stable publish-only topics and rejects MQTT wildcard characters in user input. */
object MqttTopicBuilder {
    private val safeSegment = Regex("^[A-Za-z0-9_.-]+$")

    fun normalizePrefix(prefix: String): String? {
        val normalizedPrefix = prefix.trim().trim('/')
        if (normalizedPrefix.isEmpty()) return null

        val prefixSegments = normalizedPrefix.split('/')
        if (prefixSegments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." ||
                    !safeSegment.matches(segment) || '+' in segment || '#' in segment
            }
        ) return null
        return normalizedPrefix
    }

    fun build(prefix: String, key: String): String? {
        val normalizedPrefix = normalizePrefix(prefix) ?: return null
        if (!TelemetryKeyPolicy.isPublishable(key)) return null

        if ('/' in key || '+' in key || '#' in key) return null
        return "$normalizedPrefix/$key"
    }
}
