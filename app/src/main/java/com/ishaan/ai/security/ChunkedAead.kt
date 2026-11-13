package com.ishaan.ai.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Streaming AES-256-GCM encryption in 64 KB chunks.
 *
 * Why chunks: a whole-file GCM decrypt has to hold the entire file in memory, which would crash
 * on long urine/stool videos. Each chunk has its own random nonce and authentication tag; the
 * chunk index and a "last chunk" flag are authenticated too, so chunks cannot be reordered,
 * dropped or the file truncated without detection.
 *
 * Format: "IAE1" | repeat { flag(1) | nonce(12) | len(4) | ciphertext+tag(len) }
 */
object ChunkedAead {
    private val MAGIC = byteArrayOf('I'.code.toByte(), 'A'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    const val CHUNK = 64 * 1024
    private val random = SecureRandom()

    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val out = DataOutputStream(output)
        out.write(MAGIC)
        val buf = ByteArray(CHUNK)
        var index = 0L
        var n = readFully(input, buf)
        while (true) {
            // Look ahead one chunk so we know whether the current chunk is the last one.
            val next = ByteArray(CHUNK)
            val m = if (n == CHUNK) readFully(input, next) else 0
            val last = m <= 0
            val nonce = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(index, last))
            val ct = cipher.doFinal(buf, 0, maxOf(n, 0))
            out.writeByte(if (last) 1 else 0)
            out.write(nonce)
            out.writeInt(ct.size)
            out.write(ct)
            if (last) break
            System.arraycopy(next, 0, buf, 0, m)
            n = m
            index++
        }
        out.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val inp = DataInputStream(input)
        val magic = ByteArray(4)
        inp.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not an Ishaan AI encrypted file")
        var index = 0L
        while (true) {
            val flag = try { inp.readUnsignedByte() } catch (e: EOFException) { throw IOException("Encrypted file is truncated") }
            val last = flag == 1
            val nonce = ByteArray(12).also { inp.readFully(it) }
            val len = inp.readInt()
            if (len < 16 || len > CHUNK + 16) throw IOException("Corrupt encrypted chunk")
            val ct = ByteArray(len).also { inp.readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(index, last))
            output.write(cipher.doFinal(ct)) // throws AEADBadTagException if tampered or wrong key
            if (last) break
            index++
        }
        output.flush()
    }

    private fun aad(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
