package com.urik.keyboard.service

import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.utils.CaseTransformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuggestionPipeline(
    private val state: InputStateManager,
    private val outputBridge: OutputBridge,
    private val textInputProcessor: TextInputProcessor,
    private val spellCheckManager: SpellCheckManager,
    private val wordLearningEngine: WordLearningEngine,
    private val wordFrequencyRepository: WordFrequencyRepository,
    private val languageManager: LanguageManager,
    private val caseTransformer: CaseTransformer,
    private val serviceScope: CoroutineScope,
    private val showSuggestions: () -> Boolean,
    private val effectiveSuggestionCount: () -> Int,
    private val getKeyboardState: () -> KeyboardState,
    private val shouldAutoCapitalize: (String) -> Boolean,
    private val currentLanguageProvider: () -> String,
) {
    private var suggestionDebounceJob: Job? = null
    private val suggestionDebounceDelay = SUGGESTION_DEBOUNCE_MS

    fun requestSuggestions(
        buffer: String,
        inputMethod: InputMethod,
        isCharacterInput: Boolean,
        char: String? = null,
    ) {
        val (currentSequence, bufferSnapshot) = state.getSequenceAndBuffer()

        suggestionDebounceJob?.cancel()
        suggestionDebounceJob =
            serviceScope.launch(Dispatchers.Default) {
                try {
                    delay(suggestionDebounceDelay)

                    val result =
                        if (isCharacterInput && char != null) {
                            textInputProcessor.processCharacterInput(char, bufferSnapshot, inputMethod)
                        } else {
                            textInputProcessor.processWordInput(bufferSnapshot, inputMethod)
                        }

                    withContext(Dispatchers.Main) {
                        if (state.isSequenceCurrent(currentSequence, bufferSnapshot)) {
                            when (result) {
                                is ProcessingResult.Success -> {
                                    state.wordState = result.wordState
                                    if (result.wordState.suggestions.isNotEmpty() && showSuggestions()) {
                                        val displaySuggestions =
                                            storeAndCapitalizeSuggestions(
                                                result.wordState.suggestions,
                                                state.isCurrentWordAtSentenceStart,
                                            )
                                        state.pendingSuggestions = displaySuggestions
                                        state.updateSuggestionDisplay(displaySuggestions)
                                    } else {
                                        state.pendingSuggestions = emptyList()
                                        state.clearSuggestionDisplay()
                                    }
                                }

                                is ProcessingResult.Error -> {
                                    state.pendingSuggestions = emptyList()
                                    state.clearSuggestionDisplay()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
    }

    fun coordinateStateTransition(newWordState: WordState) {
        state.wordState = newWordState

        if (newWordState.suggestions.isNotEmpty()) {
            val filteredSuggestions =
                newWordState.suggestions.filterNot { suggestion ->
                    suggestion.word.equals(state.displayBuffer, ignoreCase = true)
                }

            val displaySuggestions = storeAndCapitalizeSuggestions(filteredSuggestions, state.isCurrentWordAtSentenceStart)
            state.pendingSuggestions = displaySuggestions
            state.updateSuggestionDisplay(displaySuggestions)
        } else {
            state.pendingSuggestions = emptyList()
            state.clearSuggestionDisplay()
        }
    }

    fun storeAndCapitalizeSuggestions(
        suggestions: List<SpellingSuggestion>,
        isSentenceStart: Boolean = false,
    ): List<String> {
        state.currentRawSuggestions = suggestions
        return capitalizeSuggestions(suggestions, isSentenceStart)
    }

    fun capitalizeSuggestions(
        suggestions: List<SpellingSuggestion>,
        isSentenceStart: Boolean = false,
    ): List<String> {
        var keyboardState = getKeyboardState()
        if (state.isCurrentWordManualShifted && !keyboardState.isShiftPressed && !keyboardState.isCapsLockOn) {
            keyboardState = keyboardState.copy(isShiftPressed = true, isAutoShift = false)
        }
        return caseTransformer.applyCasingToSuggestions(suggestions, keyboardState, isSentenceStart)
    }

    fun showBigramPredictions() {
        if (state.requiresDirectCommit || !showSuggestions() || state.lastCommittedWord.isBlank()) {
            return
        }

        serviceScope.launch {
            try {
                val currentLanguage = languageManager.currentLanguage.value
                val allPredictions =
                    wordFrequencyRepository.getBigramPredictions(
                        state.lastCommittedWord,
                        currentLanguage,
                        effectiveSuggestionCount(),
                    )

                val predictions = allPredictions.filter { !spellCheckManager.isWordBlacklisted(it) }

                if (predictions.isNotEmpty() && state.displayBuffer.isEmpty()) {
                    val suggestionObjects =
                        predictions.mapIndexed { index, word ->
                            SpellingSuggestion(
                                word = word,
                                confidence = 0.85 - (index * 0.02),
                                ranking = index,
                                source = "bigram",
                                preserveCase = false,
                            )
                        }
                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    val bigramSentenceStart = shouldAutoCapitalize(textBefore)
                    val displayPredictions = storeAndCapitalizeSuggestions(suggestionObjects, bigramSentenceStart)
                    withContext(Dispatchers.Main) {
                        if (state.displayBuffer.isEmpty()) {
                            state.isShowingBigramPredictions = true
                            state.pendingSuggestions = displayPredictions
                            state.updateSuggestionDisplay(displayPredictions)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun learnWordAndInvalidateCache(
        word: String,
        inputMethod: InputMethod,
    ): Boolean =
        try {
            val settings = textInputProcessor.getCurrentSettings()
            if (!settings.isWordLearningEnabled) {
                return true
            }

            recordWordUsage(word)

            val isInDictionary = spellCheckManager.isWordInSymSpellDictionary(word)
            if (isInDictionary) {
                return true
            }

            val learnResult = wordLearningEngine.learnWord(word, inputMethod)
            if (learnResult.isSuccess) {
                spellCheckManager.invalidateWordCache(word)
                spellCheckManager.removeFromBlacklist(word)
                textInputProcessor.invalidateWord(word)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

    internal fun recordWordUsage(word: String) {
        try {
            val currentLanguage = languageManager.currentLanguage.value
            wordFrequencyRepository.incrementFrequency(word, currentLanguage)

            if (state.lastCommittedWord.isNotBlank()) {
                wordFrequencyRepository.recordBigram(state.lastCommittedWord, word, currentLanguage)
            }
            state.lastCommittedWord = word
        } catch (_: Exception) {
        }
    }

    suspend fun confirmAndLearnWord(
        checkAutoCapitalization: (String) -> Unit,
    ) {
        withContext(Dispatchers.Main) {
            val wordToLearn = state.pendingWordForLearning

            state.isActivelyEditing = true

            if (wordToLearn != null) {
                learnWordAndInvalidateCache(wordToLearn, InputMethod.TYPED)
            }

            outputBridge.beginBatchEdit()
            try {
                outputBridge.autoCapitalizePronounI(currentLanguageProvider)
                if (state.displayBuffer.isNotEmpty()) {
                    outputBridge.updateLastCommittedWord(state.displayBuffer)
                }
                outputBridge.finishComposingText()
                outputBridge.commitText(" ")
                state.clearInternalStateOnly()
                showBigramPredictions()
            } catch (_: Exception) {
                outputBridge.finishComposingText()
                outputBridge.commitText(" ")
                state.clearInternalStateOnly()
            } finally {
                outputBridge.endBatchEdit()
            }

            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        }
    }

    suspend fun coordinateSuggestionSelection(
        suggestion: String,
        checkAutoCapitalization: (String) -> Unit,
    ) {
        withContext(Dispatchers.Main) {
            try {
                val actualCursorPos = outputBridge.safeGetCursorPosition()

                if (state.composingRegionStart != -1 && state.displayBuffer.isNotEmpty()) {
                    val expectedCursorRange =
                        state.composingRegionStart..(state.composingRegionStart + state.displayBuffer.length)
                    if (actualCursorPos !in expectedCursorRange) {
                        outputBridge.invalidateComposingStateOnCursorJump()
                        return@withContext
                    }
                }

                state.isActivelyEditing = true

                recordWordUsage(suggestion)

                outputBridge.beginBatchEdit()
                try {
                    outputBridge.commitText("$suggestion ")

                    val expectedNewPosition =
                        if (state.composingRegionStart != -1) {
                            state.composingRegionStart + suggestion.length + 1
                        } else {
                            actualCursorPos + suggestion.length + 1
                        }
                    state.selectionStateTracker.setExpectedPositionAfterOperation(expectedNewPosition)
                    state.lastKnownCursorPosition = expectedNewPosition

                    outputBridge.coordinateStateClear()
                    showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (_: Exception) {
                outputBridge.coordinateStateClear()
            }
        }
    }

    suspend fun coordinateWordCompletion() {
        withContext(Dispatchers.Main) {
            try {
                state.isActivelyEditing = true
                outputBridge.coordinateStateClear()
            } catch (_: Exception) {
                outputBridge.coordinateStateClear()
            }
        }
    }

    fun cancelDebounceJob() {
        suggestionDebounceJob?.cancel()
    }

    private companion object {
        const val SUGGESTION_DEBOUNCE_MS = 10L
    }
}
