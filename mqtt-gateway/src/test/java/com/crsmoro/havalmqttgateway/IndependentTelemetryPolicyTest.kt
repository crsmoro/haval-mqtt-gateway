package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentTelemetryPolicyTest {
    @Test
    fun allowsSensitiveVehicleReadingsInMqttTopics() {
        val keys = listOf(
            "car.basic.vin_code",
            "car.oms.feedback.age_state"
        )
        keys.forEach { key ->
            assertTrue("Expected $key to be publishable", TelemetryKeyPolicy.isPublishable(key))
            assertEquals("haval/telemetry/$key", MqttTopicBuilder.build("haval/telemetry", key))
        }
    }
}
