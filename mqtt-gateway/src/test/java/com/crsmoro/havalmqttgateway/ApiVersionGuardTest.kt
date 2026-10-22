package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiVersionGuardTest {
    @Test
    fun reportsOnlyFirstUnsupportedVersionUntilSupportedVersionArrives() {
        val guard = ApiVersionGuard(supportedVersion = 1)

        val first = guard.evaluate("2")
        val second = guard.evaluate("7")

        assertFalse(first.ready)
        assertTrue(first.reportIncompatible)
        assertFalse(second.ready)
        assertFalse(second.reportIncompatible)
        assertEquals(7, second.version)
    }

    @Test
    fun invalidatesReadinessWhenProducerChangesToUnsupportedVersion() {
        val guard = ApiVersionGuard(supportedVersion = 1)

        val supported = guard.evaluate("1")
        val incompatible = guard.evaluate("2")
        val repeated = guard.evaluate("2")

        assertTrue(supported.accepted)
        assertTrue(supported.ready)
        assertFalse(incompatible.accepted)
        assertFalse(incompatible.ready)
        assertTrue(incompatible.reportIncompatible)
        assertFalse(repeated.ready)
        assertFalse(repeated.reportIncompatible)
        assertEquals(2, incompatible.version)
    }
}
