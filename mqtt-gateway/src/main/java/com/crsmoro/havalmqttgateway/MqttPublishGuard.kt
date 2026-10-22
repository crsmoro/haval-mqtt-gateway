package com.crsmoro.havalmqttgateway

internal object MqttPublishGuard {
    fun execute(publish: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            publish()
        } catch (error: Exception) {
            onFailure(error)
        }
    }
}
