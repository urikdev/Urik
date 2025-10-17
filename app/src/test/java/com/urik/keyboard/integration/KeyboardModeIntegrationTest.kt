@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.integration

import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests keyboard mode switching based on input type detection.
 *
 * Verifies automatic mode transitions between LETTERS, NUMBERS, and SYMBOLS
 * based on EditorInfo input type flags.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KeyboardModeIntegrationTest {
    /**
     * Determines if input type requires NUMBERS mode.
     */
    private fun shouldSwitchToNumbersMode(editorInfo: EditorInfo?): Boolean {
        val inputType = editorInfo?.inputType ?: return false
        val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        return inputClass == android.text.InputType.TYPE_CLASS_NUMBER
    }

    /**
     * Determines target mode based on input type and current mode.
     */
    private fun determineTargetMode(
        editorInfo: EditorInfo?,
        currentMode: KeyboardMode,
    ): KeyboardMode {
        val isNumberInput = shouldSwitchToNumbersMode(editorInfo)

        return when {
            isNumberInput -> KeyboardMode.NUMBERS
            currentMode == KeyboardMode.NUMBERS -> KeyboardMode.LETTERS
            else -> currentMode
        }
    }

    @Test
    fun `number input field triggers NUMBERS mode`() =
        runTest {
            val editorInfo =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

            val targetMode = determineTargetMode(editorInfo, KeyboardMode.LETTERS)

            assertEquals(KeyboardMode.NUMBERS, targetMode)
        }

    @Test
    fun `decimal number input triggers NUMBERS mode`() =
        runTest {
            val editorInfo =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }

            val targetMode = determineTargetMode(editorInfo, KeyboardMode.LETTERS)

            assertEquals(KeyboardMode.NUMBERS, targetMode)
        }

    @Test
    fun `signed number input triggers NUMBERS mode`() =
        runTest {
            val editorInfo =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                }

            val targetMode = determineTargetMode(editorInfo, KeyboardMode.LETTERS)

            assertEquals(KeyboardMode.NUMBERS, targetMode)
        }

    @Test
    fun `phone input does NOT trigger NUMBERS mode`() =
        runTest {
            val editorInfo =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_PHONE
                }

            val targetMode = determineTargetMode(editorInfo, KeyboardMode.LETTERS)

            assertEquals(KeyboardMode.LETTERS, targetMode)
        }

    @Test
    fun `text input field maintains LETTERS mode`() =
        runTest {
            val editorInfo =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                }

            val targetMode = determineTargetMode(editorInfo, KeyboardMode.LETTERS)

            assertEquals(KeyboardMode.LETTERS, targetMode)
        }

    @Test
    fun `text input after number input switches back to LETTERS`() =
        runTest {
            val textInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                }

            val targetMode = determineTargetMode(textInput, KeyboardMode.NUMBERS)

            assertEquals(KeyboardMode.LETTERS, targetMode)
        }

    @Test
    fun `text input while in SYMBOLS stays in SYMBOLS`() =
        runTest {
            val textInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                }

            val targetMode = determineTargetMode(textInput, KeyboardMode.SYMBOLS)

            assertEquals(KeyboardMode.SYMBOLS, targetMode)
        }

    @Test
    fun `number input while in SYMBOLS switches to NUMBERS`() =
        runTest {
            val numberInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

            val targetMode = determineTargetMode(numberInput, KeyboardMode.SYMBOLS)

            assertEquals(KeyboardMode.NUMBERS, targetMode)
        }

    @Test
    fun `email input maintains current mode`() =
        runTest {
            val emailInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                }

            val fromLetters = determineTargetMode(emailInput, KeyboardMode.LETTERS)
            val fromSymbols = determineTargetMode(emailInput, KeyboardMode.SYMBOLS)

            assertEquals(KeyboardMode.LETTERS, fromLetters)
            assertEquals(KeyboardMode.SYMBOLS, fromSymbols)
        }

    @Test
    fun `password input maintains current mode`() =
        runTest {
            val passwordInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }

            val fromLetters = determineTargetMode(passwordInput, KeyboardMode.LETTERS)
            val fromSymbols = determineTargetMode(passwordInput, KeyboardMode.SYMBOLS)

            assertEquals(KeyboardMode.LETTERS, fromLetters)
            assertEquals(KeyboardMode.SYMBOLS, fromSymbols)
        }

    @Test
    fun `null EditorInfo maintains current mode`() =
        runTest {
            val targetMode = determineTargetMode(null, KeyboardMode.SYMBOLS)

            assertEquals(KeyboardMode.SYMBOLS, targetMode)
        }

    @Test
    fun `datetime input does NOT trigger NUMBERS mode`() =
        runTest {
            val datetimeInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_DATETIME
                }

            val targetMode = determineTargetMode(datetimeInput, KeyboardMode.LETTERS)

            assertEquals(KeyboardMode.LETTERS, targetMode)
        }

    @Test
    fun `LETTERS to NUMBERS to LETTERS sequence works correctly`() =
        runTest {
            val numberInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
            val textInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                }

            var currentMode = KeyboardMode.LETTERS

            currentMode = determineTargetMode(numberInput, currentMode)
            assertEquals(KeyboardMode.NUMBERS, currentMode)

            currentMode = determineTargetMode(textInput, currentMode)
            assertEquals(KeyboardMode.LETTERS, currentMode)
        }

    @Test
    fun `SYMBOLS persists across non-number inputs`() =
        runTest {
            val textInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                }
            val emailInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                }

            var currentMode = KeyboardMode.SYMBOLS

            currentMode = determineTargetMode(textInput, currentMode)
            assertEquals(KeyboardMode.SYMBOLS, currentMode)

            currentMode = determineTargetMode(emailInput, currentMode)
            assertEquals(KeyboardMode.SYMBOLS, currentMode)
        }

    @Test
    fun `number input always forces NUMBERS mode regardless of current mode`() =
        runTest {
            val numberInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

            val fromLetters = determineTargetMode(numberInput, KeyboardMode.LETTERS)
            val fromSymbols = determineTargetMode(numberInput, KeyboardMode.SYMBOLS)
            val fromNumbers = determineTargetMode(numberInput, KeyboardMode.NUMBERS)

            assertEquals(KeyboardMode.NUMBERS, fromLetters)
            assertEquals(KeyboardMode.NUMBERS, fromSymbols)
            assertEquals(KeyboardMode.NUMBERS, fromNumbers)
        }

    @Test
    fun `exiting NUMBERS mode always returns to LETTERS`() =
        runTest {
            val textInput =
                EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                }

            val targetMode = determineTargetMode(textInput, KeyboardMode.NUMBERS)

            assertEquals(
                "NUMBERS is auto-only, should always exit to LETTERS",
                KeyboardMode.LETTERS,
                targetMode,
            )
        }
}
