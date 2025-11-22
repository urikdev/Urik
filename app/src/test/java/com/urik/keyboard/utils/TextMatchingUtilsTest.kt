package com.urik.keyboard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchingUtilsTest {
    @Test
    fun `isWordSeparatingPunctuation detects ASCII hyphen`() {
        assertTrue(TextMatchingUtils.isWordSeparatingPunctuation('-'))
    }

    @Test
    fun `isWordSeparatingPunctuation detects en dash`() {
        assertTrue(TextMatchingUtils.isWordSeparatingPunctuation('–'))
    }

    @Test
    fun `isWordSeparatingPunctuation detects em dash`() {
        assertTrue(TextMatchingUtils.isWordSeparatingPunctuation('—'))
    }

    @Test
    fun `isWordSeparatingPunctuation detects ASCII apostrophe`() {
        assertTrue(TextMatchingUtils.isWordSeparatingPunctuation('\''))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for letter`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation('a'))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for digit`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation('5'))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for period`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation('.'))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for comma`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation(','))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for exclamation mark`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation('!'))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for question mark`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation('?'))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for space`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation(' '))
    }

    @Test
    fun `isWordSeparatingPunctuation returns false for right single quotation mark`() {
        assertFalse(TextMatchingUtils.isWordSeparatingPunctuation('\u2019'))
    }

    @Test
    fun `isValidWordPunctuation detects ASCII hyphen`() {
        assertTrue(TextMatchingUtils.isValidWordPunctuation('-'))
    }

    @Test
    fun `isValidWordPunctuation detects en dash`() {
        assertTrue(TextMatchingUtils.isValidWordPunctuation('–'))
    }

    @Test
    fun `isValidWordPunctuation detects em dash`() {
        assertTrue(TextMatchingUtils.isValidWordPunctuation('—'))
    }

    @Test
    fun `isValidWordPunctuation detects ASCII apostrophe`() {
        assertTrue(TextMatchingUtils.isValidWordPunctuation('\''))
    }

    @Test
    fun `isValidWordPunctuation returns false for letter`() {
        assertFalse(TextMatchingUtils.isValidWordPunctuation('a'))
    }

    @Test
    fun `isValidWordPunctuation returns false for digit`() {
        assertFalse(TextMatchingUtils.isValidWordPunctuation('5'))
    }

    @Test
    fun `isValidWordPunctuation returns false for period`() {
        assertFalse(TextMatchingUtils.isValidWordPunctuation('.'))
    }

    @Test
    fun `isValidWordPunctuation returns false for comma`() {
        assertFalse(TextMatchingUtils.isValidWordPunctuation(','))
    }

    @Test
    fun `isValidWordPunctuation returns false for space`() {
        assertFalse(TextMatchingUtils.isValidWordPunctuation(' '))
    }

    @Test
    fun `stripWordPunctuation handles empty string`() {
        val result = TextMatchingUtils.stripWordPunctuation("")
        assertEquals("", result)
    }

    @Test
    fun `stripWordPunctuation handles string with no punctuation`() {
        val result = TextMatchingUtils.stripWordPunctuation("hello")
        assertEquals("hello", result)
    }

    @Test
    fun `stripWordPunctuation removes ASCII apostrophe`() {
        val result = TextMatchingUtils.stripWordPunctuation("don't")
        assertEquals("dont", result)
    }

    @Test
    fun `stripWordPunctuation preserves right single quotation mark`() {
        val result = TextMatchingUtils.stripWordPunctuation("don\u2019t")
        assertEquals("don\u2019t", result)
    }

    @Test
    fun `stripWordPunctuation removes ASCII hyphen`() {
        val result = TextMatchingUtils.stripWordPunctuation("co-worker")
        assertEquals("coworker", result)
    }

    @Test
    fun `stripWordPunctuation removes en dash`() {
        val result = TextMatchingUtils.stripWordPunctuation("co–worker")
        assertEquals("coworker", result)
    }

    @Test
    fun `stripWordPunctuation removes multiple punctuation marks`() {
        val result = TextMatchingUtils.stripWordPunctuation("it's-fine")
        assertEquals("itsfine", result)
    }

    @Test
    fun `stripWordPunctuation removes all apostrophes`() {
        val result = TextMatchingUtils.stripWordPunctuation("'hello'")
        assertEquals("hello", result)
    }

    @Test
    fun `stripWordPunctuation removes all hyphens`() {
        val result = TextMatchingUtils.stripWordPunctuation("-word-")
        assertEquals("word", result)
    }

    @Test
    fun `stripWordPunctuation preserves other punctuation`() {
        val result = TextMatchingUtils.stripWordPunctuation("hello.")
        assertEquals("hello.", result)
    }

    @Test
    fun `stripWordPunctuation preserves letters and digits`() {
        val result = TextMatchingUtils.stripWordPunctuation("test123")
        assertEquals("test123", result)
    }

    @Test
    fun `stripWordPunctuation handles word with mixed punctuation`() {
        val result = TextMatchingUtils.stripWordPunctuation("don't-know")
        assertEquals("dontknow", result)
    }

    @Test
    fun `stripWordPunctuation preserves modifier letter apostrophe`() {
        val result = TextMatchingUtils.stripWordPunctuation("Hawaiʻi")
        assertEquals("Hawaiʻi", result)
    }

    @Test
    fun `stripWordPunctuation removes only ASCII apostrophe`() {
        val result = TextMatchingUtils.stripWordPunctuation("test'word")
        assertEquals("testword", result)
    }

    @Test
    fun `stripWordPunctuation preserves German umlauts`() {
        val result = TextMatchingUtils.stripWordPunctuation("über")
        assertEquals("über", result)
    }

    @Test
    fun `stripWordPunctuation preserves Spanish ñ`() {
        val result = TextMatchingUtils.stripWordPunctuation("mañana")
        assertEquals("mañana", result)
    }

    @Test
    fun `stripWordPunctuation preserves French accents`() {
        val result = TextMatchingUtils.stripWordPunctuation("café")
        assertEquals("café", result)
    }
}
