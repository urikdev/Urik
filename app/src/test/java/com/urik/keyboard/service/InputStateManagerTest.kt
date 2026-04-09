package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputStateManagerTest {
    private var suggestionsCleared = false
    private var lastSuggestions: List<String> = emptyList()

    private lateinit var stateManager: InputStateManager

    @Before
    fun setup() {
        suggestionsCleared = false
        lastSuggestions = emptyList()

        val viewCallback = object : ViewCallback {
            override fun clearSuggestions() {
                suggestionsCleared = true
            }

            override fun updateSuggestions(suggestions: List<String>) {
                lastSuggestions = suggestions
            }
        }

        stateManager = InputStateManager(
            viewCallback = viewCallback,
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )
    }

    @Test
    fun `lastAutocorrection persists independently of postCommitReplacementState`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")
        stateManager.postCommitReplacementState = PostCommitReplacementState("teh", "the")

        stateManager.postCommitReplacementState = null

        assertNotNull(stateManager.lastAutocorrection)
        assertEquals("teh", stateManager.lastAutocorrection?.originalTypedWord)
        assertEquals("the", stateManager.lastAutocorrection?.correctedWord)
    }

    @Test
    fun `clearInternalStateOnly clears lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")

        stateManager.clearInternalStateOnly()

        assertNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `invalidateComposingState clears lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")

        stateManager.invalidateComposingState()

        assertNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `clearInternalStateOnly clears postCommitReplacementState`() {
        stateManager.postCommitReplacementState = PostCommitReplacementState("teh", "the")

        stateManager.clearInternalStateOnly()

        assertNull(stateManager.postCommitReplacementState)
    }

    @Test
    fun `clearBigramPredictions does not affect lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")
        stateManager.isShowingBigramPredictions = true

        stateManager.clearBigramPredictions()

        assertNotNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `clearSpellConfirmationFields does not affect lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")
        stateManager.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION

        stateManager.clearSpellConfirmationFields()

        assertNotNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `tryConsumeTypingOus returns false on empty queue`() {
        val consumed = stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5)

        assertFalse(consumed)
    }

    @Test
    fun `tryConsumeTypingOus consumes matching entry`() {
        stateManager.enqueueTypingOus(
            InputStateManager.ExpectedTypingOus(
                composingStart = 0,
                composingEnd = 5,
                cursorPosition = 5
            )
        )

        val consumed = stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5)

        assertTrue(consumed)
        assertFalse(stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5))
    }

    @Test
    fun `tryConsumeTypingOus clears queue on mismatch`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 5, 5))
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 6, 6))

        val consumed = stateManager.tryConsumeTypingOus(cursor = 99, candStart = 0, candEnd = 5)

        assertFalse(consumed)
        assertFalse(stateManager.tryConsumeTypingOus(cursor = 6, candStart = 0, candEnd = 6))
    }

    @Test
    fun `tryConsumeTypingOus processes multiple in-flight OUS in order`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 1, 1))
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 2, 2))
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 3, 3))

        assertTrue(stateManager.tryConsumeTypingOus(cursor = 1, candStart = 0, candEnd = 1))
        assertTrue(stateManager.tryConsumeTypingOus(cursor = 2, candStart = 0, candEnd = 2))
        assertTrue(stateManager.tryConsumeTypingOus(cursor = 3, candStart = 0, candEnd = 3))
        assertFalse(stateManager.tryConsumeTypingOus(cursor = 3, candStart = 0, candEnd = 3))
    }

    @Test
    fun `clearInternalStateOnly clears pending typing OUS`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 5, 5))

        stateManager.clearInternalStateOnly()

        assertFalse(stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5))
    }

    @Test
    fun `invalidateComposingState clears pending typing OUS`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 5, 5))

        stateManager.invalidateComposingState()

        assertFalse(stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5))
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns true when all conditions met`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 15

        assertTrue(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when composingRegionStart is -1`() {
        stateManager.composingRegionStart = -1
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 5

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when displayBuffer is empty`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = ""
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 10

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when isActivelyEditing`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = true
        stateManager.lastKnownCursorPosition = 15

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when cursor drifted`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 12

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }
}
