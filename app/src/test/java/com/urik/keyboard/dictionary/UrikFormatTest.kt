package com.urik.keyboard.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrikFormatTest {
    @Test
    fun `magic bytes spell URIK`() {
        val magic = UrikFormat.MAGIC
        assertEquals(0x55.toByte(), magic[0])
        assertEquals(0x52.toByte(), magic[1])
        assertEquals(0x49.toByte(), magic[2])
        assertEquals(0x4B.toByte(), magic[3])
    }

    @Test
    fun `dequantize bucket 1 returns low freq`() {
        val freq = UrikFormat.dequantizeFreq(1)
        assertTrue("Bucket 1 should return low freq, got $freq", freq in 1L..1000L)
    }

    @Test
    fun `dequantize bucket 254 returns near max freq`() {
        val freq = UrikFormat.dequantizeFreq(254)
        assertTrue("Bucket 254 should return near max, got $freq", freq > 10_000_000L)
    }

    @Test
    fun `dequantize bucket 0 returns 0`() {
        assertEquals(0L, UrikFormat.dequantizeFreq(0))
    }

    @Test
    fun `higher buckets dequantize to higher frequencies`() {
        val low = UrikFormat.dequantizeFreq(50)
        val mid = UrikFormat.dequantizeFreq(127)
        val high = UrikFormat.dequantizeFreq(200)
        assertTrue(low < mid)
        assertTrue(mid < high)
    }
}
