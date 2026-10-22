package com.crsmoro.havalmqttgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

class GatewayService : Service(), MqttPublisher.Listener {
    private val configStore by lazy { GatewayConfigStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong(System.currentTimeMillis() * 1_000_000L)
    private val publisherGeneration = PublisherGeneration()
    private val apiVersionGuard = ApiVersionGuard(SUPPORTED_API_VERSION)
    private val queueLock = Any()
    private val pendingByKey = LinkedHashMap<String, TelemetryEnvelope>()
    private val pendingBudget = PendingTelemetryBudget(MAX_PENDING_KEYS, MAX_PENDING_BYTES)
    private val inputRateLimiter = TelemetryRateLimiter(MAX_EVENTS_PER_SECOND, RATE_WINDOW_MS)
    private val publishRetryTracker = PublishRetryTracker(MAX_PUBLISH_ATTEMPTS)
    private val retryBackoff = RetryBackoff(INITIAL_RETRY_MS, MAX_RETRY_MS)

    @Volatile private var publisher: MqttPublisher? = null
    @Volatile private var reconfiguring = false
    @Volatile private var stopped = false
    private var receiverRegistered = false
    @Volatile private var connected = false
    @Volatile private var sourceApiVersion = 0
    @Volatile private var apiReady = false
    private var publishInFlight: TelemetryEnvelope? = null
    @Volatile private var currentStatusText: String? = null

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (stopped) return
            if (intent.action != ACTION_EVENT_CHANGED) return
            if (!inputRateLimiter.tryAcquire(SystemClock.elapsedRealtime())) return
            val event = runCatching {
                val extras = intent.extras ?: return@runCatching null
                TelemetryBroadcastParser.parse(extras.getString(EXTRA_KEY), extras.getString(EXTRA_VALUE))
            }.getOrNull() ?: return
            val key = event.key
            val value = event.value

            if (key == API_VERSION_KEY) {
                handleApiVersion(value)
                return
            }
            if (!TelemetryFeedPolicy.canEnqueue(apiReady, sourceApiVersion, SUPPORTED_API_VERSION)) return
            if (!TelemetryKeyPolicy.isPublishable(key) || !TelemetryKeyPolicy.isPublishableValue(value)) return

            val envelope = TelemetryEnvelope(
                vehicleId = configStore.load().vehicleId,
                key = key,
                rawValue = value,
                observedAt = Instant.now(),
                sequence = sequence.incrementAndGet(),
                apiVersion = sourceApiVersion
            )
            enqueue(envelope)
            pumpQueue()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopGateway()
            return START_NOT_STICKY
        }

        stopped = false

        val config = configStore.load()
        if (!config.enabled) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Iniciando conexão"))
        registerVehicleReceiver()
        if (PublisherRestartPolicy.shouldRestart(intent?.action, publisherExists = publisher != null)) {
            reconfigurePublisher(config)
        } else {
            connect(config)
        }
        requestSnapshot()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConnected(reconnecting: Boolean) {
        if (stopped) return
        val wasConnected = connected
        connected = true
        if (!wasConnected) retryBackoff.reset()
        updateStatus(
            when {
                !apiReady -> "MQTT conectado; aguardando Haval App Tool/API v1"
                reconnecting -> "Reconectado; sincronizando"
                else -> "Conectado; sincronizando"
            }
        )
        requestSnapshot()
        pumpQueue()
    }

    override fun onDisconnected(reason: String) {
        if (stopped) return
        connected = false
        synchronized(queueLock) { publishInFlight = null }
        updateStatus("Desconectado: ${reason.take(MAX_STATUS_DETAIL)}")
        scheduleRetry()
    }

