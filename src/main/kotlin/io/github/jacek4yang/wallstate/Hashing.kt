package io.github.jacek4yang.wallstate

import java.io.InputStream
import java.security.MessageDigest

/** Streaming SHA-256 helpers used to guarantee byte identity of wallpaper data. */
object Hashing {
    private const val BUFFER_SIZE = 128 * 1024
    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256HexOf(digest: MessageDigest): String = hex(digest.digest())

    fun isSha256Hex(value: String): Boolean {
        if (value.length != 64) return false
        for (c in value) {
            if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return false
        }
        return true
    }

    private fun hex(digest: ByteArray): String {
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0xf]
        }
        return String(out)
    }
}
