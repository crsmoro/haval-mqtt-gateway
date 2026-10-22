package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFeedPolicyTest {
    @Test
    fun acceptsValuesOnlyAfterSupportedApiIsEstablished() {
        assertFalse(TelemetryFeedPolicy.canEnqueue(apiReady = false, sourceVersion = 1, supportedVersion = 1))
        assertFalse(TelemetryFeedPolicy.canEnqueue(apiReady = true, sourceVersion = 2, supportedVersion = 1))
        assertTrue(TelemetryFeedPolicy.canEnqueue(apiReady = true, sourceVersion = 1, supportedVersion = 1))
    }
}
