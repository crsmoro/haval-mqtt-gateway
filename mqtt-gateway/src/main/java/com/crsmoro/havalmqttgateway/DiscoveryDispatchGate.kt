package com.crsmoro.havalmqttgateway

/** Serializes discovery opt-out changes with dispatch into the MQTT client. */
object DiscoveryDispatchGate {
    private val lock = Any()

    fun <T> change(action: () -> T): T = synchronized(lock) { action() }

    fun dispatch(allowed: () -> Boolean, action: () -> Unit): Boolean = synchronized(lock) {
        if (!allowed()) return@synchronized false
        action()
        true
    }
}