    override fun onPublishComplete(envelope: TelemetryEnvelope, success: Boolean, reason: String?) {
        if (stopped) return
        var droppedAfterRetries = false
        synchronized(queueLock) {
            val current = pendingByKey[envelope.key]
            if (current?.sequence == envelope.sequence) {
                if (success) {
                    pendingByKey.remove(envelope.key)
                    pendingBudget.remove(envelope.key)
                    publishRetryTracker.recordSuccess(envelope.sequence)
                } else if (publishRetryTracker.recordFailure(envelope.sequence)) {
                    pendingByKey.remove(envelope.key)
                    pendingBudget.remove(envelope.key)
                    droppedAfterRetries = true
                }
            }
            if (publishInFlight?.sequence == envelope.sequence) publishInFlight = null
        }
        if (!apiReady) return
        if (success) {
            retryBackoff.reset()
            updateStatus("Conectado; dados publicados")
            pumpQueue()
        } else if (droppedAfterRetries) {
            retryBackoff.reset()
            updateStatus("Estado não aceito pelo broker após várias tentativas; seguindo para próxima chave")
            pumpQueue()
        } else {
            updateStatus("Falha ao publicar: ${(reason ?: "erro MQTT").take(MAX_STATUS_DETAIL)}")
            scheduleRetry()
        }
    }

    private fun connect(config: GatewayConfig) {
        if (!PublisherRestartPolicy.shouldCreate(publisherExists = publisher != null, reconfiguring = reconfiguring)) return
        if (!config.brokerUri.startsWith("ssl://") && !config.brokerUri.startsWith("tcp://")) {
            updateStatus("URI inválida; use ssl:// ou tcp://")
            return
        }
        val storedPassword = configStore.password()
        if (MqttTransportPolicy.credentialsRequireTls(
                config.brokerUri,
                config.username,
                storedPassword != null
            )
        ) {
            updateStatus("Credenciais MQTT exigem TLS; use ssl:// para proteger o tráfego")
            return
        }
        val credentials = MqttCredentials.from(config.username, storedPassword)
        try {
            val generation = publisherGeneration.next()
            val newPublisher = MqttPublisher(
                this,
                config,
                credentials,
                object : MqttPublisher.Listener {
                    override fun onConnected(reconnecting: Boolean) {
                        mainHandler.post {
                            if (!stopped && publisherGeneration.isCurrent(generation)) {
                                this@GatewayService.onConnected(reconnecting)
                            }
                        }
                    }

                    override fun onDisconnected(reason: String) {
                        mainHandler.post {
                            if (!stopped && publisherGeneration.isCurrent(generation)) {
                                this@GatewayService.onDisconnected(reason)
                            }
                        }
                    }

                    override fun onPublishComplete(
                        envelope: TelemetryEnvelope,
                        success: Boolean,
                        reason: String?
                    ) {
                        mainHandler.post {
                            if (!stopped && publisherGeneration.isCurrent(generation)) {
                                this@GatewayService.onPublishComplete(envelope, success, reason)
                            }
                        }
                    }
                }
            )
            publisher = newPublisher
            updateStatus("Conectando ao broker")
            newPublisher.connect()
        } catch (t: Throwable) {
            Log.e(TAG, "MQTT client initialization failed", t)
            updateStatus("Falha ao iniciar MQTT: ${(t.message ?: "erro").take(MAX_STATUS_DETAIL)}")
            scheduleRetry()
        }
    }

    private fun reconfigurePublisher(config: GatewayConfig) {
        if (reconfiguring) return
        val oldPublisher = publisher ?: run {
            connect(config)
            return
        }
        reconfiguring = true
        publisherGeneration.invalidate()
        publisher = null
        connected = false
        synchronized(queueLock) { publishInFlight = null }
        updateStatus("Aplicando novas configurações MQTT")
        oldPublisher.disconnect {
            mainHandler.post {
                reconfiguring = false
                if (!stopped && configStore.load().enabled) connect(configStore.load())
            }
        }
    }

