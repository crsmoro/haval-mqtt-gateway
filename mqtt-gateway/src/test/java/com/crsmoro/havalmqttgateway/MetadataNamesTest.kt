package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MetadataNamesTest {
    @Test fun metadataRejectsDuplicateKeysInsteadOfSilentlyOverwriting() {
        try {
            HomeAssistantEntityMetadata.parseNames("car.foo|Primeiro\ncar.foo|Segundo")
            fail("duplicate metadata key was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("car.foo"))
        }
    }

    @Test fun selectedObservationsHaveDistinctPortugueseNamesAndPreserveIdentity() {
        val keys = IndependentVehicleState.keys + listOf("haval.power.ice", "haval.power.flow")
        assertEquals(keys.size, keys.toSet().size)
        val names = mutableSetOf<String>()
        for (key in keys) {
            val message = HomeAssistantDiscovery.build("VIN123", key)!!
            val payload = JsonParser.parseString(message.payload).asJsonObject
            val name = payload.get("name").asString
            assertFalse("Raw identifier for $key: $name", name.contains('_') || name.contains("car.") || name.contains("haval."))
            assertTrue("Duplicate name for $key: $name", names.add(name))
            assertEquals(key, "gwm/haval/VIN123/$key", payload.get("state_topic").asString)
            assertEquals(1, message.qos)
            assertTrue(message.retained)
        }
        val expected = mapOf("car.basic.vehicle_speed" to "Velocidade do veículo", "car.hvac.ac_enable" to "Ar-condicionado", "car.dms.frs.driver_age" to "Idade do motorista (reconhecimento)", "car.oms.feedback.gender_state" to "Gênero do ocupante (monitoramento)", "haval.power.ice" to "Motor a combustão")
        expected.forEach { (key, name) -> assertEquals(key, name, JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject.get("name").asString) }
    }
}
