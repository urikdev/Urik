package com.urik.keyboard.dictionary

import kotlin.math.exp
import kotlin.math.ln

internal object UrikFormat {
    val MAGIC = byteArrayOf(0x55, 0x52, 0x49, 0x4B)
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val MAX_FREQ = 30_000_000.0
    const val FREQ_BUCKETS = 254

    /**
     * Inverse of build-time quantizeFreq. Note: dequantize(254) returns ~28M, not MAX_FREQ,
     * due to log-scale rounding. Use only for relative ranking, not exact recovery.
     */
    fun dequantizeFreq(bucket: Int): Long {
        if (bucket == 0) return 0L
        return exp((bucket - 1).toDouble() * ln(MAX_FREQ) / FREQ_BUCKETS).toLong()
    }
}
