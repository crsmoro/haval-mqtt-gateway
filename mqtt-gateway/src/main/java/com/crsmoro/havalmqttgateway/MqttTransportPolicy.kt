package com.crsmoro.havalmqttgateway

/** Prevents MQTT usernames/passwords from being sent over unencrypted TCP. */
internal object MqttTransportPolicy {
    fun credentialsRequireTls(brokerUri: String, username: String, hasPassword: Boolean): Boolean =
        brokerUri.substringBefore("://").equals("tcp", ignoreCase = true) &&
            (username.isNotBlank() || hasPassword)
}
