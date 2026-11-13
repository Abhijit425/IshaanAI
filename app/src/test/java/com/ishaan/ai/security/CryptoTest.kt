package com.ishaan.ai.security

import com.ishaan.ai.data.BackupService
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import javax.crypto.KeyGenerator
import kotlin.random.Random

class CryptoTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun roundTrip(size: Int) {
        val data = Random(size).nextBytes(size)
        val enc = ByteArrayOutputStream().also { ChunkedAead.encrypt(ByteArrayInputStream(data), it, key) }.toByteArray()
        val dec = ByteArrayOutputStream().also { ChunkedAead.decrypt(ByteArrayInputStream(enc), it, key) }.toByteArray()
        assertArrayEquals("size $size", data, dec)
    }

    @Test fun roundTripsAllSizes() { listOf(0, 1, 100, ChunkedAead.CHUNK - 1, ChunkedAead.CHUNK, ChunkedAead.CHUNK + 1, 3 * ChunkedAead.CHUNK + 17).forEach { roundTrip(it) } }

    @Test fun detectsTampering() {
        val enc = ByteArrayOutputStream().also { ChunkedAead.encrypt(ByteArrayInputStream(ByteArray(1000) { 7 }), it, key) }.toByteArray()
        enc[enc.size - 5] = (enc[enc.size - 5].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { ChunkedAead.decrypt(ByteArrayInputStream(enc), ByteArrayOutputStream(), key) }
    }

    @Test fun detectsTruncation() {
        val enc = ByteArrayOutputStream().also { ChunkedAead.encrypt(ByteArrayInputStream(ByteArray(3 * ChunkedAead.CHUNK)), it, key) }.toByteArray()
        val cut = enc.copyOf(4 + 2 * (1 + 12 + 4 + ChunkedAead.CHUNK + 16)) // drop the last chunk
        assertThrows(Exception::class.java) { ChunkedAead.decrypt(ByteArrayInputStream(cut), ByteArrayOutputStream(), key) }
    }

    @Test fun backupContainerRoundTripAndWrongPassword() {
        val out = ByteArrayOutputStream()
        BackupService.writeEncrypted(out, "correct horse".toCharArray()) { z ->
            z.putNextEntry(ZipEntry("manifest.txt")); z.write("schema=1".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("vault/a.bin")); z.write(ByteArray(200_000) { (it % 251).toByte() }); z.closeEntry()
        }
        val seen = mutableMapOf<String, Int>()
        BackupService.readEncrypted(ByteArrayInputStream(out.toByteArray()), "correct horse".toCharArray()) { e, s -> seen[e.name] = s.readBytes().size }
        assertEquals(mapOf("manifest.txt" to 8, "vault/a.bin" to 200_000), seen)
        assertThrows(IOException::class.java) {
            BackupService.readEncrypted(ByteArrayInputStream(out.toByteArray()), "wrong".toCharArray()) { _, s -> s.readBytes() }
        }
    }
}
