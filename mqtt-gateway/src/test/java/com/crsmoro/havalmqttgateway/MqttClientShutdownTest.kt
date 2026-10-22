package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertTrue
import org.junit.Test

class MqttClientShutdownTest {
    @Test
    fun closesClientEvenWhenDisconnectThrows() {
        var closed = false
        try {
            MqttClientShutdown.disconnectThenClose(
                disconnect = { error("disconnect timeout") },
                close = { closed = true }
            )
        } catch (_: IllegalStateException) {
            // Expected disconnect failure; cleanup must still run.
        }

        assertTrue(closed)
    }
}
