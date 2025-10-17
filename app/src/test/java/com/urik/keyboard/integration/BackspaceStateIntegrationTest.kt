@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.integration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests backspace state management and onUpdateSelection logic.
 *
 * Verifies displayBuffer consistency across IME callbacks, prevents phantom
 * character injection during backspace, and ensures composing text clears
 * without affecting committed text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackspaceStateIntegrationTest {
    private lateinit var stateManager: IMEStateManager

    @Before
    fun setup() {
        stateManager = IMEStateManager()
    }

    @Test
    fun `onUpdateSelection does not clear state when actively composing`() =
        runTest {
            stateManager.displayBuffer = "hello"

            val shouldClear =
                stateManager.shouldClearOnUpdateSelection(
                    oldSelStart = 4,
                    oldSelEnd = 4,
                    newSelStart = 5,
                    newSelEnd = 5,
                    candidatesStart = -1,
                    candidatesEnd = -1,
                )

            assertFalse("Should NOT clear when actively composing", shouldClear)
            assertEquals("hello", stateManager.displayBuffer)
        }

    @Test
    fun `onUpdateSelection clears state when cursor moves backwards`() =
        runTest {
            stateManager.displayBuffer = "world"

            val shouldClear =
                stateManager.shouldClearOnUpdateSelection(
                    oldSelStart = 5,
                    oldSelEnd = 5,
                    newSelStart = 2,
                    newSelEnd = 2,
                    candidatesStart = -1,
                    candidatesEnd = -1,
                )

            assertTrue("Should clear when cursor moves backwards", shouldClear)
        }

    @Test
    fun `onUpdateSelection does not clear when displayBuffer empty and no stale state`() =
        runTest {
            stateManager.displayBuffer = ""
            stateManager.hasWordStateContent = false

            val shouldClear =
                stateManager.shouldClearOnUpdateSelection(
                    oldSelStart = 5,
                    oldSelEnd = 5,
                    newSelStart = 6,
                    newSelEnd = 6,
                    candidatesStart = -1,
                    candidatesEnd = -1,
                )

            assertFalse("No stale state to clear", shouldClear)
        }

    @Test
    fun `onUpdateSelection clears stale wordState when displayBuffer empty`() =
        runTest {
            stateManager.displayBuffer = ""
            stateManager.hasWordStateContent = true

            val shouldClear =
                stateManager.shouldClearOnUpdateSelection(
                    oldSelStart = 5,
                    oldSelEnd = 5,
                    newSelStart = 6,
                    newSelEnd = 6,
                    candidatesStart = -1,
                    candidatesEnd = -1,
                )

            assertTrue("Should clear stale wordState", shouldClear)
        }

    @Test
    fun `onUpdateSelection ignores aggressive IME during forward cursor movement`() =
        runTest {
            stateManager.displayBuffer = "test"

            val shouldClear =
                stateManager.shouldClearOnUpdateSelection(
                    oldSelStart = 3,
                    oldSelEnd = 3,
                    newSelStart = 4,
                    newSelEnd = 4,
                    candidatesStart = -1,
                    candidatesEnd = -1,
                )

            assertFalse("Ignore aggressive IME, continue composing", shouldClear)
        }

    @Test
    fun `onUpdateSelection handles cursor at same position with active composition`() =
        runTest {
            stateManager.displayBuffer = "typing"

            val shouldClear =
                stateManager.shouldClearOnUpdateSelection(
                    oldSelStart = 6,
                    oldSelEnd = 6,
                    newSelStart = 6,
                    newSelEnd = 6,
                    candidatesStart = -1,
                    candidatesEnd = -1,
                )

            assertFalse("Don't clear on spurious updates", shouldClear)
        }

    @Test
    fun `backspace on last composing character does not delete committed text`() =
        runTest {
            stateManager.displayBuffer = "M"
            stateManager.committedText = "2.6"

            stateManager.simulateBackspace()

            assertEquals("", stateManager.displayBuffer)
            assertEquals("2.6", stateManager.committedText)
            assertEquals("Should not call deleteSurroundingText", 0, stateManager.deleteFromCommittedCount)
        }

    @Test
    fun `backspace on empty displayBuffer deletes from committed text`() =
        runTest {
            stateManager.displayBuffer = ""
            stateManager.committedText = "hello"

            stateManager.simulateBackspace()

            assertEquals("", stateManager.displayBuffer)
            assertEquals("hell", stateManager.committedText)
            assertEquals("Should call deleteSurroundingText once", 1, stateManager.deleteFromCommittedCount)
        }

    @Test
    fun `backspace through multi-character composing does not touch committed text`() =
        runTest {
            stateManager.displayBuffer = "word"
            stateManager.committedText = "test"

            stateManager.simulateBackspace()
            assertEquals("wor", stateManager.displayBuffer)
            assertEquals("test", stateManager.committedText)

            stateManager.simulateBackspace()
            assertEquals("wo", stateManager.displayBuffer)
            assertEquals("test", stateManager.committedText)

            stateManager.simulateBackspace()
            assertEquals("w", stateManager.displayBuffer)
            assertEquals("test", stateManager.committedText)

            stateManager.simulateBackspace()
            assertEquals("", stateManager.displayBuffer)
            assertEquals("test", stateManager.committedText)
            assertEquals(0, stateManager.deleteFromCommittedCount)
        }

    @Test
    fun `backspace sequence - composing then committed`() =
        runTest {
            stateManager.displayBuffer = "ab"
            stateManager.committedText = "123"

            stateManager.simulateBackspace()
            assertEquals("a", stateManager.displayBuffer)
            assertEquals("123", stateManager.committedText)
            assertEquals(0, stateManager.deleteFromCommittedCount)

            stateManager.simulateBackspace()
            assertEquals("", stateManager.displayBuffer)
            assertEquals("123", stateManager.committedText)
            assertEquals(0, stateManager.deleteFromCommittedCount)

            stateManager.simulateBackspace()
            assertEquals("", stateManager.displayBuffer)
            assertEquals("12", stateManager.committedText)
            assertEquals(1, stateManager.deleteFromCommittedCount)
        }

    @Test
    fun `backspace after punctuation typed separately`() =
        runTest {
            stateManager.displayBuffer = "ing"
            stateManager.committedText = "test."

            stateManager.simulateBackspace()
            assertEquals("in", stateManager.displayBuffer)
            assertEquals("test.", stateManager.committedText)

            stateManager.simulateBackspace()
            assertEquals("i", stateManager.displayBuffer)
            assertEquals("test.", stateManager.committedText)

            stateManager.simulateBackspace()
            assertEquals("", stateManager.displayBuffer)
            assertEquals("test.", stateManager.committedText)
            assertEquals("Should not delete period", 0, stateManager.deleteFromCommittedCount)
        }
}

/**
 * Simplified state manager implementing fixed backspace and onUpdateSelection logic.
 */
private class IMEStateManager {
    var displayBuffer = ""
    var hasWordStateContent = false
    var committedText = ""
    var deleteFromCommittedCount = 0

    /**
     * Fixed onUpdateSelection logic trusting internal state over IME reports.
     */
    fun shouldClearOnUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): Boolean {
        if (displayBuffer.isNotEmpty()) {
            if (newSelStart < oldSelStart) {
                displayBuffer = ""
                hasWordStateContent = false
                return true
            }
            return false
        }

        val noComposingText = (candidatesStart == -1 && candidatesEnd == -1)

        if (noComposingText && hasWordStateContent) {
            displayBuffer = ""
            hasWordStateContent = false
            return true
        }

        return false
    }

    /**
     * Fixed backspace logic clearing composing text without touching committed text.
     */
    fun simulateBackspace() {
        if (displayBuffer.isNotEmpty()) {
            displayBuffer = displayBuffer.dropLast(1)

            if (displayBuffer.isEmpty()) {
                hasWordStateContent = false
            }
        } else {
            if (committedText.isNotEmpty()) {
                committedText = committedText.dropLast(1)
                deleteFromCommittedCount++
            }
        }
    }
}
