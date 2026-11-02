package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MetadataIconsTest {
    @Test fun selectedSignalsHaveMaterialIconsAppropriateToTheirFamilies() {
        val samples = mapOf("car.basic.vehicle_speed" to "mdi:speedometer", "car.basic.battery_voltage" to "mdi:car-battery", "car.hvac.ac_enable" to "mdi:air-conditioner", "car.dms.frs.driver_age" to "mdi:account", "car.ipk_info.fcw_warning" to "mdi:alert", "car.ev_info.charging_state" to "mdi:ev-station", "haval.power.ice" to "mdi:engine")
        for ((key, icon) in samples) {
            val payload = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            assertEquals(key, icon, payload.get("icon")?.asString)
        }
        for (key in IndependentVehicleState.keys + listOf("haval.power.ice", "haval.power.flow")) {
            val payload = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            assertTrue(key, payload.get("icon")?.asString?.startsWith("mdi:") == true)
        }
        val unknown = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", "car.unknown.value")!!.payload).asJsonObject
        assertFalse(unknown.has("icon"))
    }
}
