package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryRateLimiterTest {
    @Test
    fun capsAcceptedEventsPerWindowAndResetsForNextWindow() {
        val limiter = TelemetryRateLimiter(maxEvents = 2, windowMs = 1_000)

        assertTrue(limiter.tryAcquire(10))
        assertTrue(limiter.tryAcquire(11))
        assertFalse(limiter.tryAcquire(12))
        assertTrue(limiter.tryAcquire(1_010))
    }

    @Test
    fun clockRollbackStartsANewWindow() {
        val limiter = TelemetryRateLimiter(maxEvents = 1, windowMs = 1_000)
        assertTrue(limiter.tryAcquire(2_000))
        assertFalse(limiter.tryAcquire(2_001))
        assertTrue(limiter.tryAcquire(1_000))
    }
}
