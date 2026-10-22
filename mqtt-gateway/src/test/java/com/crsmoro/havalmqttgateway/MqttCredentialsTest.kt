package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MqttCredentialsTest {
    @Test
    fun allowsUsernameOnlyAuthentication() {
        val credentials = MqttCredentials.from(" telemetry-user ", null)

        assertEquals("telemetry-user", credentials.username)
        assertNull(credentials.password)
    }

    @Test
    fun ignoresPasswordWhenUsernameIsBlank() {
        val credentials = MqttCredentials.from("", "ignored")

        assertEquals("", credentials.username)
        assertNull(credentials.password)
    }
}
