package com.crsmoro.havalmqttgateway

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class MqttPublisher(
    persistenceDir: File,
    private val config: GatewayConfig,
    private val credentials: MqttCredentials,
    private val listener: Listener
) {
    constructor(context: Context, config: GatewayConfig, credentials: MqttCredentials, listener: Listener) :
        this(context.applicationContext.getDir("mqtt-persistence", Context.MODE_PRIVATE), config, credentials, listener)
    interface Listener {
        fun onConnected(reconnecting: Boolean)
        fun onDisconnected(reason: String)
        fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String? = null)
    }

    private val client: MqttAsyncClient
    private val connecting = AtomicBoolean(false)

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
                listener.onConnected(reconnect)
            }

            override fun connectionLost(cause: Throwable?) {
                connecting.set(false)
                listener.onDisconnected(cause?.message ?: "Conexão perdida")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                // This client intentionally never subscribes to command topics.
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
    }

    fun connect() {
        if (client.isConnected) {
            listener.onConnected(reconnecting = false)
            return
        }
        if (!connecting.compareAndSet(false, true)) return
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = false
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
        fun complete(success: Boolean) {
            if (completed.compareAndSet(false, true)) onComplete(success)
        }
        try {
            if (!client.isConnected) {
                complete(false)
                return
            }
            val mqttMessage = MqttMessage(message.payload.toByteArray(Charsets.UTF_8)).apply {
                qos = message.qos
                isRetained = message.retained
            }
            client.publish(message.topic, mqttMessage, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    complete(true)
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?
                ) {
                    complete(false)
                }
            })
        } catch (_: Throwable) {
            complete(false)
        }
    }

    fun disconnect(onComplete: () -> Unit = {}) {
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
        private const val KEEP_ALIVE_SECONDS = 30
        private const val CONNECT_TIMEOUT_SECONDS = 10
        private const val DISCONNECT_TIMEOUT_MS = 2_000L
        private const val QOS_AT_LEAST_ONCE = 1
    }
}
