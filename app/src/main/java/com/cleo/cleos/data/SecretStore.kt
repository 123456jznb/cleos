package com.cleo.cleos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretsStore by preferencesDataStore(name = "secrets")

/**
 * The API key, encrypted with an AES key that lives in the Android Keystore.
 *
 * The keystore key cannot be exported, so the stored ciphertext is useless anywhere but
 * this install on this phone. That is also why the file is excluded from backups (see
 * data_extraction_rules.xml): a restored copy could only ever fail to decrypt.
 * A failed decrypt is treated as "no key", so the user is asked again rather than the
 * app crashing.
 */
class SecretStore(private val context: Context) {
    private val apiKeyPref = stringPreferencesKey("api_key")

    val hasApiKey: Flow<Boolean> = context.secretsStore.data.map { !it[apiKeyPref].isNullOrEmpty() }

    suspend fun apiKey(): String? {
        val stored = context.secretsStore.data.first()[apiKeyPref] ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { Log.w(TAG, "stored API key could not be decrypted; treating as unset", it) }
            .getOrNull()
    }

    suspend fun setApiKey(value: String?) {
        val trimmed = value?.trim().orEmpty()
        context.secretsStore.edit {
            if (trimmed.isEmpty()) it.remove(apiKeyPref) else it[apiKeyPref] = encrypt(trimmed)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(":", limit = 2).let { unb64(it[0]) to unb64(it[1]) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val TAG = "SecretStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "cleos_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
