package com.urik.keyboard.service

import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.UrlEmailDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SpaceInputHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val autoCorrectionEngine: AutoCorrectionEngine,
    private val textInputProcessor: TextInputProcessor,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val swipeDetector: SwipeDetector,
    private val candidateBarController: CandidateBarController,
    private val languageManager: LanguageManager,
    private val serviceScope: CoroutineScope,
    private val onGetCurrentSettings: () -> KeyboardSettings,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit,
    private val onJapaneseSpaceNextCandidate: () -> Unit = {}
) {
    fun handle() {
        serviceScope.launch {
            try {
                if (inputState.requiresDirectCommit) {
                    outputBridge.sendSpace()
                    return@launch
                }

                if (inputState.isSuggestionsDisabled) {
                    outputBridge.sendSpace()
                    return@launch
                }

                if (suggestionPipeline.isJapaneseLayout && inputState.displayBuffer.isNotEmpty()) {
                    onJapaneseSpaceNextCandidate()
                    return@launch
                }

                swipeSpaceManager.clearAutoSpaceFlag()

                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        inputState.clearInternalStateOnly()
                        outputBridge.finishComposingText()
                    }
                }

                val currentTime = System.currentTimeMillis()
                val timeSinceLastSpace = currentTime - inputState.lastSpaceTime

                if (handleDoubleSpacePeriod(timeSinceLastSpace)) return@launch

                inputState.lastSpaceTime = currentTime

                if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    suggestionPipeline.confirmAndLearnWord(onCheckAutoCapitalization)
                    return@launch
                }

                if (inputState.displayBuffer.isNotEmpty() &&
                    onGetCurrentSettings().spellCheckEnabled &&
                    inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                ) {
                    val textBeforeForUrlCheck = outputBridge.safeGetTextBeforeCursor(100)
                    val isUrlOrEmail =
                        UrlEmailDetector.isUrlOrEmailContext(
                            currentWord = inputState.displayBuffer,
                            textBeforeCursor = textBeforeForUrlCheck,
                            nextChar = " "
                        )

                    if (!isUrlOrEmail) {
                        suggestionPipeline.cancelDebounceJob()
                        val decision = autoCorrectionEngine.decide(
                            buffer = inputState.displayBuffer,
                            spellCheckEnabled = onGetCurrentSettings().spellCheckEnabled,
                            autocorrectionEnabled = onGetCurrentSettings().autocorrectionEnabled,
                            pauseOnMisspelledWord = onGetCurrentSettings().pauseOnMisspelledWord,
                            lastAutocorrection = inputState.lastAutocorrection,
                            textBeforeCursor = textBeforeForUrlCheck,
                            nextChar = " "
                        )
                        when (decision) {
                            is AutocorrectDecision.None -> {
                                inputState.isActivelyEditing = true
                                suggestionPipeline.recordWordUsage(inputState.displayBuffer)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()
                                    suggestionPipeline.showBigramPredictions()

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.ContractionBypass -> {
                                val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                val originalWord = inputState.displayBuffer
                                inputState.isActivelyEditing = true
                                suggestionPipeline.learnWordAndInvalidateCache(originalWord, InputMethod.TYPED)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(originalWord)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()

                                    if (displaySuggestions.isNotEmpty()) {
                                        inputState.postCommitReplacementState =
                                            PostCommitReplacementState(
                                                originalWord = originalWord,
                                                committedWord = originalWord
                                            )
                                        inputState.pendingSuggestions = displaySuggestions
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        suggestionPipeline.showBigramPredictions()
                                    }

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Pause -> {
                                val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                inputState.pendingWordForLearning = inputState.displayBuffer
                                outputBridge.highlightCurrentWord()
                                inputState.pendingSuggestions = displaySuggestions
                                if (displaySuggestions.isNotEmpty()) {
                                    candidateBarController.updateSuggestions(displaySuggestions)
                                } else {
                                    candidateBarController.clearSuggestions()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Correct -> {
                                val originalWord = inputState.displayBuffer
                                val rawCorrected = decision.suggestion
                                val correctedWord = if (inputState.isCurrentWordAtSentenceStart) {
                                    rawCorrected.replaceFirstChar { it.uppercaseChar() }
                                } else {
                                    rawCorrected
                                }
                                inputState.isActivelyEditing = true
                                suggestionPipeline.recordWordUsage(correctedWord)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    outputBridge.commitText("$correctedWord ", 1)
                                    swipeDetector.updateLastCommittedWord(correctedWord)
                                    inputState.clearInternalStateOnly()
                                    inputState.postCommitReplacementState =
                                        PostCommitReplacementState(
                                            originalWord = originalWord,
                                            committedWord = correctedWord
                                        )
                                    inputState.lastAutocorrection =
                                        LastAutocorrection(
                                            originalTypedWord = originalWord,
                                            correctedWord = correctedWord
                                        )
                                    inputState.pendingSuggestions = listOf(originalWord)
                                    candidateBarController.updateSuggestions(inputState.pendingSuggestions)

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Suggestions -> {
                                val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                val originalWord = inputState.displayBuffer
                                inputState.isActivelyEditing = true
                                suggestionPipeline.learnWordAndInvalidateCache(originalWord, InputMethod.TYPED)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(originalWord)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()

                                    if (displaySuggestions.isNotEmpty()) {
                                        inputState.postCommitReplacementState =
                                            PostCommitReplacementState(
                                                originalWord = originalWord,
                                                committedWord = originalWord
                                            )
                                        inputState.pendingSuggestions = displaySuggestions
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        suggestionPipeline.showBigramPredictions()
                                    }

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }
                        }
                    }
                }

                outputBridge.beginBatchEdit()
                try {
                    applyPronounCorrectionIfNeeded()
                    if (inputState.displayBuffer.isNotEmpty()) {
                        swipeDetector.updateLastCommittedWord(
                            inputState.displayBuffer
                        )
                    }
                    outputBridge.finishComposingText()
                    outputBridge.commitText(" ", 1)
                    inputState.clearInternalStateOnly()
                    suggestionPipeline.showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    onCheckAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleSpace")
                )
                outputBridge.finishComposingText()
                outputBridge.commitText(" ", 1)
                inputState.clearInternalStateOnly()
            }
        }
    }

    private fun handleDoubleSpacePeriod(timeSinceLastSpace: Long): Boolean {
        if (timeSinceLastSpace > DOUBLE_TAP_SPACE_THRESHOLD_MS ||
            inputState.spellConfirmationState != SpellConfirmationState.NORMAL ||
            !onGetCurrentSettings().doubleSpacePeriod
        ) {
            return false
        }
        outputBridge.beginBatchEdit()
        try {
            if (inputState.wordState.hasContent && !inputState.requiresDirectCommit) {
                inputState.clearInternalStateOnly()
                outputBridge.finishComposingText()
            }
            outputBridge.deleteSurroundingText(1, 0)
            outputBridge.commitText(". ", 1)
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            onCheckAutoCapitalization(textBefore)
        } finally {
            outputBridge.endBatchEdit()
        }
        inputState.lastSpaceTime = 0
        return true
    }

    private fun applyPronounCorrectionIfNeeded() {
        val pronounLang = languageManager.currentLanguage.value.split("-").first()
        if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
            val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
            if (corrected != null && corrected != inputState.displayBuffer) {
                inputState.onPronounCapitalized(corrected)
                outputBridge.setComposingText(corrected, 1)
            }
        }
    }

    private companion object {
        const val DOUBLE_TAP_SPACE_THRESHOLD_MS = 250L
    }
}
