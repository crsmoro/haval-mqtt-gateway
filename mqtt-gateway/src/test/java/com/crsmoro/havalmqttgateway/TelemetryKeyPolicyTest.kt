package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryKeyPolicyTest {
    @Test
    fun acceptsVehicleSignalsAndDerivedVehiclePower() {
        assertTrue(TelemetryKeyPolicy.isPublishable("car.basic.vehicle_speed"))
        assertTrue(TelemetryKeyPolicy.isPublishable("car.ev_info.cur_battery_power_percentage"))
        assertTrue(TelemetryKeyPolicy.isPublishable("haval.power.flow"))
    }

    @Test
    fun excludesAppSystemAndVehicleCommandsButAllowsSensitiveReadings() {
        assertFalse(TelemetryKeyPolicy.isPublishable("app.phone.caller_number"))
        assertFalse(TelemetryKeyPolicy.isPublishable("app.navigation.directions"))
        assertFalse(TelemetryKeyPolicy.isPublishable("sys.settings.display.brightness"))
        assertTrue(TelemetryKeyPolicy.isPublishable("car.basic.vin_code"))
        assertTrue(TelemetryKeyPolicy.isPublishable("car.dms.frs.gaze_area"))
        assertTrue(TelemetryKeyPolicy.isPublishable("car.frs_setting.driver_age"))
        assertFalse(TelemetryKeyPolicy.isPublishable("car.basic.door_control_action"))
        assertFalse(TelemetryKeyPolicy.isPublishable("car.hvac.request_hvac_info"))
    }

    @Test
    fun rejectsUnknownOrMalformedKeys() {
        assertFalse(TelemetryKeyPolicy.isPublishable("unknown.speed"))
        assertFalse(TelemetryKeyPolicy.isPublishable("car.basic."))
        assertFalse(TelemetryKeyPolicy.isPublishable(null))
        assertFalse(TelemetryKeyPolicy.isPublishable("car.basic.vehicle_speed/#"))
        assertFalse(TelemetryKeyPolicy.isPublishable(" CAR.BASIC.VEHICLE_SPEED"))
    }

    @Test
    fun rejectsBlankOrOversizedBroadcastValues() {
        assertTrue(TelemetryKeyPolicy.isPublishableValue("0"))
        assertFalse(TelemetryKeyPolicy.isPublishableValue(null))
        assertFalse(TelemetryKeyPolicy.isPublishableValue("  "))
        assertFalse(TelemetryKeyPolicy.isPublishableValue("x".repeat(TelemetryKeyPolicy.MAX_VALUE_CHARS + 1)))
    }

    @Test
    fun rejectsKnownNonTelemetryOperations() {
        assertFalse(TelemetryKeyPolicy.isPublishable("car.beantechs.sota_start"))
        assertFalse(TelemetryKeyPolicy.isPublishable("car.configure.catapult_start"))
        assertFalse(TelemetryKeyPolicy.isPublishable("car.command_parking_setting.outside_control"))
        assertFalse(TelemetryKeyPolicy.isPublishable("car.ipk_setting.drive_info_reset"))
    }

    @Test
    fun allowsOccupantMonitoringData() {
        assertTrue(TelemetryKeyPolicy.isPublishable("car.oms.feedback.age_state"))
        assertTrue(TelemetryKeyPolicy.isPublishable("car.oms.feedback.gender_state"))
        assertTrue(TelemetryKeyPolicy.isPublishable("car.oms.frs.oms_facedb"))
        assertTrue(TelemetryKeyPolicy.isPublishable("car.oms.frs.passengerm_row1_expression"))
    }
}
