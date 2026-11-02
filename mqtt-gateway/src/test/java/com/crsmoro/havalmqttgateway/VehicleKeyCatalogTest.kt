package com.crsmoro.havalmqttgateway

import org.junit.Assert.*
import org.junit.Test

class VehicleKeyCatalogTest {
    @Test
    fun defaultMonitoredPropertyIsDiscoverableWithoutChangingItsSpelling() {
        val entry = VehicleKeyCatalog.find("car.basic.vehicle_speed")
        assertNotNull(entry)
        assertEquals(VehicleKeyCatalog.Source.OEM_PROPERTY, entry!!.source)
        assertTrue(entry.defaultMonitored)
        assertNull(VehicleKeyCatalog.find("CAR.BASIC.VEHICLE_SPEED"))
    }

    @Test
    fun catalogDeduplicatesAndContainsEveryDefaultIdentifier() {
        val entries = VehicleKeyCatalog.entries
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        assertEquals(126, entries.count { it.defaultMonitored })
        for (key in listOf("car.basic.tpms_status", "car.ev_info.Instant_energy_consumption", "car.basic.left_turn_Indicator_light_status")) {
            assertTrue("missing $key", VehicleKeyCatalog.find(key)!!.defaultMonitored)
        }
        assertNull(VehicleKeyCatalog.find("car.ev_info.instant_energy_consumption"))
    }

    @Test
    fun reportsPublishedSyntheticKeysWithoutPretendingTheyAreOemProperties() {
        val keys = listOf("haval.power.flow", "haval.power.ice")
        for (key in keys) {
            val entry = VehicleKeyCatalog.find(key)!!
            assertEquals(key, VehicleKeyCatalog.Source.APP_SYNTHETIC, entry.source)
            assertFalse(entry.defaultMonitored)
        }
    }

    @Test
    fun marksIdentifiersAndOccupantMonitoringAsSensitive() {
        for (key in listOf("car.basic.vin_code", "car.basic.seated_state", "car.basic.seat_belt_warning", "car.dms.frs.driver_age", "car.oms.frs.oms_facedb", "car.frs_setting.oms_age")) {
            assertTrue(key, VehicleKeyCatalog.find(key)!!.sensitive)
        }
        assertFalse(VehicleKeyCatalog.find("car.basic.vehicle_speed")!!.sensitive)
    }

    @Test
    fun excludesKnownCommandKeysAndDoesNotInventUnknownProperties() {
        for (key in listOf("car.basic.door_control_action", "car.basic.window_control_oneshot", "car.ev_setting.charge_action", "car.beantechs.sota_start", "car.ipk_setting.drive_info_reset", "car.command_parking_setting.outside_control", "car.unknown.property", "sys.network.sim_imsi", "sys.network.sim_iccid", "sys.basic.sn_code", "app.androidauto.session", "app.impulse.api_version", "app.navigation.directions", "car.map.tsr.nav_speed_limit", "car.configure.navigation")) {
            assertNull(key, VehicleKeyCatalog.find(key))
        }
    }

    @Test
    fun includesKnownTelemetryMetadataWithoutPromisingAvailability() {
        assertNotNull(VehicleKeyCatalog.find("car.basic.tpms_status")!!.note)
        assertNotNull(VehicleKeyCatalog.find("haval.power.flow")!!.note)
        assertNotNull(VehicleKeyCatalog.find("haval.power.ice")!!.note)
    }
}
