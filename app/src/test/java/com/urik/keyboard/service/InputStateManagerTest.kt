package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
