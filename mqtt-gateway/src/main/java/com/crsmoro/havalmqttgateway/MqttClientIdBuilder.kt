package com.crsmoro.havalmqttgateway

/** Stable MQTT session identities distinguish both prefixes and installations. */
internal object MqttClientIdBuilder {
    fun fromInstallation(prefix: String, instanceId: String): String {
        val input = "$prefix\u0000$instanceId".toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input)
        return "haval-gateway-" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun fromTopicPrefix(prefix: String): String {
        val normalized = MqttTopicBuilder.normalizePrefix(prefix) ?: "haval/telemetry"
        val encoded = buildString {
            normalized.forEach { c ->
                when (c) {
                    '_' -> append("_u")
                    '/' -> append("_s")
                    '.' -> append("_d")
                    else -> append(c)
                }
            }
        }
        return "haval-gateway-$encoded"
    }
}