    private fun registerVehicleReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_EVENT_CHANGED)
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun requestSnapshot() {
        if (!receiverRegistered) return
        val request = Intent(ACTION_REQUEST_SNAPSHOT)
            .putExtra(EXTRA_REQUESTER, packageName)
        sendBroadcast(request)
    }

    private fun handleApiVersion(rawValue: String) {
        val wasReady = apiReady
        val decision = apiVersionGuard.evaluate(rawValue)
        sourceApiVersion = decision.version
        apiReady = decision.ready
        if (!decision.accepted) {
            if (wasReady && !decision.ready) {
                synchronized(queueLock) {
                    pendingByKey.clear()
                    pendingBudget.clear()
                    publishInFlight = null
                }
            }
            if (decision.reportIncompatible) updateStatus("API do produtor incompatível")
            return
        }
        if (!wasReady && decision.ready) requestSnapshot()
        updateStatus(if (connected) "API v1; sincronizando" else "API v1; aguardando MQTT")
        pumpQueue()
    }

    private fun enqueue(envelope: TelemetryEnvelope) {
        synchronized(queueLock) {
            // Coalesce offline bursts to the latest state per key; a snapshot is requested
            // at startup and after reconnect so the server can resynchronize current values.
            pendingByKey.remove(envelope.key)
            pendingByKey[envelope.key] = envelope
            val evicted = pendingBudget.put(envelope.key, envelope.rawValue)
            evicted.forEach(pendingByKey::remove)
            if (evicted.isNotEmpty()) Log.w(TAG, "Pending MQTT state budget reached; dropped ${evicted.size} oldest key(s)")
        }
    }

    private fun pumpQueue() {
        if (stopped) return
        val next = synchronized(queueLock) {
            if (!connected || !apiReady || publishInFlight != null) return
            val envelope = pendingByKey.values.firstOrNull() ?: return
            publishInFlight = envelope
            envelope
        }
        publisher?.publish(next) ?: run {
            synchronized(queueLock) { publishInFlight = null }
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (stopped || !configStore.load().enabled) return
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, retryBackoff.nextDelay())
    }

    private val retryRunnable = Runnable {
        if (!configStore.load().enabled) return@Runnable
        try {
            val currentPublisher = publisher
            if (currentPublisher == null) {
                connect(configStore.load())
            } else if (connected && currentPublisher.isConnected()) {
                pumpQueue()
            } else {
                currentPublisher.connect()
            }
        } catch (t: Throwable) {
            updateStatus("Reconexão falhou: ${(t.message ?: "erro").take(MAX_STATUS_DETAIL)}")
            scheduleRetry()
        }
    }

    private fun updateStatus(message: String) {
        if (currentStatusText == message) return
        currentStatusText = message
        GatewayStatusStore.save(this, message, connected, apiReady)
        val status = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, message)
            .putExtra(EXTRA_CONNECTED, connected)
            .putExtra(EXTRA_API_READY, apiReady)
        sendBroadcast(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(message))
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Parar", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Haval MQTT Gateway",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun stopGateway() {
        stopped = true
        configStore.setEnabled(false)
        publisherGeneration.invalidate()
        mainHandler.removeCallbacks(retryRunnable)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(eventReceiver) }
            receiverRegistered = false
        }
        val oldPublisher = publisher
        publisher = null
        oldPublisher?.disconnect()
        connected = false
        synchronized(queueLock) {
            pendingByKey.clear()
            pendingBudget.clear()
            publishInFlight = null
        }
        updateStatus("Parado")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        publisherGeneration.invalidate()
        mainHandler.removeCallbacks(retryRunnable)
        if (receiverRegistered) runCatching { unregisterReceiver(eventReceiver) }
        val oldPublisher = publisher
        publisher = null
        oldPublisher?.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HavalMqttGateway"
        const val ACTION_START = "com.crsmoro.havalmqttgateway.START"
        const val ACTION_STOP = "com.crsmoro.havalmqttgateway.STOP"
        const val ACTION_STATUS = "com.crsmoro.havalmqttgateway.STATUS"
        private const val ACTION_EVENT_CHANGED = "com.haval.vehicle.EVENT_CHANGED"
        private const val ACTION_REQUEST_SNAPSHOT = "com.haval.vehicle.REQUEST_SNAPSHOT"
        private const val API_VERSION_KEY = "app.impulse.api_version"
        private const val SUPPORTED_API_VERSION = 1
        private const val EXTRA_KEY = "key"
        private const val EXTRA_VALUE = "value"
        private const val EXTRA_REQUESTER = "requester"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_API_READY = "api_ready"
        private const val CHANNEL_ID = "haval_mqtt_gateway"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_PENDING_KEYS = 2048
        private const val MAX_PENDING_BYTES = 1_048_576L
        private const val MAX_EVENTS_PER_SECOND = 500
        private const val RATE_WINDOW_MS = 1_000L
        private const val MAX_PUBLISH_ATTEMPTS = 5
        private const val MAX_STATUS_DETAIL = 120
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
    }
}
