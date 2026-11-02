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
                assertEquals("Velocidade do veículo", json.get("name").asString)
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

    @Test fun binaryDiscoveryReannouncesLegacyThenDeletesItBeforeBinaryConfig() {
        val topic = "homeassistant/binary_sensor/vin_74657374/key_74657374/config"
        val legacyTopic = topic.replace("/binary_sensor/", "/sensor/")
        val payload = """{"name":"car.lock","unique_id":"vin_74657374_key_74657374","state_topic":"gwm/haval/VIN123/car.lock","value_template":"{{ value_json.raw_value }}","device":{"identifiers":["vin_74657374"],"name":"Haval VIN123"},"payload_on":"1","payload_off":"0"}"""
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            val first = java.util.concurrent.LinkedBlockingQueue<WirePublish>()
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val packets = executor.submit<List<WirePublish>> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    check(readPacket(input).first == 0x10)
                    output.write(byteArrayOf(0x20, 2, 0, 0)); output.flush()
                    val sent = (0..2).map { index ->
                        val (header, body) = readPacket(input)
                        check(header shr 4 == 3)
                        val len = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                        val offset = 2 + len
                        val packet = WirePublish(String(body, 2, len, Charsets.UTF_8),
                            String(body, offset + 2, body.size - offset - 2, Charsets.UTF_8),
                            (header shr 1) and 3, header and 1 != 0)
                        if (index == 0) { first.put(packet); check(release.await(10, TimeUnit.SECONDS)) }
                        output.write(byteArrayOf(0x40, 2, body[offset], body[offset + 1])); output.flush()
                        packet
                    }
                    check(completed.await(10, TimeUnit.SECONDS))
                    sent
                }
            }
            val connected = CountDownLatch(1)
            val result = AtomicReference<Boolean>()
            val publisher = publisher(server.localPort, connected)
            try {
                publisher.connect()
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                publisher.publishDiscovery(HomeAssistantDiscovery.Message(topic, payload)) { result.set(it); completed.countDown() }
                val initial = first.poll(10, TimeUnit.SECONDS) ?: error("first publish missing")
                assertEquals(legacyTopic, initial.topic)
                assertEquals(1, initial.qos)
                assertFalse(initial.retained)
                val json = JsonParser.parseString(initial.payload).asJsonObject
                assertEquals("vin_74657374_key_74657374", json.get("unique_id").asString)
                assertEquals("gwm/haval/VIN123/car.lock", json.get("state_topic").asString)
                assertEquals("{{ value_json.raw_value }}", json.get("value_template").asString)
                assertEquals(JsonParser.parseString(payload).asJsonObject.get("device"), json.get("device"))
                assertFalse(json.has("payload_on")); assertFalse(json.has("payload_off"))
                assertFalse(completed.await(300, TimeUnit.MILLISECONDS))
                release.countDown()
                val sent = packets.get(10, TimeUnit.SECONDS)
                assertEquals(WirePublish(legacyTopic, "", 1, true), sent[1])
                assertEquals(WirePublish(topic, payload, 1, true), sent[2])
                assertTrue(completed.await(10, TimeUnit.SECONDS))
                assertEquals(true, result.get())
            } finally {
                release.countDown()
                stop(publisher)
                executor.shutdownNow()
            }
        }
    }

    @Test fun disablingDiscoveryBetweenAcknowledgementsDoesNotSendTombstone() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val first = CountDownLatch(1); val ack = CountDownLatch(1)
            val noTombstone = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 1000
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(readPacket(input).first == 0x10); out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    val (header, body) = readPacket(input); check(header shr 4 == 3)
                    val len = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                    val offset = 2 + len; first.countDown(); check(ack.await(10, TimeUnit.SECONDS))
                    out.write(byteArrayOf(0x40, 2, body[offset], body[offset + 1])); out.flush()
                    try { readPacket(input); false } catch (_: java.net.SocketTimeoutException) { true }
                }
            }
            val connected = CountDownLatch(1); val completed = CountDownLatch(1)
            val allowed = java.util.concurrent.atomic.AtomicBoolean(true); val outcome = AtomicReference<Boolean>()
            val config = GatewayConfig(true, false, "tcp://127.0.0.1:${server.localPort}", "", "test/discovery")
            val publisher = MqttPublisher(temporaryFolder.newFolder(), config, MqttCredentials.from("", null),
                object : MqttPublisher.Listener {
                    override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                    override fun onDisconnected(reason: String) = Unit
                    override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) = Unit
                }, { allowed.get() })
            val payload = """{"unique_id":"id","state_topic":"root/VIN123/car.lock","value_template":"{{ value_json.raw_value }}","device":{"identifiers":["vin"]}}"""
            try {
                publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS))
                publisher.publishDiscovery(HomeAssistantDiscovery.Message("homeassistant/binary_sensor/node/key/config", payload)) { outcome.set(it); completed.countDown() }
                assertTrue(first.await(10, TimeUnit.SECONDS)); allowed.set(false); ack.countDown()
                assertTrue(completed.await(10, TimeUnit.SECONDS)); assertEquals(false, outcome.get())
                assertTrue("Disabled discovery sent another packet", noTombstone.get(10, TimeUnit.SECONDS))
            } finally { ack.countDown(); stop(publisher); executor.shutdownNow() }
        }
    }

    @Test fun alreadyBinaryIcePublishesOnlyItsConfig() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val packet = executor.submit<WirePublish> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(readPacket(input).first == 0x10); out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    val (header, body) = readPacket(input)
                    val len = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                    val offset = 2 + len
                    val result = WirePublish(String(body, 2, len, Charsets.UTF_8), String(body, offset + 2, body.size - offset - 2, Charsets.UTF_8), (header shr 1) and 3, header and 1 != 0)
                    out.write(byteArrayOf(0x40, 2, body[offset], body[offset + 1])); out.flush()
                    while (input.read() != -1) { }
                    result
                }
            }
            val connected = CountDownLatch(1); val completed = CountDownLatch(1); val outcome = AtomicReference<Boolean>()
            val publisher = publisher(server.localPort, connected)
            val message = HomeAssistantDiscovery.build("VIN123", "haval.power.ice")!!
            try {
                publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS))
                publisher.publishDiscovery(message) { outcome.set(it); completed.countDown() }
                assertTrue(completed.await(10, TimeUnit.SECONDS)); assertEquals(true, outcome.get())
                stop(publisher)
                assertEquals(WirePublish(message.topic, message.payload, 1, true), packet.get(10, TimeUnit.SECONDS))
            } finally { stop(publisher); executor.shutdownNow() }
        }
    }

    @Test fun disablingAfterUnackedTombstoneDoesNotReplayItOnReconnect() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor(); val dropped = CountDownLatch(1)
            val replayed = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(readPacket(input).first == 0x10); out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    val (_, first) = readPacket(input)
                    val len = ((first[0].toInt() and 255) shl 8) or (first[1].toInt() and 255)
                    out.write(byteArrayOf(0x40, 2, first[2 + len], first[3 + len])); out.flush()
                    val (header, tombstone) = readPacket(input); check(header shr 4 == 3 && header and 1 == 1)
                    val tombLen = ((tombstone[0].toInt() and 255) shl 8) or (tombstone[1].toInt() and 255)
                    check(tombstone.size == 2 + tombLen + 2)
                    // Disconnect before the retained tombstone is acknowledged.
                }
                dropped.countDown()
                server.accept().use { socket ->
                    socket.soTimeout = 1500
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(readPacket(input).first == 0x10); out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    try { readPacket(input).first shr 4 == 3 } catch (_: java.net.SocketTimeoutException) { false }
                }
            }
            val connected = CountDownLatch(2); val lost = CountDownLatch(1)
            val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
            val config = GatewayConfig(true, false, "tcp://127.0.0.1:${server.localPort}", "", "test/reconnect")
            val publisher = MqttPublisher(temporaryFolder.newFolder(), config, MqttCredentials.from("", null),
                object : MqttPublisher.Listener {
                    override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                    override fun onDisconnected(reason: String) { lost.countDown() }
                    override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) = Unit
                }, { allowed.get() })
            val payload = """{"unique_id":"id","state_topic":"root/VIN123/car.lock","value_template":"{{ value_json.raw_value }}","device":{"identifiers":["vin"]}}"""
            try {
                publisher.connect()
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (connected.count == 2L && System.nanoTime() < until) Thread.sleep(10)
                assertEquals(1L, connected.count)
                publisher.publishDiscovery(HomeAssistantDiscovery.Message("homeassistant/binary_sensor/node/key/config", payload)) { }
                assertTrue(dropped.await(10, TimeUnit.SECONDS)); assertTrue(lost.await(10, TimeUnit.SECONDS))
                allowed.set(false); publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS))
                assertFalse("Paho replayed an unacknowledged tombstone after disabling discovery", replayed.get(10, TimeUnit.SECONDS))
            } finally { stop(publisher); executor.shutdownNow() }
        }
    }

    @Test fun timeoutDuringPermissionCheckCannotDispatchLatePublish() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newCachedThreadPool()
            val release = CountDownLatch(1); val checked = CountDownLatch(1); val checkWire = CountDownLatch(1)
            val noPublish = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 1500
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(readPacket(input).first == 0x10); out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    check(checkWire.await(20, TimeUnit.SECONDS))
                    try { readPacket(input).first shr 4 != 3 } catch (_: java.net.SocketTimeoutException) { true }
                }
            }
            val connected = CountDownLatch(1); val completed = CountDownLatch(1)
            val outcome = AtomicReference<Boolean>()
            val config = GatewayConfig(true, false, "tcp://127.0.0.1:${server.localPort}", "", "test/discovery")
            val publisher = MqttPublisher(temporaryFolder.newFolder(), config, MqttCredentials.from("", null),
                object : MqttPublisher.Listener {
                    override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                    override fun onDisconnected(reason: String) = Unit
                    override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) = Unit
                }, { checked.countDown(); release.await(20, TimeUnit.SECONDS); true })
            try {
                publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS))
                val send = executor.submit { publisher.publishDiscovery(HomeAssistantDiscovery.build("VIN123", "speed")!!) { outcome.set(it); completed.countDown() } }
                assertTrue(checked.await(5, TimeUnit.SECONDS))
                assertTrue("Timeout must not wait for a permission check", completed.await(15, TimeUnit.SECONDS))
                assertEquals(false, outcome.get()); release.countDown(); send.get(5, TimeUnit.SECONDS); checkWire.countDown()
                assertTrue("Publish was dispatched after timeout", noPublish.get(5, TimeUnit.SECONDS))
            } finally { release.countDown(); checkWire.countDown(); stop(publisher); executor.shutdownNow() }
        }
    }

    @Test fun unacknowledgedDiscoveryTimesOutAndDoesNotContinueAfterLateAck() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor(); val received = CountDownLatch(1); val ack = CountDownLatch(1)
            val noNext = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 1000
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    check(readPacket(input).first == 0x10); out.write(byteArrayOf(0x20, 2, 0, 0)); out.flush()
                    val (_, body) = readPacket(input); val len = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                    received.countDown(); check(ack.await(25, TimeUnit.SECONDS))
                    out.write(byteArrayOf(0x40, 2, body[2 + len], body[3 + len])); out.flush()
                    try { readPacket(input); false } catch (_: java.net.SocketTimeoutException) { true }
                }
            }
            val connected = CountDownLatch(1); val completed = CountDownLatch(1); val outcome = AtomicReference<Boolean>()
            val config = GatewayConfig(true, false, "tcp://127.0.0.1:${server.localPort}", "", "test/discovery")
            val publisher = MqttPublisher(temporaryFolder.newFolder(), config, MqttCredentials.from("", null),
                object : MqttPublisher.Listener {
                    override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                    override fun onDisconnected(reason: String) = Unit
                    override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) = Unit
                })
            val payload = """{"unique_id":"id","state_topic":"root/VIN123/car.lock","value_template":"{{ value_json.raw_value }}","device":{"identifiers":["vin"]}}"""
            try {
                publisher.connect(); assertTrue(connected.await(10, TimeUnit.SECONDS))
                publisher.publishDiscovery(HomeAssistantDiscovery.Message("homeassistant/binary_sensor/node/key/config", payload)) { outcome.set(it); completed.countDown() }
                assertTrue(received.await(10, TimeUnit.SECONDS))
                assertTrue("Discovery did not time out", completed.await(20, TimeUnit.SECONDS)); assertEquals(false, outcome.get())
                ack.countDown(); assertTrue("Late acknowledgement advanced migration", noNext.get(10, TimeUnit.SECONDS))
            } finally { ack.countDown(); stop(publisher); executor.shutdownNow() }
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
