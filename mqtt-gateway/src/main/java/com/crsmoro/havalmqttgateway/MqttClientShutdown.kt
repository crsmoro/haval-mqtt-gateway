package com.crsmoro.havalmqttgateway

internal object MqttClientShutdown {
    fun disconnectThenClose(disconnect: () -> Unit, close: () -> Unit) {
        try {
            disconnect()
        } finally {
            close()
        }
    }
}
