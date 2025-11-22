@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackspaceUtilsTest {
    @Test
    fun `extractWordBeforeCursor extracts last word`() {
        val result = BackspaceUtils.extractWordBeforeCursor("hello world test")

        assertNotNull(result)
        assertEquals("test", result!!.first)
        assertEquals(11, result.second)
    }

    @Test
    fun `extractWordBeforeCursor handles single word`() {
        val result = BackspaceUtils.extractWordBeforeCursor("hello")

        assertNotNull(result)
        assertEquals("hello", result!!.first)
        assertEquals(-1, result.second)
    }

    @Test
    fun `extractWordBeforeCursor handles word after punctuation`() {
        val result = BackspaceUtils.extractWordBeforeCursor("Hello, world")

        assertNotNull(result)
        assertEquals("world", result!!.first)
        assertEquals(6, result.second)
    }

    @Test
    fun `extractWordBeforeCursor handles word after period`() {
        val result = BackspaceUtils.extractWordBeforeCursor("End. Start")

        assertNotNull(result)
        assertEquals("Start", result!!.first)
        assertEquals(4, result.second)
    }

    @Test
    fun `extractWordBeforeCursor returns null for empty string`() {
        val result = BackspaceUtils.extractWordBeforeCursor("")

        assertNull(result)
    }

    @Test
    fun `extractWordBeforeCursor returns null for only punctuation`() {
        val result = BackspaceUtils.extractWordBeforeCursor("...")

        assertNull(result)
    }

    @Test
    fun `extractWordBeforeCursor returns null for only whitespace`() {
        val result = BackspaceUtils.extractWordBeforeCursor("   ")

        assertNull(result)
    }

    @Test
    fun `extractWordBeforeCursor handles word with apostrophe`() {
        val result = BackspaceUtils.extractWordBeforeCursor("don't")

        assertNotNull(result)
        assertEquals("don't", result!!.first)
        assertEquals(-1, result.second)
    }

    @Test
    fun `extractWordBeforeCursor handles hyphenated word`() {
        val result = BackspaceUtils.extractWordBeforeCursor("well-known")

        assertNotNull(result)
        assertEquals("well-known", result!!.first)
        assertEquals(-1, result.second)
    }

    @Test
    fun `extractWordBeforeCursor handles newline boundary`() {
        val result = BackspaceUtils.extractWordBeforeCursor("first\nsecond")

        assertNotNull(result)
        assertEquals("second", result!!.first)
        assertEquals(5, result.second)
    }

    @Test
    fun `shouldDeleteTrailingSpace detects space before word`() {
        val shouldDelete = BackspaceUtils.shouldDeleteTrailingSpace("hello world", 5)

        assertTrue(shouldDelete)
    }

    @Test
    fun `shouldDeleteTrailingSpace returns false when no space`() {
        val shouldDelete = BackspaceUtils.shouldDeleteTrailingSpace("helloworld", 5)

        assertFalse(shouldDelete)
    }

    @Test
    fun `shouldDeleteTrailingSpace returns false when word at start`() {
        val shouldDelete = BackspaceUtils.shouldDeleteTrailingSpace("hello", 5)

        assertFalse(shouldDelete)
    }

    @Test
    fun `shouldDeleteTrailingSpace detects tab before word`() {
        val shouldDelete = BackspaceUtils.shouldDeleteTrailingSpace("hello\tworld", 5)

        assertTrue(shouldDelete)
    }

    @Test
    fun `calculateDeleteLength includes space when requested`() {
        val length = BackspaceUtils.calculateDeleteLength(5, true)

        assertEquals(6, length)
    }

    @Test
    fun `calculateDeleteLength excludes space when not requested`() {
        val length = BackspaceUtils.calculateDeleteLength(5, false)

        assertEquals(5, length)
    }

    @Test
    fun `full backspace word flow with space`() {
        val text = "hello world test"
        val wordInfo = BackspaceUtils.extractWordBeforeCursor(text)

        assertNotNull(wordInfo)
        val (word, _) = wordInfo!!
        assertEquals("test", word)

        val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(text, word.length)
        assertTrue(shouldDeleteSpace)

        val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)
        assertEquals(5, deleteLength)
    }

    @Test
    fun `full backspace word flow without space`() {
        val text = "test"
        val wordInfo = BackspaceUtils.extractWordBeforeCursor(text)

        assertNotNull(wordInfo)
        val (word, _) = wordInfo!!
        assertEquals("test", word)

        val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(text, word.length)
        assertFalse(shouldDeleteSpace)

        val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)
        assertEquals(4, deleteLength)
    }

    @Test
    fun `extractWordBeforeCursor treats period as boundary`() {
        val result = BackspaceUtils.extractWordBeforeCursor("hello.world")

        assertNotNull(result)
        assertEquals("world", result!!.first)
        assertEquals(5, result.second)
    }

    @Test
    fun `extractWordBeforeCursor treats exclamation as boundary`() {
        val result = BackspaceUtils.extractWordBeforeCursor("hello!world")

        assertNotNull(result)
        assertEquals("world", result!!.first)
        assertEquals(5, result.second)
    }

    @Test
    fun `extractWordBeforeCursor treats question mark as boundary`() {
        val result = BackspaceUtils.extractWordBeforeCursor("hello?world")

        assertNotNull(result)
        assertEquals("world", result!!.first)
        assertEquals(5, result.second)
    }

    @Test
    fun `extractWordBeforeCursor handles Spanish inverted question mark`() {
        val result = BackspaceUtils.extractWordBeforeCursor("¿Hola")

        assertNotNull(result)
        assertEquals("Hola", result!!.first)
        assertEquals(0, result.second)
    }

    @Test
    fun `extractWordBeforeCursor preserves apostrophes in words`() {
        val result = BackspaceUtils.extractWordBeforeCursor("it's")

        assertNotNull(result)
        assertEquals("it's", result!!.first)
        assertEquals(-1, result.second)
    }

    @Test
    fun `extractWordBeforeCursor preserves hyphens in words`() {
        val result = BackspaceUtils.extractWordBeforeCursor("co-worker")

        assertNotNull(result)
        assertEquals("co-worker", result!!.first)
        assertEquals(-1, result.second)
    }

    @Test
    fun `getLastGraphemeClusterLength handles empty string`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("")
        assertEquals(0, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles single ASCII character`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("a")
        assertEquals(1, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles multiple ASCII characters`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("hello")
        assertEquals(1, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles simple emoji`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("😀")
        assertEquals(2, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles emoji with text`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("hello😀")
        assertEquals(2, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles emoji with skin tone modifier`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("👋🏽")
        assertEquals(4, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles combining diacritical marks`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("e\u0301")
        assertEquals(2, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles family emoji sequence`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("👨‍👩‍👧‍👦")
        assertEquals(11, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles flag emoji`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("🇺🇸")
        assertEquals(4, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles German umlaut`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("ü")
        assertEquals(1, length)
    }

    @Test
    fun `getLastGraphemeClusterLength handles Spanish character`() {
        val length = BackspaceUtils.getLastGraphemeClusterLength("ñ")
        assertEquals(1, length)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition handles empty string`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("", 0)
        assertEquals("", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition handles position zero`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello", 0)
        assertEquals("hello", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition handles position beyond text length`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello", 10)
        assertEquals("hello", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition handles negative position`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello", -1)
        assertEquals("hello", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes ASCII character`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello", 5)
        assertEquals("hell", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes character from middle`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello", 3)
        assertEquals("helo", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes first character`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello", 1)
        assertEquals("ello", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes simple emoji`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello😀", 7)
        assertEquals("hello", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes emoji with skin tone`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello👋🏽", 9)
        assertEquals("hello", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes combining character`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("cafe\u0301", 5)
        assertEquals("caf", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes flag emoji`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("USA🇺🇸", 7)
        assertEquals("USA", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition deletes from text with multiple emoji`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("😀😂", 4)
        assertEquals("😀", result)
    }

    @Test
    fun `deleteGraphemeClusterBeforePosition preserves text after position`() {
        val result = BackspaceUtils.deleteGraphemeClusterBeforePosition("hello world", 5)
        assertEquals("hell world", result)
    }
}
