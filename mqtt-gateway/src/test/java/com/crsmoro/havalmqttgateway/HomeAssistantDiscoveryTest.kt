package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantDiscoveryTest {
    @Test fun everySelectedReadOnlyObservationCanHaveAnEntity() {
        (IndependentVehicleState.keys + listOf("haval.power.ice", "haval.power.flow")).forEach { key ->
            assertTrue("No discovery for $key", HomeAssistantDiscovery.build("1HGCM82633A004352", key) != null)
        }
    }
    @Test
    fun inferredCombustionUsesBinarySensorWithExplicitPayloads() {
        val message = HomeAssistantDiscovery.build("VIN123", "haval.power.ice")!!
        val other = HomeAssistantDiscovery.build("VIN124", "haval.power.ice")!!
        val payload = JsonParser.parseString(message.payload).asJsonObject
        val otherPayload = JsonParser.parseString(other.payload).asJsonObject
        assertTrue(message.topic.startsWith("homeassistant/binary_sensor/"))
        assertEquals("gwm/haval/VIN123/haval.power.ice", payload.get("state_topic").asString)
        assertEquals("{{ value_json.raw_value }}", payload.get("value_template").asString)
        assertEquals("1", payload.get("payload_on").asString)
        assertEquals("0", payload.get("payload_off").asString)
        assertEquals("vin_56494e313233_key_686176616c2e706f7765722e696365", payload.get("unique_id").asString)
        assertTrue(message.topic.endsWith("/vin_56494e313233/key_686176616c2e706f7765722e696365/config"))
        assertFalse(payload.has("device_class"))
        assertNotEquals(payload.get("unique_id").asString, otherPayload.get("unique_id").asString)
        assertTrue(HomeAssistantDiscovery.build("VIN123", "haval.power.ICE")!!.topic.startsWith("homeassistant/sensor/"))
    }

    @Test
    fun documentedNumericSignalsExposeOnlyTheirVerifiedUnitsAndClasses() {
        // Producer: BatteryVoltageFilter documents speed, 12 V and steering angle;
        // TripConsistencyManager reads total odometer in km; carDerivations maps
        // RPM and battery voltage/current to the corresponding display units.
        val expected = mapOf(
            "car.basic.vehicle_speed" to ("speed" to "km/h"),
            "car.basic.total_odometer" to ("distance" to "km"),
            "car.basic.battery_voltage" to ("voltage" to "V"),
            "car.ev_info.power_battery_voltage" to ("voltage" to "V"),
            "car.ev_info.cur_charge_current" to ("current" to "A"),
            "car.basic.engine_speed" to (null to "rpm")
        )
        for ((key, metadata) in expected) {
            val message = HomeAssistantDiscovery.build("VIN123", key)!!
            val payload = JsonParser.parseString(message.payload).asJsonObject
            assertTrue(key, message.topic.startsWith("homeassistant/sensor/"))
            assertEquals(key, "gwm/haval/VIN123/$key", payload.get("state_topic").asString)
            assertEquals(key, metadata.second, payload.get("unit_of_measurement").asString)
            if (metadata.first == null) assertFalse(key, payload.has("device_class"))
            else assertEquals(key, metadata.first, payload.get("device_class").asString)
            assertFalse(key, payload.has("state_class"))
        }
    }

    @Test
    fun emitsReadOnlySensorDiscoveryForAnOemKey() {
        val message = HomeAssistantDiscovery.build("VIN123", "car.basic.left_turn_Indicator_light_status")!!
        val payload = JsonParser.parseString(message.payload).asJsonObject
        assertEquals("gwm/haval/VIN123/car.basic.left_turn_Indicator_light_status", payload.get("state_topic").asString)
        assertEquals("{{ value_json.raw_value }}", payload.get("value_template").asString)
        assertEquals("car.basic.left_turn_Indicator_light_status", payload.get("name").asString)
    }

    @Test
    fun messageRequiresRetainedQosOnePublication() {
        val message = HomeAssistantDiscovery.build("VIN123", "car.foo")!!
        assertEquals(1, message.qos)
        assertTrue(message.retained)
    }

    @Test
    fun stableIdentitiesAreReversibleCollisionFreeAndIndependentOfPrefixes() {
        val original = HomeAssistantDiscovery.build("VIN123", "car.foo_bar")!!
        val same = HomeAssistantDiscovery.build("VIN123", "car.foo_bar", "site/haval", "ha")!!
        val differentCase = HomeAssistantDiscovery.build("VIN123", "car.Foo_bar")!!
        val differentPunctuation = HomeAssistantDiscovery.build("VIN123", "car.foo.bar")!!
        val otherVin = HomeAssistantDiscovery.build("VIN124", "car.foo_bar")!!
        fun json(message: HomeAssistantDiscovery.Message) = JsonParser.parseString(message.payload).asJsonObject
        fun uid(message: HomeAssistantDiscovery.Message) = json(message).get("unique_id").asString
        fun device(message: HomeAssistantDiscovery.Message) = json(message).getAsJsonObject("device").getAsJsonArray("identifiers")[0].asString
        assertEquals(uid(original), uid(same))
        assertEquals(device(original), device(differentCase))
        assertEquals(device(original), device(same))
        assertNotEquals(uid(original), uid(differentCase))
        assertNotEquals(uid(original), uid(differentPunctuation))
        assertNotEquals(uid(original), uid(otherVin))
        assertNotEquals(device(original), device(otherVin))
        assertTrue(original.topic.startsWith("homeassistant/sensor/"))
        assertTrue(same.topic.startsWith("ha/sensor/"))
        assertTrue(original.topic.endsWith("/config"))
        assertEquals("site/haval/VIN123/car.foo_bar", json(same).get("state_topic").asString)
    }

    @Test
    fun unknownValuesDoNotClaimUnitsTypesOrControls() {
        val message = HomeAssistantDiscovery.build("VIN123", "car.oem.Unknown_0")!!
        val payload = JsonParser.parseString(message.payload).asJsonObject
        for (field in listOf("device_class", "state_class", "unit_of_measurement", "command_topic", "payload_on", "payload_off")) {
            assertFalse(field, payload.has(field))
        }
        assertTrue(message.topic.startsWith("homeassistant/sensor/"))
    }

    @Test
    fun ambiguousOrStructuredSignalsRemainGeneric() {
        for (key in listOf("car.basic.engine_state", "car.basic.gear_status", "car.ev_info.charging_state", "car.basic.instant_fuel_consumption", "haval.power.flow")) {
            val message = HomeAssistantDiscovery.build("VIN123", key)!!
            val payload = JsonParser.parseString(message.payload).asJsonObject
            assertTrue(key, message.topic.startsWith("homeassistant/sensor/"))
            for (field in listOf("device_class", "state_class", "unit_of_measurement", "payload_on", "payload_off")) {
                assertFalse("$key: $field", payload.has(field))
            }
        }
    }

    @Test
    fun rejectsUnsafeTopicsAndKnownControlKeys() {
        for (vin in listOf("", "VIN/123", "+", " VIN", ".", "..")) {
            assertNull(vin, HomeAssistantDiscovery.build(vin, "car.basic.engine_speed"))
        }
        for (key in listOf("", "car.+", "car/foo", "car..foo", "car.foo.", "car.foo_action", "car.foo_command", "car.foo_request", "car.foo_oneshot", "car.request_foo", "car.beantechs.sota_start", "car.configure.catapult_start", "car.ipk_setting.drive_info_reset", "car.command_parking_setting.park")) {
            assertNull(key, HomeAssistantDiscovery.build("VIN123", key))
        }
        for (prefix in listOf("", "/", "gwm//haval", "gwm/+/haval", "gwm/../haval")) {
            assertNull(prefix, HomeAssistantDiscovery.build("VIN123", "car.foo", dataPrefix = prefix))
            assertNull(prefix, HomeAssistantDiscovery.build("VIN123", "car.foo", discoveryPrefix = prefix))
        }
    }
}
