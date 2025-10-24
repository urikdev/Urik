@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.utils

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyboardModeUtilsTest {
    @Test
    fun `isNumberInputType detects TYPE_CLASS_NUMBER`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }

        assertTrue(KeyboardModeUtils.isNumberInputType(editorInfo))
    }

    @Test
    fun `isNumberInputType detects decimal number input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

        assertTrue(KeyboardModeUtils.isNumberInputType(editorInfo))
    }

    @Test
    fun `isNumberInputType detects signed number input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }

        assertTrue(KeyboardModeUtils.isNumberInputType(editorInfo))
    }

    @Test
    fun `isNumberInputType returns false for phone input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_PHONE
            }

        assertFalse(KeyboardModeUtils.isNumberInputType(editorInfo))
    }

    @Test
    fun `isNumberInputType returns false for text input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            }

        assertFalse(KeyboardModeUtils.isNumberInputType(editorInfo))
    }

    @Test
    fun `isNumberInputType returns false for datetime input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_DATETIME
            }

        assertFalse(KeyboardModeUtils.isNumberInputType(editorInfo))
    }

    @Test
    fun `isNumberInputType returns false for null EditorInfo`() {
        assertFalse(KeyboardModeUtils.isNumberInputType(null))
    }

    @Test
    fun `determineTargetMode switches to NUMBERS for number input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }

        val targetMode = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.LETTERS)

        assertEquals(KeyboardMode.NUMBERS, targetMode)
    }

    @Test
    fun `determineTargetMode maintains LETTERS for text input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            }

        val targetMode = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.LETTERS)

        assertEquals(KeyboardMode.LETTERS, targetMode)
    }

    @Test
    fun `determineTargetMode switches from NUMBERS to LETTERS for text input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            }

        val targetMode = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.NUMBERS)

        assertEquals(KeyboardMode.LETTERS, targetMode)
    }

    @Test
    fun `determineTargetMode maintains SYMBOLS for text input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            }

        val targetMode = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.SYMBOLS)

        assertEquals(KeyboardMode.SYMBOLS, targetMode)
    }

    @Test
    fun `determineTargetMode switches from SYMBOLS to NUMBERS for number input`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }

        val targetMode = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.SYMBOLS)

        assertEquals(KeyboardMode.NUMBERS, targetMode)
    }

    @Test
    fun `determineTargetMode maintains current mode for null EditorInfo`() {
        val targetMode = KeyboardModeUtils.determineTargetMode(null, KeyboardMode.SYMBOLS)

        assertEquals(KeyboardMode.SYMBOLS, targetMode)
    }

    @Test
    fun `determineTargetMode handles email input correctly`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }

        val fromLetters = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.LETTERS)
        val fromSymbols = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.SYMBOLS)

        assertEquals(KeyboardMode.LETTERS, fromLetters)
        assertEquals(KeyboardMode.SYMBOLS, fromSymbols)
    }

    @Test
    fun `determineTargetMode handles password input correctly`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

        val fromLetters = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.LETTERS)
        val fromSymbols = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.SYMBOLS)

        assertEquals(KeyboardMode.LETTERS, fromLetters)
        assertEquals(KeyboardMode.SYMBOLS, fromSymbols)
    }

    @Test
    fun `determineTargetMode forces NUMBERS for number input from any mode`() {
        val editorInfo =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }

        val fromLetters = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.LETTERS)
        val fromSymbols = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.SYMBOLS)
        val fromNumbers = KeyboardModeUtils.determineTargetMode(editorInfo, KeyboardMode.NUMBERS)

        assertEquals(KeyboardMode.NUMBERS, fromLetters)
        assertEquals(KeyboardMode.NUMBERS, fromSymbols)
        assertEquals(KeyboardMode.NUMBERS, fromNumbers)
    }

    @Test
    fun `determineTargetMode mode sequence LETTERS to NUMBERS to LETTERS`() {
        val numberInput =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        val textInput =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            }

        var currentMode = KeyboardMode.LETTERS

        currentMode = KeyboardModeUtils.determineTargetMode(numberInput, currentMode)
        assertEquals(KeyboardMode.NUMBERS, currentMode)

        currentMode = KeyboardModeUtils.determineTargetMode(textInput, currentMode)
        assertEquals(KeyboardMode.LETTERS, currentMode)
    }

    @Test
    fun `determineTargetMode SYMBOLS persists across non-number text inputs`() {
        val textInput =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            }
        val emailInput =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }

        var currentMode = KeyboardMode.SYMBOLS

        currentMode = KeyboardModeUtils.determineTargetMode(textInput, currentMode)
        assertEquals(KeyboardMode.SYMBOLS, currentMode)

        currentMode = KeyboardModeUtils.determineTargetMode(emailInput, currentMode)
        assertEquals(KeyboardMode.SYMBOLS, currentMode)
    }
}
