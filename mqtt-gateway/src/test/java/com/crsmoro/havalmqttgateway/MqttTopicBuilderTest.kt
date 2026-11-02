package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MqttTopicBuilderTest {
    @Test
    fun buildsTopicFromConfiguredPrefixAndCanonicalKey() {
        assertEquals(
            "haval/telemetry/gwmbrasil_1hgcm82633a004352/car.basic.vehicle_speed",
            MqttTopicBuilder.build(
                "/haval/telemetry/gwmbrasil_1hgcm82633a004352/",
                "car.basic.vehicle_speed"
            )
        )
    }

    @Test
    fun normalizesAndUsesConfiguredPrefixAsIdentity() {
        assertEquals(
            "haval/telemetry/garage",
            MqttTopicBuilder.normalizePrefix(" /haval/telemetry/garage/ ")
        )
    }

    @Test
    fun rejectsWildcardsAndEmptySegments() {
        assertNull(MqttTopicBuilder.build("haval/+/telemetry", "car.basic.vehicle_speed"))
        assertNull(MqttTopicBuilder.build("haval//telemetry", "car.basic.vehicle_speed"))
        assertNull(MqttTopicBuilder.build("haval/telemetry", "car.basic/#"))
    }

    @Test
    fun rejectsBlankFields() {
        assertNull(MqttTopicBuilder.build("", "car.basic.vehicle_speed"))
        assertNull(MqttTopicBuilder.build("haval/telemetry", " "))
    }
}
