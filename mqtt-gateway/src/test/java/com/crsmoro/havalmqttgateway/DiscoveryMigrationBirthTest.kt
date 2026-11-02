package com.crsmoro.havalmqttgateway

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryMigrationBirthTest {
    @get:Rule val folder = TemporaryFolder()

    private fun read(input: DataInputStream): Pair<Int, ByteArray> {
        val header = input.readUnsignedByte()
        var length = 0; var multiplier = 1
        do { val digit = input.readUnsignedByte(); length += (digit and 127) * multiplier; multiplier *= 128 } while (digit and 128 != 0)
        return header to ByteArray(length).also(input::readFully)
    }
    private fun publisher(port: Int, enabled: Boolean, connected: CountDownLatch, birth: () -> Unit): MqttPublisher =
        MqttPublisher(folder.newFolder(), GatewayConfig(true, false, "tcp://127.0.0.1:$port", "", "test/birth", enabled),
            MqttCredentials.from("", null), object : MqttPublisher.Listener {
                override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                override fun onDisconnected(reason: String) = Unit
                override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) = Unit
                override fun onHomeAssistantBirth() = birth()
            })
    private fun stop(publisher: MqttPublisher) {
        val done = CountDownLatch(1); publisher.disconnect { done.countDown() }; assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test fun queueWaitsForDiscoveryBeforePublishingTelemetry() {
        // The Android Service cannot be instantiated in local JVM tests without an Android runtime.
        val source = java.io.File("src/main/java/com/crsmoro/havalmqttgateway/GatewayService.kt").readText()
        val pump = source.substringAfter("private fun pumpQueue() {").substringBefore("private fun scheduleRetry()")
        assertTrue("The queue must stop while discovery is in flight",
            Regex("if \\(!connected \\|\\| discoveryInFlight\\) return").containsMatchIn(pump))
        val discoveryDispatch = pump.substringAfter("activePublisher.publishDiscovery(message)")
            .substringBefore("synchronized(queueLock) { publishInFlight = next }")
        assertTrue("Starting discovery must defer telemetry until its callback",
            discoveryDispatch.trimEnd().endsWith("return\n            }\n        }"))
    }

    @Test fun enabledSubscribesOnlyToStatusAndHandlesOnlineBirth() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val received = executor.submit<String> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(read(input).first == 0x10)
                    out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    val (header, body) = read(input)
                    check(header == 0x82)
                    val length = ((body[2].toInt() and 255) shl 8) or (body[3].toInt() and 255)
                    check(body.size == 2 + 2 + length + 1)
                    check(body.last().toInt() == 1)
                    val topic = String(body, 4, length, Charsets.UTF_8)
                    out.write(byteArrayOf(0x90.toByte(), 3, body[0], body[1], 1)); out.flush()
                    val status = "homeassistant/status".toByteArray()
                    val payload = "online".toByteArray()
                    out.write(byteArrayOf(0x30, (2 + status.size + payload.size).toByte(), 0, status.size.toByte()))
                    out.write(status); out.write(payload); out.flush()
                    while (input.read() != -1) { }
                    topic
                }
            }
            val connected = CountDownLatch(1); val born = CountDownLatch(1); val calls = AtomicInteger()
            val publisher = publisher(server.localPort, true, connected) { calls.incrementAndGet(); born.countDown() }
            try {
                publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS)); assertTrue(born.await(10, TimeUnit.SECONDS))
                stop(publisher)
                assertEquals("homeassistant/status", received.get(10, TimeUnit.SECONDS)); assertEquals(1, calls.get())
            } finally { stop(publisher); executor.shutdownNow() }
        }
    }

    @Test fun rejectedStatusSubscriptionRetriesWithDelayAndReceivesBirth() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val attempts = executor.submit<List<Long>> {
                server.accept().use { socket ->
                    socket.soTimeout = 8_000
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(read(input).first == 0x10)
                    out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    val times = mutableListOf<Long>()
                    repeat(3) { index ->
                        val (header, body) = read(input); check(header == 0x82)
                        times.add(System.nanoTime())
                        out.write(byteArrayOf(0x90.toByte(), 3, body[0], body[1], if (index < 2) 0x80.toByte() else 1)); out.flush()
                    }
                    val status = "homeassistant/status".toByteArray(); val payload = "online".toByteArray()
                    out.write(byteArrayOf(0x30, (2 + status.size + payload.size).toByte(), 0, status.size.toByte()))
                    out.write(status); out.write(payload); out.flush()
                    while (input.read() != -1) { }
                    times
                }
            }
            val connected = CountDownLatch(1); val born = CountDownLatch(1)
            val publisher = publisher(server.localPort, true, connected) { born.countDown() }
            try {
                publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS))
                assertTrue("Rejected SUBACK was not retried successfully", born.await(8, TimeUnit.SECONDS))
                stop(publisher)
                val times = attempts.get(10, TimeUnit.SECONDS)
                assertTrue("Subscription retry loop is too fast", times.zipWithNext().all { (a, b) -> b - a >= TimeUnit.MILLISECONDS.toNanos(500) })
            } finally { stop(publisher); executor.shutdownNow() }
        }
    }

    @Test fun repeatedStatusRejectionStopsAfterFiveAttempts() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val bounded = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(read(input).first == 0x10); out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    repeat(5) {
                        val (header, body) = read(input); check(header == 0x82)
                        out.write(byteArrayOf(0x90.toByte(), 3, body[0], body[1], 0x80.toByte())); out.flush()
                    }
                    // Ignore keepalive traffic; a sixth exponential retry would be due after 16 seconds.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(17)
                    var extraSubscribe = false
                    while (System.nanoTime() < deadline) {
                        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                        socket.soTimeout = remainingMs.toInt()
                        try {
                            val (header, _) = read(input)
                            if (header == 0xc0) { out.write(byteArrayOf(0xd0.toByte(), 0)); out.flush() }
                            else { extraSubscribe = true; break }
                        } catch (_: SocketTimeoutException) { break }
                    }
                    !extraSubscribe
                }
            }
            val connected = CountDownLatch(1)
            val publisher = publisher(server.localPort, true, connected) { }
            try {
                publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS))
                assertTrue("Subscription retried more than five times", bounded.get(40, TimeUnit.SECONDS))
            } finally { stop(publisher); executor.shutdownNow() }
        }
    }

    @Test fun disabledDoesNotSubscribeToStatus() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val noSubscription = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 700
                    val input = DataInputStream(socket.getInputStream()); check(read(input).first == 0x10)
                    socket.getOutputStream().apply { write(byteArrayOf(0x20, 2, 0, 0)); flush() }
                    try { read(input); false } catch (_: SocketTimeoutException) { true }
                }
            }
            val connected = CountDownLatch(1); val calls = AtomicInteger()
            val publisher = publisher(server.localPort, false, connected) { calls.incrementAndGet() }
            try { publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS)); assertTrue(noSubscription.get(10, TimeUnit.SECONDS)); assertEquals(0, calls.get()) }
            finally { stop(publisher); executor.shutdownNow() }
        }
    }
}
