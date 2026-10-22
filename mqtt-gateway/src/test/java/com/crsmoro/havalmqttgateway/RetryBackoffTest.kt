package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryBackoffTest {
    @Test
    fun increasesDelayUntilAConnectionSucceedsThenResets() {
        val backoff = RetryBackoff(initialDelayMs = 2_000, maxDelayMs = 8_000)

        assertEquals(2_000, backoff.nextDelay())
        assertEquals(4_000, backoff.nextDelay())
        assertEquals(8_000, backoff.nextDelay())
        assertEquals(8_000, backoff.nextDelay())
        backoff.reset()
        assertEquals(2_000, backoff.nextDelay())
    }
}
