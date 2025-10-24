package com.urik.keyboard.utils

import org.junit.Assert
import org.junit.Test

class CursorEditingUtilsTest {
    @Test
    fun `extractWordAtCursor limits to 20 chars before cursor`() {
        val textBefore = "a".repeat(50)
        val textAfter = "b".repeat(5)

        val word = CursorEditingUtils.extractWordAtCursor(textBefore, textAfter)

        Assert.assertNotNull(word)
        Assert.assertTrue(word!!.length <= 25)
    }

    @Test
    fun `extractWordAtCursor limits to 20 chars after cursor`() {
        val textBefore = "a".repeat(5)
        val textAfter = "b".repeat(50)

        val word = CursorEditingUtils.extractWordAtCursor(textBefore, textAfter)

        Assert.assertNotNull(word)
        Assert.assertTrue(word!!.length <= 25)
    }

    @Test
    fun `extractWordAtCursor rejects words over 40 chars`() {
        val textBefore = "a".repeat(25)
        val textAfter = "b".repeat(25)

        val word = CursorEditingUtils.extractWordAtCursor(textBefore, textAfter)

        Assert.assertNull(word)
    }

    @Test
    fun `extractWordAtCursor extracts normal word correctly`() {
        val textBefore = "The quick brown fox jumps over the hello"
        val textAfter = " world"

        val word = CursorEditingUtils.extractWordAtCursor(textBefore, textAfter)

        Assert.assertEquals("hello", word)
    }

    @Test
    fun `extractWordAtCursor handles cursor at word boundary`() {
        val textBefore = "test "
        val textAfter = "word"

        val word = CursorEditingUtils.extractWordAtCursor(textBefore, textAfter)

        Assert.assertEquals("word", word)
    }

    @Test
    fun `extractWordAtCursor handles punctuation boundaries`() {
        val textBefore = "Hello, this is "
        val textAfter = "great!"

        val word = CursorEditingUtils.extractWordAtCursor(textBefore, textAfter)

        Assert.assertEquals("great", word)
    }

    @Test
    fun `extractWordAtCursor returns null for empty input`() {
        val word = CursorEditingUtils.extractWordAtCursor("", "")

        Assert.assertNull(word)
    }

    @Test
    fun `shouldClearStateOnEmptyField detects stale state in empty field`() {
        val shouldClear =
            CursorEditingUtils.shouldClearStateOnEmptyField(
                newSelStart = 0,
                newSelEnd = 0,
                textBefore = "",
                textAfter = "",
                displayBuffer = "hello",
                hasWordStateContent = true,
            )

        Assert.assertTrue(shouldClear)
    }

    @Test
    fun `shouldClearStateOnEmptyField does not clear when field has content`() {
        val shouldClear =
            CursorEditingUtils.shouldClearStateOnEmptyField(
                newSelStart = 0,
                newSelEnd = 0,
                textBefore = "",
                textAfter = "world",
                displayBuffer = "hello",
                hasWordStateContent = true,
            )

        Assert.assertFalse(shouldClear)
    }

    @Test
    fun `shouldClearStateOnEmptyField does not clear when no stale state`() {
        val shouldClear =
            CursorEditingUtils.shouldClearStateOnEmptyField(
                newSelStart = 0,
                newSelEnd = 0,
                textBefore = "",
                textAfter = "",
                displayBuffer = "",
                hasWordStateContent = false,
            )

        Assert.assertFalse(shouldClear)
    }

    @Test
    fun `shouldClearStateOnEmptyField does not clear when cursor not at start`() {
        val shouldClear =
            CursorEditingUtils.shouldClearStateOnEmptyField(
                newSelStart = 5,
                newSelEnd = 5,
                textBefore = "",
                textAfter = "",
                displayBuffer = "hello",
                hasWordStateContent = true,
            )

        Assert.assertFalse(shouldClear)
    }

    @Test
    fun `calculateCursorPositionInWord with valid composing region`() {
        val cursorPos =
            CursorEditingUtils.calculateCursorPositionInWord(
                absoluteCursorPos = 15,
                composingRegionStart = 10,
                displayBufferLength = 5,
            )

        Assert.assertEquals(5, cursorPos)
    }

    @Test
    fun `calculateCursorPositionInWord with no composing region`() {
        val cursorPos =
            CursorEditingUtils.calculateCursorPositionInWord(
                absoluteCursorPos = 15,
                composingRegionStart = -1,
                displayBufferLength = 5,
            )

        Assert.assertEquals(5, cursorPos)
    }

    @Test
    fun `calculateCursorPositionInWord coerces to buffer bounds`() {
        val cursorPos =
            CursorEditingUtils.calculateCursorPositionInWord(
                absoluteCursorPos = 100,
                composingRegionStart = 10,
                displayBufferLength = 5,
            )

        Assert.assertEquals(5, cursorPos)
    }

    @Test
    fun `recalculateComposingRegionStart calculates correctly`() {
        val start =
            CursorEditingUtils.recalculateComposingRegionStart(
                currentTextLength = 15,
                displayBufferLength = 5,
            )

        Assert.assertEquals(10, start)
    }

    @Test
    fun `isValidTextInput accepts letters`() {
        Assert.assertTrue(CursorEditingUtils.isValidTextInput("hello"))
    }

    @Test
    fun `isValidTextInput accepts apostrophes`() {
        Assert.assertTrue(CursorEditingUtils.isValidTextInput("don't"))
    }

    @Test
    fun `isValidTextInput accepts hyphens`() {
        Assert.assertTrue(CursorEditingUtils.isValidTextInput("well-known"))
    }

    @Test
    fun `isValidTextInput rejects blank`() {
        Assert.assertFalse(CursorEditingUtils.isValidTextInput(""))
        Assert.assertFalse(CursorEditingUtils.isValidTextInput("   "))
    }

    @Test
    fun `isValidTextInput rejects pure punctuation`() {
        Assert.assertFalse(CursorEditingUtils.isValidTextInput("..."))
    }
}
