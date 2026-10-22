package com.crsmoro.havalmqttgateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.net.URI

class MainActivity : Activity() {
    private lateinit var store: GatewayConfigStore
    private lateinit var brokerUri: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var topicPrefix: EditText
    private lateinit var autoStart: CheckBox
    private lateinit var clearPassword: CheckBox
    private lateinit var statusView: TextView
    private var statusReceiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != GatewayService.ACTION_STATUS) return
            statusView.text = intent.getStringExtra(GatewayService.EXTRA_STATUS) ?: "Estado indisponível"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GatewayConfigStore(this)
        val config = store.load()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            text = "Gateway somente de saída: recebe a telemetria pública do Haval App Tool e publica no broker. Instale e inicie o Haval App Tool na mesma central."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        })

        brokerUri = addField(content, "Broker URI (ssl://host:8883 ou tcp://host:1883)", config.brokerUri)
        username = addField(content, "Usuário MQTT (opcional)", config.username)
        password = addField(content, "Senha MQTT (armazenada no Android Keystore)", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        clearPassword = CheckBox(this).apply {
            text = "Apagar a senha salva"
            isChecked = false
        }
        content.addView(clearPassword)
        topicPrefix = addField(content, "Prefixo de tópicos", config.topicPrefix)
        content.addView(TextView(this).apply {
            text = "Tópico exemplo: ${config.topicPrefix}/${config.vehicleId}/car.basic.vehicle_speed"
            textSize = 12f
            setPadding(0, dp(3), 0, dp(10))
        })
        autoStart = CheckBox(this).apply {
            text = "Iniciar automaticamente após reiniciar a central"
            isChecked = config.autoStart
        }
        content.addView(autoStart)
        content.addView(TextView(this).apply {
            text = "Privacidade: exclui VIN, DMS/FRS/OMS, app/sys e chaves de ação conhecidas por nome; o produtor não tipa leitura versus comando, então o filtro não é completo. O broadcast local não autentica o emissor; trate os dados como não confiáveis, nunca como sinal de segurança veicular."
            textSize = 12f
            setPadding(0, dp(8), 0, dp(6))
        })
        content.addView(TextView(this).apply {
            text = "Sem rede, coalesce o último valor por chave em fila volátil limitada a 1 MiB/2.048 chaves e pede snapshot ao reconectar. Entrada acima de 500 eventos/s é descartada; não é histórico durável."
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        statusView = TextView(this).apply {
            text = GatewayStatusStore.load(
                this@MainActivity,
                if (config.enabled) "Gateway habilitado; aguardando serviço" else "Gateway parado"
            )
            textSize = 16f
            setPadding(0, dp(10), 0, dp(10))
        }
        content.addView(statusView)

        content.addView(Button(this).apply {
            text = "Salvar e iniciar gateway"
            setOnClickListener { saveAndStart() }
        })
        content.addView(Button(this).apply {
            text = "Parar gateway"
            setOnClickListener { stopGateway() }
        })
        content.addView(TextView(this).apply {
            text = "ID pseudônimo desta central: ${config.vehicleId}"
            textSize = 11f
            setPadding(0, dp(14), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        if (!statusReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                IntentFilter(GatewayService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            statusReceiverRegistered = true
        }
        statusView.text = GatewayStatusStore.load(this, statusView.text.toString())
    }

    override fun onPause() {
        if (statusReceiverRegistered) {
            runCatching { unregisterReceiver(statusReceiver) }
            statusReceiverRegistered = false
        }
        super.onPause()
    }

    private fun saveAndStart() {
        val uri = brokerUri.text.toString().trim()
        val parsed = runCatching { URI(uri) }.getOrNull()
        if (
            parsed == null || parsed.scheme !in setOf("ssl", "tcp") || parsed.host.isNullOrBlank() ||
            parsed.userInfo != null || !parsed.path.isNullOrEmpty() || parsed.query != null || parsed.fragment != null
        ) {
            showError("URI inválida. Informe ssl://host:8883 ou tcp://host:1883, sem usuário/senha na URI.")
            return
        }
        val current = store.load()
        val next = GatewayConfig(
            enabled = true,
            autoStart = autoStart.isChecked,
            brokerUri = uri,
            username = username.text.toString().trim(),
            topicPrefix = topicPrefix.text.toString().trim(),
            vehicleId = current.vehicleId
        )
        if (MqttTopicBuilder.build(next.topicPrefix, next.vehicleId, "car.basic.vehicle_speed") == null) {
            showError("Prefixo de tópico inválido. Não use +, #, espaços ou segmentos vazios.")
            return
        }
        val passwordValue = password.text.toString()
        val hasConfiguredPassword = !clearPassword.isChecked &&
            (passwordValue.isNotEmpty() || store.password() != null)
        if (MqttTransportPolicy.credentialsRequireTls(uri, next.username, hasConfiguredPassword)) {
            showError("Credenciais MQTT exigem TLS. Use ssl:// ou remova usuário e senha antes de usar tcp://.")
            return
        }
        val secretUpdate = when {
            clearPassword.isChecked -> ""
            passwordValue.isNotEmpty() -> passwordValue
            else -> null // Blank means keep the currently stored credential.
        }
        if (!store.save(next, secretUpdate)) {
            showError("Não foi possível proteger a senha no Android Keystore; o gateway não foi iniciado.")
            return
        }
        password.setText("")
        clearPassword.isChecked = false
        ContextCompat.startForegroundService(
            this,
            Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        )
        statusView.text = if (uri.startsWith("tcp://")) {
            "Iniciando sem TLS — use apenas em rede confiável"
        } else {
            "Iniciando conexão TLS"
        }
    }

    private fun stopGateway() {
        startService(Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_STOP))
        statusView.text = "Parando gateway"
    }

    private fun addField(parent: LinearLayout, label: String, value: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(0, dp(8), 0, dp(3))
        })
        return EditText(this).apply {
            setText(value)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            parent.addView(this)
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        statusView.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
