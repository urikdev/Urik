package com.urik.keyboard.service

import com.urik.keyboard.utils.SelectionStateTracker

enum class SpellConfirmationState {
    NORMAL,
    AWAITING_CONFIRMATION
}

data class PostCommitReplacementState(val originalWord: String, val committedWord: String)

data class LastAutocorrection(val originalTypedWord: String, val correctedWord: String)

interface ViewCallback {
    fun clearSuggestions()

    fun updateSuggestions(suggestions: List<String>)
}

class InputStateManager(
    private val viewCallback: ViewCallback,
    private val onShiftStateChanged: (Boolean) -> Unit,
    private val isCapsLockOn: () -> Boolean,
    private val cancelDebounceJob: () -> Unit
) {
    @Volatile
    var displayBuffer = ""
        internal set

    @Volatile
    var wordState = WordState()
        internal set

    @Volatile
    var composingRegionStart: Int = -1
        internal set

    private var processingSequence = 0L
    private val processingLock = Any()

    @Volatile
    var isActivelyEditing = false
        internal set

    @Volatile
    var isCurrentWordAtSentenceStart = false
        internal set

    @Volatile
    var isCurrentWordManualShifted = false
        internal set

    @Volatile
    var pendingSuggestions: List<String> = emptyList()
        internal set

    @Volatile
    var currentRawSuggestions: List<SpellingSuggestion> = emptyList()
        internal set

    @Volatile
    var spellConfirmationState = SpellConfirmationState.NORMAL
        internal set

    @Volatile
    var pendingWordForLearning: String? = null
        internal set

    @Volatile
    var isShowingBigramPredictions: Boolean = false
        internal set

    @Volatile
    var composingReassertionCount: Int = 0
        internal set

    @Volatile
    var lastKnownCursorPosition: Int = -1
        internal set

    @Volatile
    var lastSpaceTime: Long = 0
        internal set

    @Volatile
    var lastShiftTime: Long = 0
        internal set

    @Volatile
    var lastCommittedWord: String = ""
        internal set

    @Volatile
    var isSecureField: Boolean = false
        internal set

    @Volatile
    var isDirectCommitField: Boolean = false
        internal set

    @Volatile
    var isRawKeyEventField: Boolean = false
        internal set

    @Volatile
    var isUrlOrEmailField: Boolean = false
        internal set

    @Volatile
    var currentInputAction: com.urik.keyboard.model.KeyboardKey.ActionType =
        com.urik.keyboard.model.KeyboardKey.ActionType.ENTER
        internal set

    @Volatile
    var isAcceleratedDeletion = false
        internal set

    @Volatile
    var postCommitReplacementState: PostCommitReplacementState? = null
        internal set

    @Volatile
    var lastAutocorrection: LastAutocorrection? = null
        internal set

    val selectionStateTracker = SelectionStateTracker()

    val requiresDirectCommit: Boolean
        get() = isSecureField || isDirectCommitField

    data class ExpectedTypingOus(val composingStart: Int, val composingEnd: Int, val cursorPosition: Int)

    private val pendingTypingOus = ArrayDeque<ExpectedTypingOus>()

    fun enqueueTypingOus(ous: ExpectedTypingOus) {
        pendingTypingOus.addLast(ous)
    }

    fun tryConsumeTypingOus(cursor: Int, candStart: Int, candEnd: Int): Boolean {
        val expected = pendingTypingOus.firstOrNull() ?: return false
        return if (cursor == expected.cursorPosition &&
            candStart == expected.composingStart &&
            candEnd == expected.composingEnd
        ) {
            pendingTypingOus.removeFirst()
            true
        } else {
            pendingTypingOus.clear()
            false
        }
    }

    fun clearPendingTypingOus() {
        pendingTypingOus.clear()
    }

    fun isComposingCursorAtExpectedEnd(): Boolean = composingRegionStart != -1 &&
        displayBuffer.isNotEmpty() &&
        !isActivelyEditing &&
        lastKnownCursorPosition == composingRegionStart + displayBuffer.length

    fun getSequenceAndBuffer(): Pair<Long, String> = synchronized(processingLock) {
        ++processingSequence to displayBuffer
    }

    fun isSequenceCurrent(sequence: Long, bufferSnapshot: String): Boolean = synchronized(processingLock) {
        sequence == processingSequence && displayBuffer == bufferSnapshot
    }

    fun onComposingReasserted() {
        composingReassertionCount++
        isActivelyEditing = true
    }

    fun onRecompositionSucceeded(word: String, wordStart: Int) {
        displayBuffer = word
        composingRegionStart = wordStart
    }

    fun onSwipeCommitted() {
        displayBuffer = ""
    }

    fun onPronounCapitalized(capitalizedWord: String) {
        displayBuffer = capitalizedWord
    }

    fun clearSuggestionDisplay() {
        viewCallback.clearSuggestions()
    }

    fun updateSuggestionDisplay(suggestions: List<String>) {
        viewCallback.updateSuggestions(suggestions)
    }

    fun clearInternalStateOnly() {
        synchronized(processingLock) {
            processingSequence++
        }

        cancelDebounceJob()
        clearPendingTypingOus()

        isActivelyEditing = true
        isCurrentWordAtSentenceStart = false
        isCurrentWordManualShifted = false
        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        currentRawSuggestions = emptyList()
        isShowingBigramPredictions = false
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null
        postCommitReplacementState = null
        lastAutocorrection = null
        viewCallback.clearSuggestions()
        composingRegionStart = -1
        composingReassertionCount = 0
        lastKnownCursorPosition = -1

        if (!isCapsLockOn()) {
            onShiftStateChanged(false)
        }
    }

    fun clearBigramPredictions() {
        if (isShowingBigramPredictions) {
            isShowingBigramPredictions = false
            pendingSuggestions = emptyList()
            viewCallback.clearSuggestions()
        }
    }

    fun clearSpellConfirmationFields() {
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null
    }

    fun invalidateComposingState() {
        synchronized(processingLock) {
            processingSequence++
        }

        cancelDebounceJob()
        clearPendingTypingOus()

        isActivelyEditing = true

        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        isShowingBigramPredictions = false
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null
        postCommitReplacementState = null
        lastAutocorrection = null
        viewCallback.clearSuggestions()
        composingRegionStart = -1
        lastKnownCursorPosition = -1
        selectionStateTracker.clearExpectedPosition()
    }
}
