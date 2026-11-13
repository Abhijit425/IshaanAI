package com.ishaan.ai.data

import android.content.Context
import android.net.Uri
import com.ishaan.ai.data.db.AppDatabase
import com.ishaan.ai.data.db.BackupMetadata
import com.ishaan.ai.security.ChunkedAead
import com.ishaan.ai.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Encrypted backup file (.ishaanbackup)
 *   "ISHAANBK" | version(1) | salt(16) | iterations(int) | ChunkedAead( ZIP{ manifest.txt, database.db, vault/<files> } )
 * The key comes from the parent's backup password (PBKDF2-HMAC-SHA256). Without the password the file is unreadable.
 * The backup is independent of this phone's keystore, so it can be restored on a new phone.
 */
class BackupService(private val context: Context, private val db: AppDatabase, private val vault: Vault, private val fileKey: () -> SecretKey) {

    companion object {
        private val MAGIC = "ISHAANBK".toByteArray()
        private const val FORMAT = 1
        private const val ITER = 150_000
        fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITER): SecretKey {
            val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded
            return SecretKeySpec(k, "AES")
        }

        fun writeEncrypted(out: OutputStream, password: CharArray, writeZip: (ZipOutputStream) -> Unit) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)
            val dos = DataOutputStream(out)
            dos.write(MAGIC); dos.writeByte(FORMAT); dos.write(salt); dos.writeInt(ITER); dos.flush()
            val pin = PipedInputStream(1 shl 16)
            val pout = PipedOutputStream(pin)
            var err: Throwable? = null
            val t = thread(name = "backup-zip") {
                try { ZipOutputStream(pout).use { writeZip(it) } } catch (e: Throwable) { err = e; runCatching { pout.close() } }
            }
            ChunkedAead.encrypt(pin, out, key)
            t.join()
            err?.let { throw it }
            out.flush()
        }

        /** Decrypts and hands each zip entry to [onEntry]. Throws IOException("Wrong password or damaged backup") on failure. */
        fun readEncrypted(input: InputStream, password: CharArray, onEntry: (ZipEntry, InputStream) -> Unit) {
            val dis = DataInputStream(input)
            val magic = ByteArray(8); dis.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("This is not an Ishaan AI backup file.")
            val fmt = dis.readUnsignedByte(); if (fmt != FORMAT) throw IOException("Unsupported backup format $fmt.")
            val salt = ByteArray(16); dis.readFully(salt)
            val iter = dis.readInt()
            val key = deriveKey(password, salt, iter)
            val pin = PipedInputStream(1 shl 16)
            val pout = PipedOutputStream(pin)
            var err: Throwable? = null
            val t = thread(name = "backup-decrypt") {
                try { ChunkedAead.decrypt(dis, pout, key) } catch (e: Throwable) { err = e } finally { runCatching { pout.close() } }
            }
            try {
                ZipInputStream(pin).use { zip ->
                    while (true) { val e = zip.nextEntry ?: break; onEntry(e, zip) }
                    // Drain any trailing bytes so the decrypting thread can finish and verify the final chunk.
                    val buf = ByteArray(8192); while (pin.read(buf) >= 0) { }
                }
            } catch (e: IOException) {
                if (err == null) throw e
            }
            t.join()
            err?.let { throw IOException("Wrong password or damaged backup file.", it) }
        }
    }

    data class Result(val ok: Boolean, val message: String, val bytes: Long = 0)

    private fun counts(): Map<String, Long> = AppDatabase.TABLES_IN_ORDER.associateWith { t ->
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$t`").use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
    }

    suspend fun backup(uri: Uri, password: CharArray): Result = withContext(Dispatchers.IO) {
        val export = File(context.cacheDir, "backup_export.db")
        try {
            export.delete()
            val sdb = db.openHelper.writableDatabase
            sdb.execSQL("ATTACH DATABASE '${export.absolutePath}' AS plaintext KEY ''")
            try {
                sdb.query("SELECT sqlcipher_export('plaintext')").use { it.moveToFirst() }
                sdb.execSQL("PRAGMA plaintext.user_version = ${AppDatabase.VERSION}")
            } finally { sdb.execSQL("DETACH DATABASE plaintext") }
            val counts = counts()
            val files = vault.dir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") } ?: emptyList()
            var written = 0L
            context.contentResolver.openOutputStream(uri, "w")!!.use { raw ->
                val counting = object : OutputStream() {
                    override fun write(b: Int) { raw.write(b); written++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { raw.write(b, off, len); written += len }
                    override fun flush() = raw.flush()
                }
                writeEncrypted(counting, password) { zip ->
                    zip.putNextEntry(ZipEntry("manifest.txt"))
                    zip.write(buildString {
                        appendLine("app=IshaanAI"); appendLine("schema=${AppDatabase.VERSION}"); appendLine("created=${System.currentTimeMillis()}")
                        appendLine("files=${files.size}"); counts.forEach { (k, v) -> appendLine("count.$k=$v") }
                    }.toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("database.db")); export.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    for (f in files) {
                        zip.putNextEntry(ZipEntry("vault/${f.name}"))
                        f.inputStream().buffered().use { ChunkedAead.decrypt(it, zip, fileKey()) }
                        zip.closeEntry()
                    }
                }
            }
            val total = counts.values.sum().toInt()
            db.planDao().insertBackupMeta(BackupMetadata(type = "BACKUP", location = uri.lastPathSegment ?: uri.toString(), sizeBytes = written, recordCount = total, notes = "${files.size} files"))
            Result(true, "Backup saved: $total records and ${files.size} documents/photos/videos.", written)
        } catch (t: Throwable) {
            runCatching { db.planDao().insertBackupMeta(BackupMetadata(type = "BACKUP", status = "FAILED", notes = t.message ?: t.javaClass.simpleName)) }
            Result(false, "Backup failed: ${t.message ?: t.javaClass.simpleName}")
        } finally { export.delete() }
    }

    /**
     * Restores everything from a backup, replacing the current records. Nothing is changed until the whole file
     * has been decrypted and checked; then the database is replaced in one transaction.
     */
    suspend fun restore(uri: Uri, password: CharArray): Result = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val stagedVault = File(staging, "vault").apply { mkdirs() }
        val stagedDb = File(staging, "database.db")
        try {
            var manifest = ""
            context.contentResolver.openInputStream(uri)!!.buffered().use { input ->
                readEncrypted(input, password) { e, zin ->
                    when {
                        e.name == "manifest.txt" -> manifest = zin.readBytes().decodeToString()
                        e.name == "database.db" -> stagedDb.outputStream().use { zin.copyTo(it) }
                        e.name.startsWith("vault/") -> {
                            val name = e.name.removePrefix("vault/")
                            if (name.contains('/') || name.contains("..")) throw IOException("Invalid file in backup")
                            // Re-encrypt with this phone's file key as it streams in.
                            File(stagedVault, name).outputStream().buffered().use { ChunkedAead.encrypt(NonClosing(zin), it, fileKey()) }
                        }
                    }
                }
            }
            val schema = Regex("schema=(\\d+)").find(manifest)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@withContext Result(false, "Backup is incomplete (no manifest).")
            if (schema > AppDatabase.VERSION) return@withContext Result(false, "This backup was made by a newer version of Ishaan AI. Update the app first.")
            if (schema < AppDatabase.VERSION) return@withContext Result(false, "This backup uses an older data format ($schema) that this version cannot import yet.")
            if (!stagedDb.exists()) return@withContext Result(false, "Backup is incomplete (no database).")

            val sdb = db.openHelper.writableDatabase
            sdb.execSQL("ATTACH DATABASE '${stagedDb.absolutePath}' AS bk KEY ''")
            try {
                sdb.beginTransaction()
                try {
                    sdb.execSQL("PRAGMA defer_foreign_keys = ON")
                    AppDatabase.TABLES_IN_ORDER.reversed().forEach { sdb.execSQL("DELETE FROM main.`$it`") }
                    AppDatabase.TABLES_IN_ORDER.forEach { sdb.execSQL("INSERT INTO main.`$it` SELECT * FROM bk.`$it`") }
                    sdb.setTransactionSuccessful()
                } finally { sdb.endTransaction() }
            } finally { sdb.execSQL("DETACH DATABASE bk") }

            vault.dir.listFiles()?.forEach { it.delete() }
            stagedVault.listFiles()?.forEach { f -> f.copyTo(File(vault.dir, f.name), overwrite = true) }
            db.planDao().insertBackupMeta(BackupMetadata(type = "RESTORE", location = uri.lastPathSegment ?: "", notes = manifest.lines().filter { it.startsWith("created=") }.joinToString()))
            Result(true, "Restore complete. The app will now restart.")
        } catch (t: Throwable) {
            Result(false, t.message ?: "Restore failed.")
        } finally { staging.deleteRecursively() }
    }

    private class NonClosing(private val inner: InputStream) : InputStream() {
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
        override fun close() {}
    }
}
