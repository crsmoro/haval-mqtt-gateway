package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttClientIdBuilderTest {
    @org.junit.Test fun sharedPrefixMustNotDisconnectDifferentInstallations() {
        val method = MqttClientIdBuilder::class.java.methods.firstOrNull { it.name == "fromInstallation" }
        org.junit.Assert.assertNotNull("Installation-scoped client identity is required", method)
        val first = method!!.invoke(MqttClientIdBuilder, "gwm", "installation-1") as String
        val second = method.invoke(MqttClientIdBuilder, "gwm", "installation-2") as String
        org.junit.Assert.assertNotEquals(first, second)
        org.junit.Assert.assertEquals(first, method.invoke(MqttClientIdBuilder, "gwm", "installation-1"))
    }
    @Test
    fun distinctPrefixesDoNotShareClientId() {
        assertNotEquals(MqttClientIdBuilder.fromTopicPrefix("a/b"), MqttClientIdBuilder.fromTopicPrefix("a_b"))
        assertNotEquals(MqttClientIdBuilder.fromTopicPrefix("a/_b"), MqttClientIdBuilder.fromTopicPrefix("a_/b"))
    }

    @Test
    fun derivesStableBrokerClientIdFromConfiguredPrefix() {
        val prefix = "haval/telemetry/garage"
        val first = MqttClientIdBuilder.fromTopicPrefix(prefix)

        assertEquals("haval-gateway-haval_stelemetry_sgarage", first)
        assertEquals(first, MqttClientIdBuilder.fromTopicPrefix(prefix))
        assertNotEquals(first, MqttClientIdBuilder.fromTopicPrefix("haval/telemetry/other"))
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
    }
}
