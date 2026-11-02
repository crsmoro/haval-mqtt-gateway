package com.crsmoro.havalmqttgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/** Main-thread-owned last observations, limited to explicitly selected vehicle properties. */
internal class IndependentVehicleState {
    private val observations = LinkedHashMap<String, TelemetryEnvelope>()
    private val ice = VehicleIceTelemetry()
    private val flow = VehiclePowerFlowTelemetry()
    fun snapshot(): Map<String, String> = observations.mapValues { it.value.rawValue }
    fun effectiveTopicPrefix(prefix: String): String? {
        val vin = observations["car.basic.vin_code"]?.rawValue
        return VehicleTopicIdentity.build(prefix, vin, "car.basic.vin_code")?.removeSuffix("/car.basic.vin_code")
    }
    fun invalidDerivedKeys(pending: Set<String>): Set<String> =
        pending.intersect(setOf("haval.power.ice", "haval.power.flow")) - observations.keys
    fun observeFlow(key: String, value: String, prefix: String, time: Instant, sequence: Long): TelemetryEnvelope? {
        val derived = flow.observe(key, value)
        if (!flow.available) observations.remove("haval.power.flow")
        if (derived == null) return null
        return TelemetryEnvelope(prefix, "haval.power.flow", derived, time, sequence, 0,
            source = "vehicle_derived", sourceTrust = "derived_from_shizuku_oem_binder").also {
            observations[it.key] = it
        }
    }
    fun observeDerived(key: String, value: String, prefix: String, time: Instant, sequence: Long): TelemetryEnvelope? {
        val derived = ice.observe(key, value)
        if (key == "car.basic.engine_speed" && derived == null) observations.remove("haval.power.ice")
        if (derived == null) return null
        return TelemetryEnvelope(prefix, "haval.power.ice", derived, time, sequence, 0,
            source = "vehicle_derived", sourceTrust = "derived_from_shizuku_oem_binder").also {
            observations[it.key] = it
        }
    }
    fun observe(key: String, value: String, prefix: String, time: Instant, sequence: Long): TelemetryEnvelope? {
        if (key == "car.basic.vin_code" && !VehicleTopicIdentity.isValidVin(value)) {
            observations.remove(key)
        }
        if (key !in keys || !TelemetryKeyPolicy.isPublishableValue(value)) return null
        return TelemetryEnvelope(prefix, key, value, time, sequence, 0,
            source = "vehicle_oem_binder", sourceTrust = "shizuku_oem_binder").also {
            observations[key] = it
        }
    }
    fun replay(prefix: String, firstSequence: Long): List<TelemetryEnvelope> =
        observations.values.mapIndexed { index, value ->
            value.copy(topicPrefix = prefix, sequence = firstSequence + index, cached = true)
        }
    fun invalidateDerived() {
        ice.clear()
        flow.clear()
        observations.remove("haval.power.ice")
        observations.remove("haval.power.flow")
    }
    fun invalidateValue(key: String) {
        if (key == "car.basic.vin_code") {
            clear()
            return
        }
        observations.remove(key)
        observeDerived(key, "", "", Instant.EPOCH, 0)
        observeFlow(key, "", "", Instant.EPOCH, 0)
    }
    fun onSourceStatus(status: VehicleSource.Status) {
        if (status != VehicleSource.Status.CONNECTED) clear()
    }
    fun clear() { observations.clear(); invalidateDerived() }

    companion object {
        // Never subscribe to the entire catalog: many non-default names are actions.
        // Deletion-related "rst" identifiers could be results or resets; leave them
        // out until the OEM contract proves they are safe to read and subscribe.
        private val ambiguousDeletionKeys = setOf(
            "car.dms.frs.dele_dms_facedb_rst", "car.dms.frs.dele_oms_facedb_rst"
        )
        private val previouslySelectedKeys = listOf(
            "car.basic.vin_code", "car.basic.vehicle_speed", "car.basic.engine_speed",
            "car.basic.engine_state", "car.basic.gear_status", "car.basic.total_odometer",
            "car.basic.accumulated_odometer", "car.basic.remain_odometer",
            "car.basic.remain_fuel_percentage", "car.basic.battery_voltage",
            "car.basic.battery_power_level", "car.basic.coolant_temp",
            "car.basic.avg_fuel_consumption", "car.basic.instant_fuel_consumption",
            "car.basic.inside_temp", "car.basic.outside_temp", "car.basic.door_status",
            "car.basic.door_lock_status", "car.basic.window_status", "car.basic.sunroof_status",
            "car.basic.seated_state", "car.basic.seat_belt_warning", "car.basic.tpms_status",
            "car.basic.tpms_units", "car.basic.tpms_warning", "car.basic.steering_wheel_angle",
            "car.ev_info.battery_charge_percentage", "car.ev_info.charging_state",
            "car.ev_info.cur_charge_current", "car.ev_info.power_battery_current",
            "car.ev_info.power_battery_voltage", "car.ev_info.soc_of_battery",
            "car.ev_info.motor_power", "car.ev_info.motor_speed", "car.ev_info.rear_motor_speed",
            "car.dms.feedback.distracted_state", "car.dms.feedback.fatigue_driving_state",
            "car.dms.frs.driver_age", "car.dms.frs.driver_gender", "car.dms.frs.gaze_area",
            "car.oms.feedback.age_state", "car.oms.feedback.gender_state",
            "car.oms.feedback.expression_state"
        )
        val keys: List<String> = (previouslySelectedKeys + VehicleKeyCatalog.entries.asSequence()
            .filter { it.source == VehicleKeyCatalog.Source.OEM_PROPERTY && (it.defaultMonitored || it.sensitive) }
            .map { it.key }.toList())
            .filterNot { it in ambiguousDeletionKeys }
            .distinct()
            .also { selected ->
                require(selected.all { VehicleSource.permitsKey(it) && TelemetryKeyPolicy.isPublishable(it) })
            }
    }
}

