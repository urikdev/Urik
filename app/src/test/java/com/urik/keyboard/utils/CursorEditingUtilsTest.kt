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

    @Test
    fun `isNonSequentialCursorMovement detects large jump`() {
        Assert.assertTrue(
            CursorEditingUtils.isNonSequentialCursorMovement(
                oldSelStart = 10,
                oldSelEnd = 10,
                newSelStart = 100,
                newSelEnd = 100,
                composingRegionStart = -1,
                composingRegionEnd = -1,
            ),
        )
    }

    @Test
    fun `isNonSequentialCursorMovement allows sequential movement`() {
        Assert.assertFalse(
            CursorEditingUtils.isNonSequentialCursorMovement(
                oldSelStart = 10,
                oldSelEnd = 10,
                newSelStart = 11,
                newSelEnd = 11,
                composingRegionStart = -1,
                composingRegionEnd = -1,
            ),
        )
    }

    @Test
    fun `isNonSequentialCursorMovement allows movement within composing region`() {
        Assert.assertFalse(
            CursorEditingUtils.isNonSequentialCursorMovement(
                oldSelStart = 12,
                oldSelEnd = 12,
                newSelStart = 18,
                newSelEnd = 18,
                composingRegionStart = 10,
                composingRegionEnd = 20,
            ),
        )
    }

    @Test
    fun `isNonSequentialCursorMovement detects selection change`() {
        Assert.assertTrue(
            CursorEditingUtils.isNonSequentialCursorMovement(
                oldSelStart = 10,
                oldSelEnd = 10,
                newSelStart = 10,
                newSelEnd = 20,
                composingRegionStart = -1,
                composingRegionEnd = -1,
            ),
        )
    }

    @Test
    fun `extractWordBoundedByParagraph stops at newline`() {
        val result = CursorEditingUtils.extractWordBoundedByParagraph("First line\nSecond")

        Assert.assertNotNull(result)
        Assert.assertEquals("Second", result!!.first)
    }

    @Test
    fun `extractWordBoundedByParagraph extracts word within paragraph`() {
        val result = CursorEditingUtils.extractWordBoundedByParagraph("Hello world")

        Assert.assertNotNull(result)
        Assert.assertEquals("world", result!!.first)
    }

    @Test
    fun `extractWordBoundedByParagraph returns null for empty paragraph`() {
        val result = CursorEditingUtils.extractWordBoundedByParagraph("First line\n")

        Assert.assertNull(result)
    }

    @Test
    fun `extractWordBoundedByParagraph returns null for whitespace only`() {
        val result = CursorEditingUtils.extractWordBoundedByParagraph("First line\n   ")

        Assert.assertNull(result)
    }

    @Test
    fun `shouldAbortRecomposition detects cursor mismatch`() {
        Assert.assertTrue(
            CursorEditingUtils.shouldAbortRecomposition(
                expectedCursorPosition = 10,
                actualCursorPosition = 50,
                expectedComposingStart = 5,
                actualComposingStart = 5,
            ),
        )
    }

    @Test
    fun `shouldAbortRecomposition allows minor drift within tolerance`() {
        Assert.assertFalse(
            CursorEditingUtils.shouldAbortRecomposition(
                expectedCursorPosition = 10,
                actualCursorPosition = 11,
                expectedComposingStart = 5,
                actualComposingStart = 5,
            ),
        )
    }

    @Test
    fun `shouldAbortRecomposition detects composing region mismatch`() {
        Assert.assertTrue(
            CursorEditingUtils.shouldAbortRecomposition(
                expectedCursorPosition = 10,
                actualCursorPosition = 10,
                expectedComposingStart = 5,
                actualComposingStart = 20,
            ),
        )
    }

    @Test
    fun `crossesParagraphBoundary detects newline in range`() {
        val text = "Hello\nWorld"

        Assert.assertTrue(
            CursorEditingUtils.crossesParagraphBoundary(0, 8, text),
        )
    }

    @Test
    fun `crossesParagraphBoundary returns false for same paragraph`() {
        val text = "Hello World"

        Assert.assertFalse(
            CursorEditingUtils.crossesParagraphBoundary(0, 5, text),
        )
    }

    @Test
    fun `crossesParagraphBoundary handles invalid positions`() {
        Assert.assertFalse(
            CursorEditingUtils.crossesParagraphBoundary(-1, 5, "Hello"),
        )
        Assert.assertFalse(
            CursorEditingUtils.crossesParagraphBoundary(0, 100, "Hello"),
        )
    }
}
