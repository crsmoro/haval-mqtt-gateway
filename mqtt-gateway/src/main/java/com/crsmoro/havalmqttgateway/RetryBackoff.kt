package com.crsmoro.havalmqttgateway

internal class RetryBackoff(
    private val initialDelayMs: Long,
    private val maxDelayMs: Long
) {
    private var currentDelayMs = initialDelayMs

    init {
        require(initialDelayMs > 0)
        require(maxDelayMs >= initialDelayMs)
    }

    @Synchronized
    fun nextDelay(): Long {
        val result = currentDelayMs
        currentDelayMs = (currentDelayMs * 2).coerceAtMost(maxDelayMs)
        return result
    }

    @Synchronized
    fun reset() {
        currentDelayMs = initialDelayMs
    }
}
