package com.crsmoro.havalmqttgateway

/** Cached observations may be replayed only while both endpoints and the VIN are valid. */
object HomeAssistantReplayPolicy {
    fun shouldReplay(enabled: Boolean, connected: Boolean, sourceReady: Boolean, vin: String?): Boolean =
        enabled && connected && sourceReady && VehicleTopicIdentity.isValidVin(vin)
}
