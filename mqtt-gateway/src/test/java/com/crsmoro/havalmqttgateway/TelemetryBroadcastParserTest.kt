package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryBroadcastParserTest {
    @Test
    fun acceptsOnlyStringExtras() {
        assertEquals(
            TelemetryBroadcastEvent("car.basic.vehicle_speed", "42"),
            TelemetryBroadcastParser.parse("car.basic.vehicle_speed", "42")
        )
        assertNull(TelemetryBroadcastParser.parse(1, "42"))
        assertNull(TelemetryBroadcastParser.parse("car.basic.vehicle_speed", byteArrayOf(1)))
        assertNull(TelemetryBroadcastParser.parse(null, null))
    }
}
