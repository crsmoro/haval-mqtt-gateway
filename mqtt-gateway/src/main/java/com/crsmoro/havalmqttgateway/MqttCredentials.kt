package com.crsmoro.havalmqttgateway

internal data class MqttCredentials(val username: String, val password: String?) {
    companion object {
        fun from(username: String, password: String?): MqttCredentials {
            val normalizedUsername = username.trim()
            val usablePassword = password?.takeIf { normalizedUsername.isNotBlank() && it.isNotEmpty() }
            return MqttCredentials(normalizedUsername, usablePassword)
        }
    }
}
