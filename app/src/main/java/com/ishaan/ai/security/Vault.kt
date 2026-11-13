package com.ishaan.ai.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.crypto.SecretKey

data class StoredFile(val fileName: String, val originalName: String, val mimeType: String, val sizeBytes: Long)

/**
 * Encrypted storage for every document, photo and video. Originals are never modified;
 * they are encrypted byte-for-byte and can be decrypted back exactly.
 */
class Vault(private val context: Context, private val keyProvider: () -> SecretKey) {
    val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val viewDir get() = File(context.cacheDir, "view").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    fun importUri(uri: Uri, fallbackMime: String = "application/octet-stream"): StoredFile {
        val cr = context.contentResolver
        var name = "file"
        var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val mime = cr.getType(uri) ?: guessMime(name) ?: fallbackMime
        val stored = cr.openInputStream(uri)?.use { importStream(it) } ?: throw IllegalStateException("Cannot read the selected file")
        return StoredFile(stored, name, mime, size)
    }

    fun importStream(input: InputStream, existingName: String? = null): String {
        val name = existingName ?: (UUID.randomUUID().toString() + ".bin")
        val tmp = File(dir, "$name.part")
        tmp.outputStream().buffered().use { ChunkedAead.encrypt(input, it, keyProvider()) }
        val target = File(dir, name)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("Could not save file")
        return name
    }

    fun importFile(src: File): String = src.inputStream().buffered().use { importStream(it) }

    fun decryptTo(name: String, out: OutputStream) {
        File(dir, name).inputStream().buffered().use { ChunkedAead.decrypt(it, out, keyProvider()) }
    }

    fun readBytes(name: String): ByteArray = ByteArrayOutputStream().also { decryptTo(name, it) }.toByteArray()

    fun decodeBitmap(name: String, maxSide: Int = 1600): Bitmap? = runCatching {
        val bytes = readBytes(name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Decrypts to a short-lived file in private cache for viewing/sharing. Cleared on every app start and lock. */
    fun decryptToTemp(name: String, displayName: String): File {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        val f = File(viewDir, "${System.currentTimeMillis()}_$safe")
        f.outputStream().buffered().use { decryptTo(name, it) }
        return f
    }

    fun delete(name: String?) { if (name != null) File(dir, name).delete() }

    fun clearTemp() {
        viewDir.listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "capture").listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "share").listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    companion object {
        fun guessMime(name: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    }
}
