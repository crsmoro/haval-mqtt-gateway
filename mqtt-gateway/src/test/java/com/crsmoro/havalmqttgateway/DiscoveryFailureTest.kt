package com.crsmoro.havalmqttgateway

import org.junit.Assert.*
import org.junit.Test

class DiscoveryFailureTest {
    @Test fun failedDiscoveryDoesNotBlockTelemetryOrCauseImmediateRetries() {
        val tracker = DiscoveryDeliveryTracker()
        // Fall back to the old policy so this test reproduces its retry loop before the fix.
        val shouldAttempt = tracker.javaClass.methods.firstOrNull {
            it.name == "shouldAttempt" && it.parameterCount == 3
        }
        fun attempt(now: Long) = shouldAttempt?.invoke(tracker, "ha/config", "payload", now) as? Boolean
            ?: tracker.needs("ha/config", "payload")
        assertTrue(attempt(1_000L))
        val completeAt = tracker.javaClass.methods.firstOrNull {
            it.name == "complete" && it.parameterCount == 4
        }
        if (completeAt == null) tracker.complete("ha/config", "payload", false)
        else completeAt.invoke(tracker, "ha/config", "payload", false, 1_000L)
        // The same queued telemetry must become eligible instead of trying discovery again.
        assertFalse("failure must leave room to publish telemetry", attempt(1_000L))
        assertTrue("failure is not an acknowledgement", tracker.needs("ha/config", "payload"))
    }

    @Test fun cooldownIsGlobalBoundedAndExpiresAtDeadline() {
        val tracker = DiscoveryDeliveryTracker()
        val start = 10_000L
        tracker.complete("ha/one", "p", false, start)
        repeat(10_000) { i ->
            assertFalse(tracker.shouldAttempt("ha/$i", "p", start + i % 59_999))
        }
        assertTrue(tracker.shouldAttempt("ha/one", "p", start + DiscoveryDeliveryTracker.RETRY_DELAY_MS))
        tracker.complete("ha/one", "p", false, start + DiscoveryDeliveryTracker.RETRY_DELAY_MS)
        assertFalse(tracker.shouldAttempt("ha/two", "p", start + DiscoveryDeliveryTracker.RETRY_DELAY_MS))
    }

    @Test fun resetClearsCooldownAndAcknowledgementsForReconnect() {
        val tracker = DiscoveryDeliveryTracker()
        tracker.complete("ha/one", "p", true, 100L)
        tracker.complete("ha/two", "p", false, 100L)
        assertFalse(tracker.shouldAttempt("ha/one", "p", 101L))
        assertFalse(tracker.shouldAttempt("ha/two", "p", 101L))
        tracker.reset()
        assertTrue(tracker.shouldAttempt("ha/one", "p", 101L))
        assertTrue(tracker.shouldAttempt("ha/two", "p", 101L))
        tracker.complete("ha/one", "p", true, 101L)
        assertFalse(tracker.shouldAttempt("ha/one", "p", 102L))
        assertTrue(tracker.shouldAttempt("ha/one", "changed", 102L))
    }

    @Test fun deniedDiscoveryStillDrainsManyTelemetryValues() {
        val tracker = DiscoveryDeliveryTracker()
        var attempts = 0
        val delivered = mutableListOf<Int>()
        val queue = ArrayDeque((0 until 500).toList())
        fun pump() {
            while (queue.isNotEmpty()) {
                val key = queue.first()
                if (tracker.shouldAttempt("ha/$key", "p", 1_000L)) {
                    attempts++
                    tracker.completeAndContinue("ha/$key", "p", false, 1_000L) {
                        // The real service posts a fresh pump after the completion callback.
                    }
                    continue
                }
                delivered.add(queue.removeFirst())
            }
        }
        pump()
        assertEquals(1, attempts)
        assertEquals((0 until 500).toList(), delivered)
        assertTrue(queue.isEmpty())
        assertTrue(tracker.needs("ha/0", "p"))
    }

    @Test fun failedCompletionContinuesTelemetryPump() {
        val tracker = DiscoveryDeliveryTracker()
        var telemetryPumps = 0
        val completion = tracker.javaClass.methods.firstOrNull {
            it.name == "completeAndContinue" && it.parameterCount == 5
        }
        if (completion == null) {
            tracker.complete("homeassistant/config", "payload", false)
            // The old service did not resume the pump on discovery failure.
        } else {
            completion.invoke(tracker, "homeassistant/config", "payload", false, 1_000L, { telemetryPumps++ })
        }
        assertEquals("failed discovery must resume the telemetry pump", 1, telemetryPumps)
    }
}