class GatewayService : Service(), MqttPublisher.Listener {
    private val configStore by lazy { GatewayConfigStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong(System.currentTimeMillis() * 1_000_000L)
    private val publisherGeneration = PublisherGeneration()
    private val vehicleState = IndependentVehicleState()
    private val queueLock = Any()
    private val pendingByKey = LinkedHashMap<String, TelemetryEnvelope>()
    private val pendingBudget = PendingTelemetryBudget(MAX_PENDING_KEYS, MAX_PENDING_BYTES)
    private val publishRetryTracker = PublishRetryTracker(MAX_PUBLISH_ATTEMPTS)
    private val discoveryTracker = DiscoveryDeliveryTracker()
    private var discoveryInFlight = false
    private var discoveryEpoch = 0L
    private var failedDiscoveryTopic: String? = null
    private val retryBackoff = RetryBackoff(INITIAL_RETRY_MS, MAX_RETRY_MS)

    @Volatile private var publisher: MqttPublisher? = null
    @Volatile private var reconfiguring = false
    @Volatile private var stopped = false
    private var vehicleSource: VehicleSourceAndroid? = null
    private var lastValidVin: String? = null
    private var sourceGeneration = 0L
    @Volatile private var connected = false
    @Volatile private var apiReady = false
    private var publishInFlight: TelemetryEnvelope? = null
    @Volatile private var currentStatusText: String? = null

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
        startVehicleSource()
        if (PublisherRestartPolicy.shouldRestart(intent?.action, publisherExists = publisher != null)) {
            reconfigurePublisher(config)
        } else {
            connect(config)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConnected(reconnecting: Boolean) {
        if (stopped) return
        val wasConnected = connected
        connected = true
        discoveryEpoch++
        discoveryInFlight = false
        discoveryTracker.reset()
        failedDiscoveryTopic = null
        if (!wasConnected) retryBackoff.reset()
        updateStatus(
            when {
                !apiReady -> "MQTT conectado; aguardando fonte veicular/Shizuku"
                reconnecting -> "Reconectado; sincronizando"
                else -> "Conectado; sincronizando"
            }
        )
        replaySnapshot()
        pumpQueue()
        if (configStore.load().publishHomeAssistant) scheduleHomeAssistantReplay(5_000L)
    }

    override fun onDisconnected(reason: String) {
        if (stopped) return
        connected = false
        discoveryEpoch++
        discoveryInFlight = false
        discoveryTracker.reset()
        failedDiscoveryTopic = null
        mainHandler.removeCallbacks(homeAssistantReplayRunnable)
        synchronized(queueLock) { publishInFlight = null }
        updateStatus("MQTT desconectado")
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
        if (success) {
            retryBackoff.reset()
            updateStatus(if (failedDiscoveryTopic != null) "Dados publicados; descoberta Home Assistant pendente (verifique ACL)" else "Conectado; dados publicados")
            pumpQueue()
        } else if (droppedAfterRetries) {
            retryBackoff.reset()
            updateStatus("Estado não aceito pelo broker após várias tentativas; seguindo para próxima chave")
            pumpQueue()
        } else {
            updateStatus("Falha ao publicar no MQTT")
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
            updateStatus("Falha ao iniciar MQTT")
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
        discoveryEpoch++
        discoveryInFlight = false
        discoveryTracker.reset()
        failedDiscoveryTopic = null
        mainHandler.removeCallbacks(homeAssistantReplayRunnable)
        synchronized(queueLock) { publishInFlight = null }
        updateStatus("Aplicando novas configurações MQTT")
        oldPublisher.disconnect {
            mainHandler.post {
                reconfiguring = false
                if (!stopped && configStore.load().enabled) connect(configStore.load())
            }
        }
    }

    private fun startVehicleSource() {
        if (vehicleSource != null) return
        val generation = ++sourceGeneration
        try {
            val source = VehicleSourceAndroid(applicationContext, IndependentVehicleState.keys,
                object : VehicleSource.Observer {
                    override fun onStatus(status: VehicleSource.Status) {
                        mainHandler.post {
                            if (stopped || sourceGeneration != generation) return@post
                            apiReady = status == VehicleSource.Status.CONNECTED
                            vehicleState.onSourceStatus(status)
                            if (!apiReady) {
                                discardPending()
                            }
                            val message = when (status) {
                                VehicleSource.Status.CONNECTED -> if (connected) "Fonte veicular e MQTT conectados" else "Fonte veicular conectada; aguardando MQTT"
                                VehicleSource.Status.CONNECTING -> "Conectando à fonte veicular"
                                VehicleSource.Status.SHIZUKU_UNAVAILABLE -> "Inicie o Shizuku na central"
                                VehicleSource.Status.PERMISSION_DENIED -> "Autorize o gateway no Shizuku pela tela do aplicativo"
                                VehicleSource.Status.SERVICE_UNAVAILABLE -> "Serviço veicular indisponível nesta central"
                                VehicleSource.Status.ERROR -> "Falha ao ler a fonte veicular; tentando novamente"
                                VehicleSource.Status.CLOSED -> "Fonte veicular parada"
                            }
                            updateStatus(message)
                        }
                    }
                    override fun onValue(key: String, value: String) {
                        mainHandler.post {
                            if (stopped || sourceGeneration != generation) return@post
                            if (key == "car.basic.vin_code" && !VehicleTopicIdentity.isValidVin(value)) {
                                vehicleState.invalidateValue(key)
                                discardPending()
                                return@post
                            }
                            val prefix = configStore.load().topicPrefix
                            val oldVin = lastValidVin
                            if (key == "car.basic.vin_code" && VehicleTopicIdentity.isValidVin(oldVin) &&
                                VehicleTopicIdentity.isValidVin(value) && oldVin != value) {
                                vehicleState.clear()
                                discardPending()
                            }
                            if (key == "car.basic.vin_code" && VehicleTopicIdentity.isValidVin(value)) {
                                lastValidVin = value
                            }
                            val envelope = vehicleState.observe(key, value,
                                MqttTopicBuilder.normalizePrefix(prefix) ?: prefix,
                                Instant.now(), sequence.incrementAndGet()) ?: return@post
                            enqueue(envelope)
                            vehicleState.observeDerived(key, value, envelope.topicPrefix, envelope.observedAt,
                                sequence.incrementAndGet())?.let(::enqueue)
                            vehicleState.observeFlow(key, value, envelope.topicPrefix, envelope.observedAt,
                                sequence.incrementAndGet())?.let(::enqueue)
                            discardInvalidDerived()
                            pumpQueue()
                        }
                    }
                    override fun onInvalidValue(key: String) {
                        mainHandler.post {
                            if (stopped || sourceGeneration != generation) return@post
                            vehicleState.invalidateValue(key)
                            if (key == "car.basic.vin_code") discardPending()
                            else {
                                synchronized(queueLock) {
                                    pendingByKey.remove(key)?.let { publishRetryTracker.recordSuccess(it.sequence) }
                                    pendingBudget.remove(key)
                                }
                                discardInvalidDerived()
                            }
                        }
                    }
                })
            vehicleSource = source
            source.start()
        } catch (_: RuntimeException) {
            updateStatus("Não foi possível iniciar a fonte veicular")
        }
    }

    /** Republish last observations, not a fresh or atomic OEM read. */
    private fun replaySnapshot() {
        val count = vehicleState.snapshot().size
        if (count == 0) return
        val first = sequence.getAndAdd(count.toLong()) + 1
        val prefix = configStore.load().topicPrefix
        vehicleState.replay(MqttTopicBuilder.normalizePrefix(prefix) ?: prefix, first).forEach(::enqueue)
    }

    private fun closeVehicleSource() {
        ++sourceGeneration
        val source = vehicleSource
        vehicleSource = null
        apiReady = false
        vehicleState.clear()
        lastValidVin = null
        source?.close()
    }

    private fun discardInvalidDerived() {
        synchronized(queueLock) {
            vehicleState.invalidDerivedKeys(pendingByKey.keys).forEach { key ->
                pendingByKey.remove(key)?.let { publishRetryTracker.recordSuccess(it.sequence) }
                pendingBudget.remove(key)
            }
        }
    }

    private fun discardPending() {
        synchronized(queueLock) {
            pendingByKey.values.forEach { publishRetryTracker.recordSuccess(it.sequence) }
            pendingByKey.clear()
            pendingBudget.clear()
        }
    }

    private fun enqueue(envelope: TelemetryEnvelope) {
        synchronized(queueLock) {
            // Coalesce offline bursts to the latest state per key.
            pendingByKey.remove(envelope.key)
            pendingByKey[envelope.key] = envelope
            val evicted = pendingBudget.put(envelope.key, envelope.rawValue)
            evicted.forEach(pendingByKey::remove)
            if (evicted.isNotEmpty()) Log.w(TAG, "Pending MQTT state budget reached; dropped ${evicted.size} oldest key(s)")
        }
    }

    private fun pumpQueue() {
        if (stopped) return
        val config = configStore.load()
        val effectivePrefix = vehicleState.effectiveTopicPrefix(config.topicPrefix)
        if (effectivePrefix == null) {
            if (connected) updateStatus("MQTT conectado; aguardando VIN válido")
            return
        }
        if (!connected || discoveryInFlight) return
        val next = synchronized(queueLock) {
            if (publishInFlight != null) return
            pendingByKey.values.firstOrNull() ?: return
        }
        if (config.publishHomeAssistant) {
            val vin = vehicleState.snapshot()["car.basic.vin_code"] ?: return
            val root = MqttTopicBuilder.normalizePrefix(config.topicPrefix) ?: return
            val message = HomeAssistantDiscovery.build(vin, next.key, "$root/haval")
            if (message != null && discoveryTracker.shouldAttempt(message.topic, message.payload, android.os.SystemClock.elapsedRealtime())) {
                val activePublisher = publisher ?: return
                val epoch = discoveryEpoch
                discoveryInFlight = true
                activePublisher.publishDiscovery(message) { success ->
                    mainHandler.post {
                        if (stopped || epoch != discoveryEpoch || publisher !== activePublisher) return@post
                        discoveryInFlight = false
                        if (success) {
                            if (failedDiscoveryTopic == message.topic) failedDiscoveryTopic = null
                            scheduleHomeAssistantReplay(5_000L)
                        } else {
                            failedDiscoveryTopic = message.topic
                            Log.w(TAG, "Home Assistant discovery publish failed; check broker ACL; retry in 60s")
                            updateStatus("Falha na descoberta Home Assistant; verifique ACL do broker")
                            scheduleHomeAssistantReplay(DiscoveryDeliveryTracker.RETRY_DELAY_MS)
                        }
                        // A discovery failure must not block telemetry or use MQTT reconnect backoff.
                        discoveryTracker.completeAndContinue(message.topic, message.payload, success, android.os.SystemClock.elapsedRealtime()) {
                            pumpQueue()
                        }
                    }
                }
                return
            }
        }
        synchronized(queueLock) { publishInFlight = next }
        publisher?.publish(next.copy(topicPrefix = effectivePrefix)) ?: run {
            synchronized(queueLock) { publishInFlight = null }
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (stopped || !configStore.load().enabled) return
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, retryBackoff.nextDelay())
    }

    /** A newly discovered HA entity may subscribe after the first non-retained state. */
    private fun scheduleHomeAssistantReplay(delayMs: Long) {
        mainHandler.removeCallbacks(homeAssistantReplayRunnable)
        mainHandler.postDelayed(homeAssistantReplayRunnable, delayMs)
    }

    private val homeAssistantReplayRunnable = Runnable {
        if (!stopped && configStore.load().publishHomeAssistant) {
            if (HomeAssistantReplayPolicy.shouldReplay(
                    enabled = true, connected = connected, sourceReady = apiReady,
                    vin = vehicleState.snapshot()["car.basic.vin_code"]
                )) {
                replaySnapshot()
                pumpQueue()
            }
            if (connected) scheduleHomeAssistantReplay(60_000L)
        }
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
            updateStatus("Reconexão MQTT falhou")
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
        mainHandler.removeCallbacks(homeAssistantReplayRunnable)
        closeVehicleSource()
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
        mainHandler.removeCallbacks(homeAssistantReplayRunnable)
        closeVehicleSource()
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
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_API_READY = "api_ready"
        private const val CHANNEL_ID = "haval_mqtt_gateway"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_PENDING_KEYS = 2048
        private const val MAX_PENDING_BYTES = 1_048_576L
        private const val MAX_PUBLISH_ATTEMPTS = 5
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
    }
}
