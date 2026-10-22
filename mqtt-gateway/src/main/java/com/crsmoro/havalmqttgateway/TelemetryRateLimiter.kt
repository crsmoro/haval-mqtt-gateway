package com.crsmoro.havalmqttgateway

/** Limits work from the unauthenticated local broadcast input. */
internal class TelemetryRateLimiter(
    private val maxEvents: Int,
    private val windowMs: Long
) {
    private var windowStartMs = Long.MIN_VALUE
    private var eventCount = 0

    init {
        require(maxEvents > 0)
        require(windowMs > 0)
    }

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (windowStartMs == Long.MIN_VALUE || nowMs < windowStartMs || nowMs - windowStartMs >= windowMs) {
            windowStartMs = nowMs
            eventCount = 0
        }
        if (eventCount >= maxEvents) return false
        eventCount++
        return true
    }
}
