package com.crsmoro.havalmqttgateway

import android.content.Context
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicLong
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

internal class MqttPublisher @JvmOverloads constructor(
    persistenceDir: File,
    private val config: GatewayConfig,
    private val credentials: MqttCredentials,
    private val listener: Listener,
    private val discoveryAllowed: () -> Boolean = { true }
) {
    constructor(context: Context, config: GatewayConfig, credentials: MqttCredentials, listener: Listener, discoveryAllowed: () -> Boolean = { true }) :
        this(context.applicationContext.getDir("mqtt-persistence", Context.MODE_PRIVATE), config, credentials, listener, discoveryAllowed)
    interface Listener {
        fun onHomeAssistantBirth() {}
        fun onConnected(reconnecting: Boolean)
        fun onDisconnected(reason: String)
        fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String? = null)
    }

    private val client: MqttAsyncClient
    private val connecting = AtomicBoolean(false)
    private val discoveryGeneration = AtomicLong()
    private val discoveryDispatchLock = Any()
    private val subscriptionGeneration = AtomicLong()
    private val subscriptionRetry = AtomicReference<ScheduledFuture<*>?>()
    private val subscriptionLock = Any()

    init {
        client = MqttAsyncClient(
            config.brokerUri,
            if (config.clientInstanceId.isNotBlank()) MqttClientIdBuilder.fromInstallation(config.topicPrefix, config.clientInstanceId)
            else MqttClientIdBuilder.fromTopicPrefix(config.topicPrefix),
            MqttDefaultFilePersistence(persistenceDir.absolutePath)
        )
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                connecting.set(false)
                synchronized(discoveryDispatchLock) { discoveryGeneration.incrementAndGet() }
                val subscriptionEpoch = synchronized(subscriptionLock) {
                    subscriptionRetry.getAndSet(null)?.cancel(false)
                    subscriptionGeneration.incrementAndGet()
                }
                if (config.publishHomeAssistant) {
                    subscribeToStatus(subscriptionEpoch, 0)
                }
                listener.onConnected(reconnect)
            }

            override fun connectionLost(cause: Throwable?) {
                connecting.set(false)
                synchronized(discoveryDispatchLock) { discoveryGeneration.incrementAndGet() }
                synchronized(subscriptionLock) {
                    subscriptionGeneration.incrementAndGet()
                    subscriptionRetry.getAndSet(null)?.cancel(false)
                }
                listener.onDisconnected(cause?.message ?: "Conexão perdida")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (config.publishHomeAssistant && discoveryAllowed() && topic == "homeassistant/status" &&
                    message?.payload?.toString(Charsets.UTF_8) == "online") {
                    synchronized(discoveryDispatchLock) { discoveryGeneration.incrementAndGet() }
                    listener.onHomeAssistantBirth()
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
    }

    private fun subscribeToStatus(epoch: Long, attempt: Int) {
        if (epoch != subscriptionGeneration.get() || !client.isConnected || !discoveryAllowed()) return
        fun retry() {
            synchronized(subscriptionLock) {
                if (attempt + 1 >= STATUS_SUBSCRIBE_MAX_ATTEMPTS || epoch != subscriptionGeneration.get()) return
                val delay = STATUS_SUBSCRIBE_INITIAL_DELAY_MS shl attempt
                val scheduled = subscriptionRetries.schedule({ subscribeToStatus(epoch, attempt + 1) }, delay, TimeUnit.MILLISECONDS)
                subscriptionRetry.getAndSet(scheduled)?.cancel(false)
            }
        }
        try {
            client.subscribe("homeassistant/status", 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    val granted = asyncActionToken?.grantedQos
                    if (granted == null || granted.size != 1 || granted[0] !in 0..1) retry()
                }
                override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) = retry()
            })
        } catch (_: Throwable) { retry() }
    }

    fun connect() {
        if (client.isConnected) {
            listener.onConnected(reconnecting = false)
            return
        }
        if (!connecting.compareAndSet(false, true)) return
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            // Application queues and discovery replay handle retries. A persistent Paho
            // session could otherwise resend an unacknowledged deletion after opt-out.
            isCleanSession = true
            keepAliveInterval = KEEP_ALIVE_SECONDS
            connectionTimeout = CONNECT_TIMEOUT_SECONDS
            if (credentials.username.isNotBlank()) userName = credentials.username
            if (!credentials.password.isNullOrEmpty()) {
                password = credentials.password.toCharArray()
            }
        }
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) = Unit
                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?
                ) {
                    connecting.set(false)
                    listener.onDisconnected(exception?.message ?: "Falha ao conectar")
                }
            })
        } catch (t: Throwable) {
            connecting.set(false)
            listener.onDisconnected(t.message ?: "Falha ao iniciar conexão")
        }
    }

    fun isConnected(): Boolean = client.isConnected

    fun publish(envelope: TelemetryEnvelope) {
        val topic = MqttTopicBuilder.build(envelope.topicPrefix, envelope.key)
        if (topic == null) {
            listener.onPublishComplete(envelope, success = false, reason = "Tópico inválido")
            return
        }
        if (!client.isConnected) {
            listener.onPublishComplete(envelope, success = false, reason = "MQTT desconectado")
            return
        }
        val message = MqttMessage(envelope.toJson().toByteArray(Charsets.UTF_8)).apply {
            qos = QOS_AT_LEAST_ONCE
            isRetained = false
        }
        MqttPublishGuard.execute(
            publish = {
                client.publish(topic, message, envelope.key, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                        listener.onPublishComplete(envelope, success = true)
                    }

                    override fun onFailure(
                        asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                        exception: Throwable?
                    ) {
                        listener.onPublishComplete(
                            envelope,
                            success = false,
                            reason = exception?.message ?: "Falha ao publicar"
                        )
                    }
                })
            },
            onFailure = { error ->
                listener.onPublishComplete(
                    envelope,
                    success = false,
                    reason = error.message ?: "Falha síncrona ao publicar"
                )
            }
        )
    }

    fun publishDiscovery(message: HomeAssistantDiscovery.Message, onComplete: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        val timeout = AtomicReference<ScheduledFuture<*>?>()
        val generation = discoveryGeneration.get()
        fun complete(success: Boolean) {
            val first = synchronized(discoveryDispatchLock) { completed.compareAndSet(false, true) }
            if (first) {
                timeout.get()?.cancel(false)
                onComplete(success)
            }
        }
        val messages = try {
            val marker = "/binary_sensor/"
            if (!message.topic.contains(marker)) listOf(Triple(message.topic, message.payload, true))
            else {
                val json = JsonParser.parseString(message.payload).asJsonObject
                val stateTopic = json.get("state_topic").asString
                if (stateTopic.substringAfterLast('/') == "haval.power.ice") {
                    listOf(Triple(message.topic, message.payload, true))
                } else {
                    val legacy = JsonObject().apply {
                        addProperty("name", stateTopic.substringAfterLast('/'))
                        add("unique_id", json.get("unique_id").deepCopy())
                        add("state_topic", json.get("state_topic").deepCopy())
                        add("value_template", json.get("value_template").deepCopy())
                        add("device", json.get("device").deepCopy())
                    }
                    val legacyTopic = message.topic.replaceFirst(marker, "/sensor/")
                    listOf(Triple(legacyTopic, legacy.toString(), false), Triple(legacyTopic, "", true),
                        Triple(message.topic, message.payload, true))
                }
            }
        } catch (_: Throwable) { complete(false); return }
        fun send(index: Int) {
            if (completed.get()) return
            val allowed = DiscoveryDispatchGate.dispatch(discoveryAllowed) {
                synchronized(discoveryDispatchLock) {
                    if (completed.get()) return@dispatch
                    if (generation != discoveryGeneration.get() || !client.isConnected) {
                        complete(false); return@dispatch
                    }
                    if (index == messages.size) { complete(true); return@dispatch }
                    val (topic, payload, retained) = messages[index]
                    try {
                        val mqttMessage = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                            qos = QOS_AT_LEAST_ONCE
                            isRetained = retained
                        }
                        client.publish(topic, mqttMessage, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) = send(index + 1)
                            override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) = complete(false)
                        })
                    } catch (_: Throwable) { complete(false) }
                }
            }
            if (!allowed) complete(false)
        }
        timeout.set(discoveryTimeouts.schedule({ complete(false) }, DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        send(0)
    }

    fun disconnect(onComplete: () -> Unit = {}) {
        synchronized(discoveryDispatchLock) { discoveryGeneration.incrementAndGet() }
        synchronized(subscriptionLock) {
            subscriptionGeneration.incrementAndGet()
            subscriptionRetry.getAndSet(null)?.cancel(false)
        }
        Thread({
            try {
                runCatching {
                    MqttClientShutdown.disconnectThenClose(
                        disconnect = {
                            if (client.isConnected) {
                                client.disconnect(DISCONNECT_TIMEOUT_MS).waitForCompletion(DISCONNECT_TIMEOUT_MS)
                            }
                        },
                        close = { runCatching { client.close() } }
                    )
                }
            } finally {
                onComplete()
            }
        }, "MqttGatewayDisconnect").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private val discoveryTimeouts = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "MqttDiscoveryTimeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val subscriptionRetries = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "MqttStatusSubscribeRetry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private const val STATUS_SUBSCRIBE_MAX_ATTEMPTS = 5
        private const val STATUS_SUBSCRIBE_INITIAL_DELAY_MS = 1_000L
        private const val DISCOVERY_TIMEOUT_SECONDS = 10L
        private const val KEEP_ALIVE_SECONDS = 30
        private const val CONNECT_TIMEOUT_SECONDS = 10
        private const val DISCONNECT_TIMEOUT_MS = 2_000L
        private const val QOS_AT_LEAST_ONCE = 1
    }
}
