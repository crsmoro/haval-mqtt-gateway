package com.crsmoro.havalmqttgateway

import java.util.LinkedHashMap

/** Bounds the in-memory latest-value queue by key count and conservative payload size. */
internal class PendingTelemetryBudget(
    private val maxKeys: Int,
    private val maxBytes: Long
) {
    private val sizesByKey = LinkedHashMap<String, Long>()
    private var bytes = 0L

    val size: Int
        @Synchronized get() = sizesByKey.size

    val currentBytes: Long
        @Synchronized get() = bytes

    @Synchronized
    fun put(key: String, value: String): List<String> {
        sizesByKey.remove(key)?.let { bytes -= it }
        val size = estimateBytes(key, value)
        sizesByKey[key] = size
        bytes += size

        val evicted = mutableListOf<String>()
        while (sizesByKey.isNotEmpty() && (sizesByKey.size > maxKeys || bytes > maxBytes)) {
            val oldestKey = sizesByKey.keys.first()
            bytes -= sizesByKey.remove(oldestKey) ?: 0L
            evicted += oldestKey
        }
        return evicted
    }

    @Synchronized
    fun remove(key: String): Boolean {
        val size = sizesByKey.remove(key) ?: return false
        bytes -= size
        return true
    }

    @Synchronized
    fun clear() {
        sizesByKey.clear()
        bytes = 0L
    }

    private fun estimateBytes(key: String, value: String): Long =
        key.length.toLong() * 8 + value.length.toLong() * 8 + ENVELOPE_OVERHEAD_BYTES

    companion object {
        private const val ENVELOPE_OVERHEAD_BYTES = 256L
    }
}
