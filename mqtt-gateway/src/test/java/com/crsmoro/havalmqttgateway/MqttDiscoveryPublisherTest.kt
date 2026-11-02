package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses a real Paho client and a minimal MQTT 3.1.1 TCP endpoint. */
class MqttDiscoveryPublisherTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun discoveryIsRetainedQosOneAndCompletesOnlyAfterPuback() {
        val method = MqttPublisher::class.java.methods.firstOrNull {
            it.name == "publishDiscovery" && it.parameterCount == 2
        }
        assertNotNull("Publisher must expose publishDiscovery", method)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val received = CountDownLatch(1)
            val sendAck = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val packet = executor.submit<WirePublish> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    check(readPacket(input).first == 0x10)
                    output.write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                    output.flush()
                    val (header, body) = readPacket(input)
                    check(header shr 4 == 3)
                    val topicLength = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                    val topic = String(body, 2, topicLength, Charsets.UTF_8)
                    val qos = (header shr 1) and 3
                    val idOffset = 2 + topicLength
                    val payloadOffset = idOffset + if (qos > 0) 2 else 0
                    val result = WirePublish(topic, String(body, payloadOffset, body.size - payloadOffset, Charsets.UTF_8), qos, header and 1 != 0)
                    received.countDown()
                    check(sendAck.await(10, TimeUnit.SECONDS))
                    if (qos == 1) {
                        output.write(byteArrayOf(0x40, 0x02, body[idOffset], body[idOffset + 1]))
                        output.flush()
                    }
                    // Keep the socket alive until the client closes it.
                    while (input.read() != -1) { }
                    result
                }
            }
            val connected = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val calls = AtomicInteger()
            val outcome = AtomicReference<Boolean>()
            val config = GatewayConfig(true, false, "tcp://127.0.0.1:${server.localPort}", "", "test/discovery")
            val publisher = MqttPublisher(temporaryFolder.newFolder("paho"), config, MqttCredentials.from("", null),
                object : MqttPublisher.Listener {
                    override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                    override fun onDisconnected(reason: String) = Unit
                    override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) = Unit
                })
            try {
                publisher.connect()
                assertTrue("Connection timed out", connected.await(10, TimeUnit.SECONDS))
                val discovery = HomeAssistantDiscovery.build("VIN123", "car.basic.vehicle_speed")!!
                val callback: (Boolean) -> Unit = { outcome.set(it); calls.incrementAndGet(); completed.countDown() }
                method!!.invoke(publisher, discovery, callback)
                assertTrue("PUBLISH not received", received.await(10, TimeUnit.SECONDS))
                assertFalse("Completion before PUBACK", completed.await(300, TimeUnit.MILLISECONDS))
                sendAck.countDown()
                assertTrue("PUBACK completion timed out", completed.await(10, TimeUnit.SECONDS))
                assertEquals(true, outcome.get())
                assertEquals(1, calls.get())
                val stopped = CountDownLatch(1)
                publisher.disconnect { stopped.countDown() }
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                val published = packet.get(10, TimeUnit.SECONDS)
                assertEquals(discovery.topic, published.topic)
                assertEquals(1, published.qos)
                assertTrue(published.retained)
                val json = JsonParser.parseString(published.payload).asJsonObject
                assertEquals("gwm/haval/VIN123/car.basic.vehicle_speed", json.get("state_topic").asString)
                assertEquals("car.basic.vehicle_speed", json.get("name").asString)
                assertEquals(JsonParser.parseString(discovery.payload), json)
                assertEquals(1, calls.get())
            } finally {
                sendAck.countDown()
                val stopped = CountDownLatch(1)
                publisher.disconnect { stopped.countDown() }
                stopped.await(5, TimeUnit.SECONDS)
                executor.shutdownNow()
            }
        }
    }

    @Test fun disconnectedPublishFailsExactlyOnceWithoutQueuing() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            val noQueuedPacket = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 800
                    val input = DataInputStream(socket.getInputStream())
                    check(readPacket(input).first == 0x10)
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                        flush()
                    }
                    try {
                        readPacket(input)
                        false
                    } catch (_: java.net.SocketTimeoutException) {
                        true
                    }
                }
            }
            val connected = CountDownLatch(1)
            val publisher = publisher(server.localPort, connected)
            val calls = AtomicInteger()
            val outcome = AtomicReference<Boolean>()
            try {
                publisher.publishDiscovery(HomeAssistantDiscovery.build("VIN123", "speed")!!) {
                    outcome.set(it)
                    calls.incrementAndGet()
                }
                assertEquals(false, outcome.get())
                assertEquals(1, calls.get())
                publisher.connect()
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                assertTrue("Disconnected publication was queued", noQueuedPacket.get(10, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
            } finally {
                stop(publisher)
                executor.shutdownNow()
            }
        }
    }

    @Test fun connectionLostBeforePubackFailsExactlyOnce() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            val received = executor.submit<Unit> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    check(readPacket(input).first == 0x10)
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                        flush()
                    }
                    check(readPacket(input).first shr 4 == 3)
                    // Close without PUBACK to force an asynchronous Paho failure.
                }
            }
            val connected = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val calls = AtomicInteger()
            val outcome = AtomicReference<Boolean>()
            val publisher = publisher(server.localPort, connected)
            try {
                publisher.connect()
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                publisher.publishDiscovery(HomeAssistantDiscovery.build("VIN123", "speed")!!) {
                    outcome.set(it)
                    calls.incrementAndGet()
                    completed.countDown()
                }
                received.get(10, TimeUnit.SECONDS)
                assertTrue("Connection loss did not complete publish", completed.await(10, TimeUnit.SECONDS))
                assertEquals(false, outcome.get())
                stop(publisher)
                assertEquals(1, calls.get())
            } finally {
                stop(publisher)
                executor.shutdownNow()
            }
        }
    }

    @Test fun invalidTopicFailsSynchronouslyExactlyOnce() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            val accepted = executor.submit<Unit> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    check(readPacket(input).first == 0x10)
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                        flush()
                    }
                    while (input.read() != -1) { }
                }
            }
            val connected = CountDownLatch(1)
            val publisher = publisher(server.localPort, connected)
            val completed = CountDownLatch(1)
            val calls = AtomicInteger()
            val outcome = AtomicReference<Boolean>()
            try {
                publisher.connect()
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                publisher.publishDiscovery(HomeAssistantDiscovery.Message("invalid/+/topic", "{}")) {
                    outcome.set(it)
                    calls.incrementAndGet()
                    completed.countDown()
                }
                assertTrue(completed.await(10, TimeUnit.SECONDS))
                assertEquals(false, outcome.get())
                assertEquals(1, calls.get())
            } finally {
                stop(publisher)
                accepted.get(10, TimeUnit.SECONDS)
                executor.shutdownNow()
            }
        }
    }

    private fun publisher(port: Int, connected: CountDownLatch): MqttPublisher {
        val config = GatewayConfig(true, false, "tcp://127.0.0.1:$port", "", "test/discovery")
        return MqttPublisher(temporaryFolder.newFolder(), config, MqttCredentials.from("", null),
            object : MqttPublisher.Listener {
                override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                override fun onDisconnected(reason: String) = Unit
                override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) = Unit
            })
    }

    private fun stop(publisher: MqttPublisher) {
        val stopped = CountDownLatch(1)
        publisher.disconnect { stopped.countDown() }
        assertTrue("Publisher shutdown timed out", stopped.await(5, TimeUnit.SECONDS))
    }

    private data class WirePublish(val topic: String, val payload: String, val qos: Int, val retained: Boolean)

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = input.readUnsignedByte()
        var length = 0
        var multiplier = 1
        var digit: Int
        do {
            digit = input.readUnsignedByte()
            length += (digit and 127) * multiplier
            multiplier *= 128
            check(multiplier <= 128 * 128 * 128 * 128)
        } while (digit and 128 != 0)
        val body = ByteArray(length)
        input.readFully(body)
        return header to body
    }
}
