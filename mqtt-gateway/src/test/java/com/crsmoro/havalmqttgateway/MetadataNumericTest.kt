package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MetadataNumericTest {
    @Test fun engineSpeedRejectsOutOfRangeAndRoundedSentinelValuesAsUnknown() {
        val payload = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", "car.basic.engine_speed")!!.payload).asJsonObject
        assertEquals(
            "{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ v | float if is_number(v) and 0 <= (v | float) <= 8000 and (v | float | round(0)) not in [1023, 2047, 65535] else none }}",
            payload.get("value_template").asString
        )
    }
    @Test fun numericTemplatesGuardSentinelsAndPercentageBoundsWithoutRejectingSignedCurrent() {
        fun template(key: String) = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload)
            .asJsonObject.get("value_template").asString
        val percent = template("car.basic.remain_fuel_percentage")
        assertTrue(percent.contains("0 <= (v | float) <= 100"))
        val speed = template("car.basic.vehicle_speed")
        assertTrue(speed.contains("(v | float) >= 0"))
        val current = template("car.ev_info.cur_charge_current")
        assertFalse(current.contains(">= 0"))
        assertTrue(current.contains("is_number(v)"))
    }
    @Test fun obviousPercentagesExposePercentUnitsWithoutInventingStateClass() {
        val expected = mapOf(
            "car.basic.remain_fuel_percentage" to null,
            "car.ev_info.battery_charge_percentage" to "battery",
            "car.ev_info.cur_battery_power_percentage" to "battery",
            "car.ev_info.soc_of_battery" to "battery"
        )
        for ((key, deviceClass) in expected) {
            val p = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            assertEquals(key, "%", p.get("unit_of_measurement")?.asString)
            if (deviceClass == null) assertFalse(key, p.has("device_class")) else assertEquals(key, deviceClass, p.get("device_class").asString)
            assertFalse(key, p.has("state_class"))
            assertEquals(key, "{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ v | float if is_number(v) and 0 <= (v | float) <= 100 else none }}", p.get("value_template").asString)
        }
    }

    @Test fun energyOutputPercentageAllowsSignedRegeneration() {
        val p = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", "car.ev_info.energy_output_percentage")!!.payload).asJsonObject
        assertEquals("%", p.get("unit_of_measurement").asString)
        assertFalse(p.has("device_class"))
        assertEquals("{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ v | float if is_number(v) else none }}", p.get("value_template").asString)
    }

    @Test fun rangeSensorsExposeKilometersWithNonnegativeNumericStates() {
        for (key in listOf("car.basic.remain_odometer", "car.ev_info.electric_mode_remain_odometer", "car.ev_info.fuel_mode_remain_odometer")) {
            val p = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            assertEquals(key, "distance", p.get("device_class")?.asString)
            assertEquals(key, "km", p.get("unit_of_measurement")?.asString)
            assertEquals(key, "{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ v | float if is_number(v) and (v | float) >= 0 else none }}", p.get("value_template").asString)
            assertFalse(key, p.has("state_class"))
        }
    }

    @Test fun signedSteeringAngleAndTractionBatteryCurrentRemainNumeric() {
        val specs = mapOf("car.basic.steering_wheel_angle" to (null to "°"), "car.ev_info.power_battery_current" to ("current" to "A"))
        for ((key, spec) in specs) {
            val p = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            if (spec.first == null) assertFalse(key, p.has("device_class")) else assertEquals(key, spec.first, p.get("device_class").asString)
            assertEquals(key, spec.second, p.get("unit_of_measurement")?.asString)
            assertEquals(key, "{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ v | float if is_number(v) else none }}", p.get("value_template").asString)
            assertFalse(key, p.has("state_class"))
        }
    }

    @Test fun measuredSensorsValidateNumbersInsteadOfConvertingInvalidValuesToZero() {
        val keys = listOf("car.basic.vehicle_speed", "car.basic.total_odometer", "car.basic.battery_voltage", "car.ev_info.power_battery_voltage", "car.ev_info.cur_charge_current", "car.basic.engine_speed")
        for (key in keys) {
            val p = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            val range = when (key) {
                "car.ev_info.cur_charge_current" -> ""
                "car.basic.engine_speed" -> " and 0 <= (v | float) <= 8000 and (v | float | round(0)) not in [1023, 2047, 65535]"
                else -> " and (v | float) >= 0"
            }
            assertEquals(key, "{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ v | float if is_number(v)$range else none }}", p.get("value_template").asString)
            assertFalse(key, p.has("state_class"))
        }
        for (key in listOf("car.basic.inside_temp", "car.hvac.driver_temperature", "car.basic.instant_fuel_consumption")) {
            val p = JsonParser.parseString(HomeAssistantDiscovery.build("VIN123", key)!!.payload).asJsonObject
            assertFalse(key, p.has("unit_of_measurement"))
            assertEquals("{{ value_json.raw_value }}", p.get("value_template").asString)
        }
    }
}
