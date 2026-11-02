package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises real Paho traffic against a minimal MQTT 3.1.1 localhost endpoint, not a vehicle or a full broker. */
class VehicleMqttEndToEndTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun fakeOemValueReachesLocalMqttEndpointWithExpectedMetadataAndPublishFlags() {
        val directoryConstructor = MqttPublisher::class.java.declaredConstructors.firstOrNull {
            it.parameterTypes.firstOrNull() == File::class.java
        }
        assertNotNull("Publisher must accept a filesystem persistence directory without Android Context", directoryConstructor)

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            val received = executor.submit<WirePublish> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    val connect = readPacket(input)
                    check(connect.first == 0x10) { "Expected MQTT CONNECT, got ${connect.first}" }
                    output.write(byteArrayOf(0x20, 0x02, 0x00, 0x00)) // CONNACK
                    output.flush()
                    val (header, body) = readPacket(input)
                    check(header shr 4 == 3) { "Expected MQTT PUBLISH, got $header" }
                    val topicLength = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                    val topic = String(body, 2, topicLength, Charsets.UTF_8)
                    val qos = (header shr 1) and 3
                    val packetIdOffset = 2 + topicLength
                    val payloadOffset = packetIdOffset + if (qos > 0) 2 else 0
                    val payload = String(body, payloadOffset, body.size - payloadOffset, Charsets.UTF_8)
                    if (qos == 1) {
                        output.write(byteArrayOf(0x40, 0x02, body[packetIdOffset], body[packetIdOffset + 1]))
                        output.flush()
                    }
                    WirePublish(topic, payload, qos, (header and 1) != 0)
                }
            }
            val connected = CountDownLatch(1)
            val published = CountDownLatch(1)
            val outcome = AtomicReference<String>()
            val config = GatewayConfig(true, false, "tcp://127.0.0.1:${server.localPort}", "", "test/fake")
            val listener = object : MqttPublisher.Listener {
                override fun onConnected(reconnecting: Boolean) { connected.countDown() }
                override fun onDisconnected(reason: String) { outcome.compareAndSet(null, "disconnected: $reason") }
                override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) {
                    outcome.set(if (success) "success" else "failed: $reason")
                    published.countDown()
                }
            }
            val publisher = directoryConstructor!!.newInstance(
                temporaryFolder.newFolder("paho"), config, MqttCredentials.from("", null), listener
            ) as MqttPublisher
            var source: VehicleSource? = null
            try {
                publisher.connect()
                assertTrue("MQTT connection timed out: ${outcome.get()}", connected.await(10, TimeUnit.SECONDS))
                val state = IndependentVehicleState()
                val key = "car.basic.vehicle_speed"
                val observedAt = Instant.parse("2026-01-01T00:00:00Z")
                val connection = object : VehicleSource.Connection {
                    override fun subscribe(keys: List<String>) { assertEquals(listOf(key), keys) }
                    override fun read(key: String): String = "42"
                    override fun close() = Unit
                }
                source = VehicleSource(listOf(key), VehicleSource.Transport { connection },
                    VehicleSource.Scheduler { _, _ -> VehicleSource.Cancel {} },
                    object : VehicleSource.Observer {
                        override fun onStatus(status: VehicleSource.Status) = Unit
                        override fun onValue(key: String, value: String) {
                            val envelope = state.observe(key, value, "test/fake/haval/1HGCM82633A004352", observedAt, 7L)
                            if (envelope != null) publisher.publish(envelope)
                        }
                    })
                source.start()
                val message = received.get(10, TimeUnit.SECONDS)
                assertTrue("Paho did not receive PUBACK", published.await(10, TimeUnit.SECONDS))
                assertEquals("success", outcome.get())
                assertEquals("test/fake/haval/1HGCM82633A004352/car.basic.vehicle_speed", message.topic)
                assertEquals(1, message.qos)
                assertFalse(message.retained)
                val json = JsonParser.parseString(message.payload).asJsonObject
                assertEquals("42", json.get("raw_value").asString)
                assertEquals("car.basic.vehicle_speed", json.get("key").asString)
                assertEquals("test/fake/haval/1HGCM82633A004352", json.get("vehicle_id").asString)
                assertEquals("2026-01-01T00:00:00Z", json.get("observed_at").asString)
                assertEquals(7L, json.get("sequence").asLong)
                assertEquals(1, json.get("schema_version").asInt)
                assertEquals(0, json.get("app_api_version").asInt)
                assertEquals("vehicle_oem_binder", json.get("source").asString)
                assertEquals("shizuku_oem_binder", json.get("source_trust").asString)
            } finally {
                source?.close()
                val disconnected = CountDownLatch(1)
                publisher.disconnect { disconnected.countDown() }
                assertTrue("Publisher shutdown timed out", disconnected.await(5, TimeUnit.SECONDS))
                executor.shutdownNow()
            }
        }
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
            check(multiplier <= 128 * 128 * 128 * 128) { "Invalid MQTT remaining length" }
        } while (digit and 128 != 0)
        val body = ByteArray(length)
        input.readFully(body)
        return header to body
    }
}
