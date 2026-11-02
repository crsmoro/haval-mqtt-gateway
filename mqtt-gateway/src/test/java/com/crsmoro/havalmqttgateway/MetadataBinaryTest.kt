package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MetadataBinaryTest {
    @Test fun hvacFlagsAreBinaryAndUnknownValuesCannotLeaveStaleState() {
        val flags = listOf("ac_enable", "acmax_enable", "anion_enable", "aqs_enable", "auto_enable", "front_defrost_enable", "heating_enable", "rear_defrost_enable", "sync_enable", "setting.auto_defrost_enable", "setting.limit_enable")
        for (flag in flags) {
            val key = "car.hvac.$flag"
            val msg = HomeAssistantDiscovery.build("VIN123", key)!!
            val p = JsonParser.parseString(msg.payload).asJsonObject
            assertTrue(key, msg.topic.startsWith("homeassistant/binary_sensor/"))
            assertEquals("1", p.get("payload_on").asString)
            assertEquals("0", p.get("payload_off").asString)
            assertEquals("{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ v | string if (v | string) in ['0', '1'] else none }}", p.get("value_template").asString)
            assertEquals("gwm/haval/VIN123/$key", p.get("state_topic").asString)
        }
        for (key in listOf("car.hvac.power_mode", "car.hvac.blower_mode", "car.basic.door_status", "car.hvac.unknown_enable")) {
            assertTrue(key, HomeAssistantDiscovery.build("VIN123", key)!!.topic.startsWith("homeassistant/sensor/"))
        }
    }
}
