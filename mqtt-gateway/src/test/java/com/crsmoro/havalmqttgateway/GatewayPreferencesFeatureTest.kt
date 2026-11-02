package com.crsmoro.havalmqttgateway

import org.junit.Assert.*
import org.junit.Test

class GatewayPreferencesFeatureTest {
    @Test fun discoveryIsAnExplicitOptionDisabledForExistingConfigurations() {
        val config = GatewayConfig(true, false, "tcp://localhost:1883", "", "gwm")
        val property = GatewayConfig::class.java.methods.firstOrNull { it.name == "getPublishHomeAssistant" }
        assertNotNull("GatewayConfig must expose the Home Assistant option", property)
        assertEquals(false, property!!.invoke(config))
    }
}
