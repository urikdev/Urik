package com.urik.keyboard.service

import com.urik.keyboard.ui.keyboard.components.StreamingScoringEngine

class ImeStateCoordinator(
    private val outputBridge: OutputBridge,
    private val streamingScoringEngine: StreamingScoringEngine,
    private val inputState: InputStateManager,
    private val spellCheckManager: SpellCheckManager,
    private val textInputProcessor: TextInputProcessor,
    private val wordLearningEngine: WordLearningEngine
) {
    fun coordinateStateClear() {
        streamingScoringEngine.cancelActiveGesture()
        outputBridge.coordinateStateClear()
    }

    fun invalidateComposingStateOnCursorJump() {
        outputBridge.invalidateComposingStateOnCursorJump()
    }

    fun clearSecureFieldState() {
        inputState.clearInternalStateOnly()
        outputBridge.finishComposingText()

        spellCheckManager.clearCaches()
        spellCheckManager.clearBlacklist()
        textInputProcessor.clearCaches()
        wordLearningEngine.clearCurrentLanguageCache()

        inputState.lastSpaceTime = 0
        inputState.lastShiftTime = 0
    }
}
