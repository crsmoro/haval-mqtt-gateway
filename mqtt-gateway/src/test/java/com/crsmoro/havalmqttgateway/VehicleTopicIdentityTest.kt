package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTopicIdentityTest {
    @Test
    fun validatesVinWithoutChangingItsCase() {
        fun valid(vin: String?): Boolean = VehicleTopicIdentity.isValidVin(vin)
        assertTrue(valid("1HGCM82633A004352"))
        assertTrue(valid("1hgcm82633a004352"))
        for (vin in listOf(null, "", "1HGCM82633A00435", "1HGCM82633A0043522", "IHGCM82633A004352", "OhGCM82633A004352", "qHGCM82633A004352", "+HGCM82633A004352", "#HGCM82633A004352", "/HGCM82633A004352", "\u0000HGCM82633A004352", "ÉHGCM82633A004352")) {
            assertFalse("Should reject $vin", valid(vin))
        }
    }
    @Test
    fun rejectsUniformPlaceholderVinsWithoutPublishingTopics() {
        for (vin in listOf("00000000000000000", "11111111111111111", "AAAAAAAAAAAAAAAAA", "aAaAaAaAaAaAaAaAa")) {
            assertFalse("Should reject uniform placeholder $vin", VehicleTopicIdentity.isValidVin(vin))
            assertEquals(null, VehicleTopicIdentity.build("gwm", vin, "car.basic.vehicle_speed"))
        }
    }

    @Test
    fun acceptsPlausibleVinsWithoutRequiringNorthAmericanCheckDigit() {
        for (vin in listOf("LGWFF5A50MB123456", "lgwff5a50mb123456", "1HGCM82633A004352")) {
            assertTrue("Should accept plausible VIN $vin", VehicleTopicIdentity.isValidVin(vin))
            assertEquals("gwm/haval/$vin/car.basic.vehicle_speed", VehicleTopicIdentity.build("gwm", vin, "car.basic.vehicle_speed"))
        }
    }

    @Test
    fun buildsOnlySafeTopicsAfterValidVinArrives() {
        fun build(prefix: String, vin: String?, key: String): String? =
            VehicleTopicIdentity.build(prefix, vin, key)
        assertEquals("gwm/haval/1hGCM82633A004352/car.basic.vehicle_speed", build("gwm", "1hGCM82633A004352", "car.basic.vehicle_speed"))
        assertEquals("site/garage/haval/1HGCM82633A004352/car.basic.vehicle_speed", build("site/garage", "1HGCM82633A004352", "car.basic.vehicle_speed"))
        for (vin in listOf(null, "", "1HGCM82633A00435+", "1HGCM82633A00435/", "1HGCM82633A00435#", "1HGCM82633A00435\u0000")) {
            assertEquals(null, build("gwm", vin, "car.basic.vehicle_speed"))
        }
        for (prefix in listOf("", "site//garage", "site/+", "site/#", "site/\u0000")) {
            assertEquals(null, build(prefix, "1HGCM82633A004352", "car.basic.vehicle_speed"))
        }
        for (key in listOf("", "car.basic/+", "car.basic/#", "car.basic/vehicle_speed", "car.basic.\u0000")) {
            assertEquals(null, build("gwm", "1HGCM82633A004352", key))
        }
    }

    @Test
    fun migratesOnlyExactOldDefault() {
        assertEquals("gwm", VehicleTopicIdentity.migratePrefix("haval/telemetry"))
        assertEquals("haval/telemetry/garage", VehicleTopicIdentity.migratePrefix("haval/telemetry/garage"))
        assertEquals(" /haval/telemetry/ ", VehicleTopicIdentity.migratePrefix(" /haval/telemetry/ "))
    }
}
