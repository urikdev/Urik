package com.urik.keyboard.utils

import java.security.MessageDigest

/** SHA-256 hex digest for clipboard dedup. Output is always 64 lowercase hex chars. */
object ContentHasher {
    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            sb.append(HEX[byte.toInt() ushr 4 and 0x0F])
            sb.append(HEX[byte.toInt() and 0x0F])
        }
        return sb.toString()
    }
}
