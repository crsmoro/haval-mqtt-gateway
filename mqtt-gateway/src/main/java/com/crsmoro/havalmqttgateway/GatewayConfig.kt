package com.crsmoro.havalmqttgateway

import android.content.Context

data class GatewayConfig(
    val enabled: Boolean,
    val autoStart: Boolean,
    val brokerUri: String,
    val username: String,
    val topicPrefix: String,
    val publishHomeAssistant: Boolean = false,
    val clientInstanceId: String = ""
)

class GatewayConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secrets = EncryptedSecretStore(appContext)
    private val instanceId: String by lazy { synchronized(appContext) {
        prefs.getString(KEY_INSTANCE_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            check(prefs.edit().putString(KEY_INSTANCE_ID, it).commit())
        }
    } }

    fun load(): GatewayConfig = GatewayConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        autoStart = prefs.getBoolean(KEY_AUTO_START, false),
        brokerUri = prefs.getString(KEY_BROKER_URI, "ssl://") ?: "ssl://",
        username = prefs.getString(KEY_USERNAME, "") ?: "",
        topicPrefix = VehicleTopicIdentity.migratePrefix(prefs.getString(KEY_TOPIC_PREFIX, DEFAULT_TOPIC_PREFIX) ?: DEFAULT_TOPIC_PREFIX),
        publishHomeAssistant = prefs.getBoolean(KEY_HOME_ASSISTANT, false),
        clientInstanceId = instanceId
    )

    fun save(config: GatewayConfig, password: String?): Boolean {
        if (password != null && !secrets.save(password)) return false
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putBoolean(KEY_AUTO_START, config.autoStart)
            .putString(KEY_BROKER_URI, config.brokerUri.trim())
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_TOPIC_PREFIX, config.topicPrefix.trim().trim('/'))
            .putBoolean(KEY_HOME_ASSISTANT, config.publishHomeAssistant)
            .apply()
        return true
    }

    fun password(): String? = secrets.read()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFERENCES = "mqtt_gateway"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_BROKER_URI = "broker_uri"
        private const val KEY_USERNAME = "username"
        private const val KEY_TOPIC_PREFIX = "topic_prefix"
        private const val KEY_HOME_ASSISTANT = "publish_home_assistant"
        private const val KEY_INSTANCE_ID = "mqtt_instance_id"
        const val DEFAULT_TOPIC_PREFIX = "gwm"
    }
}
