package com.crsmoro.havalmqttgateway

internal data class ApiVersionDecision(
    val accepted: Boolean,
    val version: Int,
    val ready: Boolean,
    val reportIncompatible: Boolean
)

internal class ApiVersionGuard(private val supportedVersion: Int) {
    private var currentVersion = 0
    private var ready = false
    private var incompatibilityReported = false

    fun evaluate(rawValue: String): ApiVersionDecision {
        val parsedVersion = rawValue.toIntOrNull() ?: 0
        val wasReady = ready
        if (parsedVersion == supportedVersion) {
            currentVersion = parsedVersion
            ready = true
            incompatibilityReported = false
            return ApiVersionDecision(true, currentVersion, ready, reportIncompatible = false)
        }
        currentVersion = parsedVersion
        ready = false
        val shouldReport = wasReady || !incompatibilityReported
        incompatibilityReported = true
        return ApiVersionDecision(false, currentVersion, ready = false, reportIncompatible = shouldReport)
    }
}
