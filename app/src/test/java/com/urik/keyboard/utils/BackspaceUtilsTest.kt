@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.utils

import org.junit.Assert.*
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
        val (word, boundary) = wordInfo!!
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
        val (word, boundary) = wordInfo!!
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
}
