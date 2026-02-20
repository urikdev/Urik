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
    fun `isWordSeparatingPunctuation detects right single quotation mark`() {
        assertTrue(TextMatchingUtils.isWordSeparatingPunctuation('\u2019'))
    }

    @Test
    fun `isWordSeparatingPunctuation detects single high-reversed-9 quotation mark`() {
        assertTrue(TextMatchingUtils.isWordSeparatingPunctuation('\u201B'))
    }

    @Test
    fun `isWordSeparatingPunctuation detects modifier letter apostrophe`() {
        assertTrue(TextMatchingUtils.isWordSeparatingPunctuation('\u02BC'))
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
    fun `isValidWordPunctuation detects right single quotation mark`() {
        assertTrue(TextMatchingUtils.isValidWordPunctuation('\u2019'))
    }

    @Test
    fun `isValidWordPunctuation detects modifier letter apostrophe`() {
        assertTrue(TextMatchingUtils.isValidWordPunctuation('\u02BC'))
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
    fun `stripWordPunctuation strips right single quotation mark`() {
        val result = TextMatchingUtils.stripWordPunctuation("don\u2019t")
        assertEquals("dont", result)
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
    fun `stripWordPunctuation strips modifier letter apostrophe`() {
        val result = TextMatchingUtils.stripWordPunctuation("Hawai\u02BCi")
        assertEquals("Hawaii", result)
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

    @Test
    fun `isContractionSuggestion returns true for dont to don't`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("dont", "don't"))
    }

    @Test
    fun `isContractionSuggestion returns true for havent to haven't`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("havent", "haven't"))
    }

    @Test
    fun `isContractionSuggestion returns true for cant to can't`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("cant", "can't"))
    }

    @Test
    fun `isContractionSuggestion returns true for youre to you're`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("youre", "you're"))
    }

    @Test
    fun `isContractionSuggestion returns true for im to I'm case insensitive`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("im", "I'm"))
    }

    @Test
    fun `isContractionSuggestion returns true for well to we'll`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("well", "we'll"))
    }

    @Test
    fun `isContractionSuggestion returns false for reverse direction don't to dont`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("don't", "dont"))
    }

    @Test
    fun `isContractionSuggestion returns false for reverse direction haven't to havent`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("haven't", "havent"))
    }

    @Test
    fun `isContractionSuggestion returns false for completely different words`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("hello", "world"))
    }

    @Test
    fun `isContractionSuggestion returns false when both have apostrophes`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("don't", "can't"))
    }

    @Test
    fun `isContractionSuggestion returns false when neither has apostrophes`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("hello", "hello"))
    }

    @Test
    fun `isContractionSuggestion returns false for empty input`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("", "don't"))
    }

    @Test
    fun `isContractionSuggestion returns false for empty candidate`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("dont", ""))
    }

    @Test
    fun `isContractionSuggestion returns false for both empty`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("", ""))
    }

    @Test
    fun `isContractionSuggestion returns true for French l'homme from lhomme`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("lhomme", "l'homme"))
    }

    @Test
    fun `isContractionSuggestion returns true for German Ost-Berlin from ostberlin`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("ostberlin", "Ost-Berlin"))
    }

    @Test
    fun `isContractionSuggestion returns false when input already has hyphen`() {
        assertFalse(TextMatchingUtils.isContractionSuggestion("co-worker", "coworker"))
    }

    @Test
    fun `isContractionSuggestion returns true for hyphen suggestion coworker to co-worker`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("coworker", "co-worker"))
    }

    @Test
    fun `isContractionSuggestion handles case insensitivity`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("DONT", "don't"))
        assertTrue(TextMatchingUtils.isContractionSuggestion("dont", "DON'T"))
        assertTrue(TextMatchingUtils.isContractionSuggestion("DoNt", "DoN't"))
    }

    @Test
    fun `isContractionSuggestion works with curly apostrophe`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("dont", "don\u2019t"))
    }

    @Test
    fun `isContractionSuggestion works with French clitic curly apostrophe`() {
        assertTrue(TextMatchingUtils.isContractionSuggestion("lhomme", "l\u2019homme"))
    }
}
