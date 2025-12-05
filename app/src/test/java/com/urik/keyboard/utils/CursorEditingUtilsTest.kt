package com.urik.keyboard.utils

import org.junit.Assert
import org.junit.Test

class CursorEditingUtilsTest {
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

    @Test
    fun `isPunctuation recognizes common English punctuation`() {
        Assert.assertTrue(CursorEditingUtils.isPunctuation('.'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation(','))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('!'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('?'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation(';'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation(':'))
    }

    @Test
    fun `isPunctuation recognizes quotes and brackets`() {
        Assert.assertTrue(CursorEditingUtils.isPunctuation('"'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('('))
        Assert.assertTrue(CursorEditingUtils.isPunctuation(')'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('['))
        Assert.assertTrue(CursorEditingUtils.isPunctuation(']'))
    }

    @Test
    fun `isPunctuation recognizes international punctuation`() {
        Assert.assertTrue(CursorEditingUtils.isPunctuation('。'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('？'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('！'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('،'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('؛'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('¿'))
        Assert.assertTrue(CursorEditingUtils.isPunctuation('¡'))
    }

    @Test
    fun `isPunctuation rejects apostrophes and hyphens`() {
        Assert.assertFalse(CursorEditingUtils.isPunctuation('\''))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('\u2019'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('-'))
    }

    @Test
    fun `isPunctuation rejects letters and digits`() {
        Assert.assertFalse(CursorEditingUtils.isPunctuation('a'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('Z'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('5'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('ñ'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('č'))
    }

    @Test
    fun `isPunctuation recognizes at symbol`() {
        Assert.assertTrue(CursorEditingUtils.isPunctuation('@'))
    }

    @Test
    fun `isPunctuation rejects symbols with special semantic meaning`() {
        Assert.assertFalse(CursorEditingUtils.isPunctuation('#'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('$'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('%'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('&'))
        Assert.assertFalse(CursorEditingUtils.isPunctuation('*'))
    }
}
