package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MqttTopicBuilderTest {
    @Test
    fun buildsPerVehicleTopicFromPrefixAndCanonicalKey() {
        assertEquals(
            "haval/telemetry/vehicle_123/car.basic.vehicle_speed",
            MqttTopicBuilder.build("/haval/telemetry/", "vehicle_123", "car.basic.vehicle_speed")
        )
    }

    @Test
    fun rejectsWildcardsAndEmptySegments() {
        assertNull(MqttTopicBuilder.build("haval/+/telemetry", "vehicle_123", "car.basic.vehicle_speed"))
        assertNull(MqttTopicBuilder.build("haval/telemetry", "vehicle/#", "car.basic.vehicle_speed"))
        assertNull(MqttTopicBuilder.build("haval/telemetry", "vehicle_123", "car.basic/#"))
        assertNull(MqttTopicBuilder.build("haval//telemetry", "vehicle_123", "car.basic.vehicle_speed"))
    }

    @Test
    fun rejectsBlankFields() {
        assertNull(MqttTopicBuilder.build("", "vehicle_123", "car.basic.vehicle_speed"))
        assertNull(MqttTopicBuilder.build("haval/telemetry", "", "car.basic.vehicle_speed"))
        assertNull(MqttTopicBuilder.build("haval/telemetry", "vehicle_123", " "))
    }
}
