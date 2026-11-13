package com.ishaan.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key hierarchy
 *  - A master AES-256 key lives inside the Android Keystore (hardware-backed where the phone supports it)
 *    and can never be exported.
 *  - Two random 256-bit secrets are generated once: the database passphrase and the file key.
 *    They are stored on disk only in wrapped (encrypted-by-master-key) form.
 *
 * Uses the platform Keystore directly rather than the deprecated androidx.security:security-crypto.
 */
class KeyManager(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "keys").apply { mkdirs() }

    fun databasePassphrase(): ByteArray = secret("db.key")
    fun fileKey(): SecretKey = SecretKeySpec(secret("files.key"), "AES")

    @Synchronized
    private fun secret(name: String): ByteArray {
        val f = File(dir, name)
        if (f.exists()) return unwrap(f.readBytes())
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(wrap(raw))
        if (!tmp.renameTo(f)) throw IllegalStateException("Could not store key")
        return raw
    }

    private fun master(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, master())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(raw)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, master(), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    companion object { private const val ALIAS = "ishaan_ai_master_v1" }
}
