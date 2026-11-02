package com.crsmoro.havalmqttgateway

/** Main-thread-owned acknowledgement cache, reset on MQTT reconnect. */
class DiscoveryDeliveryTracker {
    private val acknowledged = mutableMapOf<String, String>()
    private var retryAt = 0L
    fun needs(topic: String, payload: String): Boolean = acknowledged[topic] != payload
    fun shouldAttempt(topic: String, payload: String, now: Long): Boolean =
        now >= retryAt && needs(topic, payload)
    fun complete(topic: String, payload: String, success: Boolean) {
        if (success) acknowledged[topic] = payload
    }
    fun complete(topic: String, payload: String, success: Boolean, now: Long) {
        complete(topic, payload, success)
        if (!success) retryAt = now + RETRY_DELAY_MS
    }
    fun completeAndContinue(topic: String, payload: String, success: Boolean, now: Long, continueQueue: () -> Unit) {
        complete(topic, payload, success, now)
        continueQueue()
    }
    fun reset() {
        acknowledged.clear()
        retryAt = 0L
    }
    companion object {
        const val RETRY_DELAY_MS = 60_000L
    }
}
