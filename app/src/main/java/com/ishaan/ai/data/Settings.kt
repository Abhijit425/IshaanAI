package com.ishaan.ai.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore by preferencesDataStore("ishaan_settings")

data class AppSettings(
    val pinSet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autoLockSeconds: Int = 60,
    val secureScreen: Boolean = true,
    val darkMode: String = "SYSTEM",   // SYSTEM, LIGHT, DARK
    val largeText: Boolean = false,
    val llmModelFile: String? = null,  // file name inside app storage
    val acknowledgedDisclaimer: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val bio = booleanPreferencesKey("bio")
        val autoLock = intPreferencesKey("auto_lock")
        val secure = booleanPreferencesKey("secure_screen")
        val dark = stringPreferencesKey("dark")
        val large = booleanPreferencesKey("large_text")
        val llm = stringPreferencesKey("llm_file")
        val ack = booleanPreferencesKey("ack_disclaimer")
        val failed = intPreferencesKey("failed_attempts")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = context.dataStore.data.first().toSettings()

    private fun Preferences.toSettings() = AppSettings(
        pinSet = this[K.pinHash] != null,
        biometricEnabled = this[K.bio] ?: false,
        autoLockSeconds = this[K.autoLock] ?: 60,
        secureScreen = this[K.secure] ?: true,
        darkMode = this[K.dark] ?: "SYSTEM",
        largeText = this[K.large] ?: false,
        llmModelFile = this[K.llm],
        acknowledgedDisclaimer = this[K.ack] ?: false,
    )

    suspend fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        context.dataStore.edit {
            it[K.pinSalt] = Base64.getEncoder().encodeToString(salt)
            it[K.pinHash] = Base64.getEncoder().encodeToString(PinHasher.hash(pin, salt))
            it[K.failed] = 0
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val p = context.dataStore.data.first()
        val salt = p[K.pinSalt]?.let { Base64.getDecoder().decode(it) } ?: return false
        val expected = p[K.pinHash]?.let { Base64.getDecoder().decode(it) } ?: return false
        val ok = MessageDigest.isEqual(PinHasher.hash(pin, salt), expected)
        context.dataStore.edit { it[K.failed] = if (ok) 0 else (it[K.failed] ?: 0) + 1 }
        return ok
    }

    suspend fun failedAttempts(): Int = context.dataStore.data.first()[K.failed] ?: 0

    suspend fun setBiometric(v: Boolean) = context.dataStore.edit { it[K.bio] = v }
    suspend fun setAutoLock(sec: Int) = context.dataStore.edit { it[K.autoLock] = sec }
    suspend fun setSecureScreen(v: Boolean) = context.dataStore.edit { it[K.secure] = v }
    suspend fun setDarkMode(v: String) = context.dataStore.edit { it[K.dark] = v }
    suspend fun setLargeText(v: Boolean) = context.dataStore.edit { it[K.large] = v }
    suspend fun setLlmModel(file: String?) = context.dataStore.edit { if (file == null) it.remove(K.llm) else it[K.llm] = file }
    suspend fun acknowledgeDisclaimer() = context.dataStore.edit { it[K.ack] = true }
}

object PinHasher {
    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
