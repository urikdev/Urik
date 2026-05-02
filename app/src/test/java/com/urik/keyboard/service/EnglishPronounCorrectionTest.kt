package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnglishPronounCorrectionTest {
    @Test
    fun `capitalize returns I for i`() {
        assertEquals("I", EnglishPronounCorrection.capitalize("i"))
    }

    @Test
    fun `capitalize returns Im for im`() {
        assertEquals("I'm", EnglishPronounCorrection.capitalize("i'm"))
    }

    @Test
    fun `capitalize returns Ill for ill`() {
        assertEquals("I'll", EnglishPronounCorrection.capitalize("i'll"))
    }

    @Test
    fun `capitalize returns Ive for ive`() {
        assertEquals("I've", EnglishPronounCorrection.capitalize("i've"))
    }

    @Test
    fun `capitalize returns Id for id`() {
        assertEquals("I'd", EnglishPronounCorrection.capitalize("i'd"))
    }

    @Test
    fun `capitalize returns null for non-pronoun word`() {
        assertNull(EnglishPronounCorrection.capitalize("hello"))
    }
}
