package com.crsmoro.havalmqttgateway

internal object TelemetryFeedPolicy {
    fun canEnqueue(apiReady: Boolean, sourceVersion: Int, supportedVersion: Int): Boolean =
        apiReady && sourceVersion == supportedVersion
}
