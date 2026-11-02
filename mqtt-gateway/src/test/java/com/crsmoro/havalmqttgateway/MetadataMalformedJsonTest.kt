package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataMalformedJsonTest {
    @Test fun typedStateTemplatesHandleMalformedJsonWithoutKeepingAStaleValue() {
        val keys = listOf("car.hvac.ac_enable", "car.basic.vehicle_speed", "car.hvac.cycle_mode", "haval.power.flow", "haval.power.ice")
        for (key in keys) {
            val payload = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            val template = payload.get("value_template").asString
            assertTrue("$key must safely parse malformed JSON", template.contains("value | from_json({})"))
        }
    }
}
