package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishRetryTrackerTest {
    @Test
    fun dropsRepeatedFailureAfterConfiguredAttempts() {
        val tracker = PublishRetryTracker(maxAttempts = 3)

        assertFalse(tracker.recordFailure(10))
        assertFalse(tracker.recordFailure(10))
        assertTrue(tracker.recordFailure(10))
    }

    @Test
    fun newerSequenceStartsANewFailureBudget() {
        val tracker = PublishRetryTracker(maxAttempts = 2)

        assertFalse(tracker.recordFailure(10))
        assertFalse(tracker.recordFailure(11))
        assertTrue(tracker.recordFailure(11))
    }
}
