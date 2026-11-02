package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleIceTelemetryTest {
    @Test
    fun determiningEngineSpeedEmitsIceOn() {
        val ice = VehicleIceTelemetry()
        assertEquals("1", ice.observe("car.basic.engine_speed", "400"))
    }

    @Test fun hysteresisAndResetRequireDeterminingValues() {
        val ice = VehicleIceTelemetry()
        assertNull(ice.observe("car.basic.engine_speed", "250"))
        assertEquals("1", ice.observe("car.basic.engine_speed", "400"))
        assertEquals("1", ice.observe("car.basic.engine_speed", "200"))
        assertEquals("0", ice.observe("car.basic.engine_speed", "199"))
        assertEquals("0", ice.observe("car.basic.engine_speed", "399"))
        ice.clear()
        assertNull(ice.observe("car.basic.engine_speed", "300"))
    }

    @Test fun invalidRpmClearsHysteresisBeforeRecovery() {
        val ice = VehicleIceTelemetry()
        assertEquals("1", ice.observe("car.basic.engine_speed", "500"))
        assertNull(ice.observe("car.basic.engine_speed", "-1"))
        assertNull(ice.observe("car.basic.engine_speed", "300"))
    }

    @Test fun ignoresSentinelsAndInvalidOrUnrelatedInputs() {
        val ice = VehicleIceTelemetry()
        listOf("65535", "1023", "1023.1", "2047", "8001", "NaN", "-1", "n/a").forEach {
            assertNull(ice.observe("car.basic.engine_speed", it))
        }
        assertNull(ice.observe("car.basic.vehicle_speed", "400"))
    }
}
