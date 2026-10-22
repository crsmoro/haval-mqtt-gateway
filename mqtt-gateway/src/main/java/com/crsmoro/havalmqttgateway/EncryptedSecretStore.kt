package com.crsmoro.havalmqttgateway

import android.content.Context
import android.util.Base64
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the broker password encrypted with an AES key that stays in Android Keystore. */
internal class EncryptedSecretStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("mqtt_gateway_secrets", Context.MODE_PRIVATE)

    fun save(password: String): Boolean {
        if (password.isEmpty()) {
            prefs.edit().remove(PASSWORD_KEY).apply()
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            prefs.edit().putString(PASSWORD_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
            true
        }.onFailure { Log.e(TAG, "Could not encrypt broker credential", it) }
            .getOrDefault(false)
    }

    fun read(): String? {
        val encoded = prefs.getString(PASSWORD_KEY, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_SIZE) { "Encrypted credential is truncated" }
            val iv = packed.copyOfRange(0, IV_SIZE)
            val encrypted = packed.copyOfRange(IV_SIZE, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.onFailure { Log.e(TAG, "Could not decrypt broker credential; re-enter it in settings", it) }
            .getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "GatewaySecretStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "haval_mqtt_gateway_password_v1"
        private const val PASSWORD_KEY = "broker_password_gcm"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
