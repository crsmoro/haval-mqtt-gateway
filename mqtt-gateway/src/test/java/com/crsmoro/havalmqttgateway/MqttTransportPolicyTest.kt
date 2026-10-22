package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttTransportPolicyTest {
    @Test
    fun credentialsAreRejectedOverPlainTcp() {
        assertTrue(MqttTransportPolicy.credentialsRequireTls("tcp://broker:1883", "user", true))
        assertTrue(MqttTransportPolicy.credentialsRequireTls("tcp://broker:1883", "", true))
        assertTrue(MqttTransportPolicy.credentialsRequireTls("tcp://broker:1883", "user", false))
    }

    @Test
    fun tlsAndAnonymousTcpAreAllowedByCredentialPolicy() {
        assertFalse(MqttTransportPolicy.credentialsRequireTls("ssl://broker:8883", "user", true))
        assertFalse(MqttTransportPolicy.credentialsRequireTls("tcp://broker:1883", "", false))
    }
}
