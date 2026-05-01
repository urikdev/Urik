package com.urik.keyboard.service

import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.utils.CaseTransformer
import com.urik.keyboard.utils.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuggestionPipeline(
    private val host: SuggestionPipelineHost,
    private val state: InputStateManager,
    private val outputBridge: OutputBridge,
    private val textInputProcessor: TextInputProcessor,
    private val spellCheckManager: SpellCheckManager,
    private val wordLearningEngine: WordLearningEngine,
    private val wordFrequencyRepository: WordFrequencyRepository,
    private val languageManager: LanguageManager,
    private val caseTransformer: CaseTransformer,
    private val scriptConverterRegistry: ScriptConverterRegistry,
    private val serviceScope: CoroutineScope
) {
    private var suggestionDebounceJob: Job? = null
    private val suggestionDebounceDelay = SUGGESTION_DEBOUNCE_MS
    var isJapaneseLayout: Boolean = false
        private set

    fun setJapaneseLayout(japanese: Boolean) {
        isJapaneseLayout = japanese
    }

    fun requestSuggestions(buffer: String, inputMethod: InputMethod) {
        if (isJapaneseLayout) {
            requestJapaneseSuggestions(buffer)
            return
        }
        val (currentSequence, bufferSnapshot) = state.getSequenceAndBuffer()

        suggestionDebounceJob?.cancel()
        suggestionDebounceJob =
            serviceScope.launch(Dispatchers.Default) {
                try {
                    delay(suggestionDebounceDelay)

                    val result = textInputProcessor.processWordInput(bufferSnapshot, inputMethod)

                    withContext(Dispatchers.Main) {
                        if (state.isSequenceCurrent(currentSequence, bufferSnapshot)) {
                            when (result) {
                                is ProcessingResult.Success -> {
                                    state.wordState = result.wordState
                                    if (result.wordState.suggestions.isNotEmpty() && host.showSuggestions()) {
                                        val displaySuggestions =
                                            storeAndCapitalizeSuggestions(
                                                result.wordState.suggestions,
                                                state.isCurrentWordAtSentenceStart
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
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SuggestionPipeline",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "requestSuggestions")
                    )
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

            val displaySuggestions =
                storeAndCapitalizeSuggestions(filteredSuggestions, state.isCurrentWordAtSentenceStart)
            state.pendingSuggestions = displaySuggestions
            state.updateSuggestionDisplay(displaySuggestions)
        } else {
            state.pendingSuggestions = emptyList()
            state.clearSuggestionDisplay()
        }
    }

    fun storeAndCapitalizeSuggestions(
        suggestions: List<SpellingSuggestion>,
        isSentenceStart: Boolean = false
    ): List<String> {
        state.currentRawSuggestions = suggestions
        return capitalizeSuggestions(suggestions, isSentenceStart)
    }

    fun capitalizeSuggestions(suggestions: List<SpellingSuggestion>, isSentenceStart: Boolean = false): List<String> {
        var keyboardState = host.getKeyboardState()
        if (state.isCurrentWordManualShifted && !keyboardState.isShiftPressed && !keyboardState.isCapsLockOn) {
            keyboardState = keyboardState.copy(isShiftPressed = true, isAutoShift = false)
        }
        return caseTransformer.applyCasingToSuggestions(suggestions, keyboardState, isSentenceStart)
    }

    fun showBigramPredictions() {
        if (state.requiresDirectCommit || !host.showSuggestions() || state.lastCommittedWord.isBlank()) {
            return
        }

        serviceScope.launch {
            try {
                val currentLanguage = languageManager.currentLanguage.value
                val allPredictions =
                    wordFrequencyRepository.getBigramPredictions(
                        state.lastCommittedWord,
                        currentLanguage,
                        host.effectiveSuggestionCount()
                    )

                val predictions = allPredictions.filter { !spellCheckManager.isWordBlacklisted(it) }

                if (predictions.isNotEmpty() && state.displayBuffer.isEmpty()) {
                    val suggestionObjects =
                        predictions.mapIndexed { index, word ->
                            SpellingSuggestion(
                                word = word,
                                confidence = 0.85 - index * 0.02,
                                ranking = index,
                                source = "bigram",
                                preserveCase = false
                            )
                        }
                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    val bigramSentenceStart = host.shouldAutoCapitalize(textBefore)
                    val displayPredictions = storeAndCapitalizeSuggestions(suggestionObjects, bigramSentenceStart)
                    withContext(Dispatchers.Main) {
                        if (state.displayBuffer.isEmpty()) {
                            state.isShowingBigramPredictions = true
                            state.pendingSuggestions = displayPredictions
                            state.updateSuggestionDisplay(displayPredictions)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "showBigramPredictions")
                )
            }
        }
    }

    suspend fun learnWordAndInvalidateCache(word: String, inputMethod: InputMethod): Boolean = try {
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
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SuggestionPipeline",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "learnWordAndInvalidateCache")
        )
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SuggestionPipeline",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "recordWordUsage")
            )
        }
    }

    suspend fun confirmAndLearnWord(checkAutoCapitalization: (String) -> Unit) {
        withContext(Dispatchers.Main) {
            val wordToLearn = state.pendingWordForLearning

            state.isActivelyEditing = true

            if (wordToLearn != null) {
                learnWordAndInvalidateCache(wordToLearn, InputMethod.TYPED)
            }

            outputBridge.beginBatchEdit()
            try {
                val lang = host.currentLanguage().split("-").first()
                if (lang == "en" && state.displayBuffer.isNotEmpty()) {
                    val corrected = EnglishPronounCorrection.capitalize(state.displayBuffer.lowercase())
                    if (corrected != null && corrected != state.displayBuffer) {
                        state.onPronounCapitalized(corrected)
                        outputBridge.setComposingText(corrected, 1)
                    }
                }
                if (state.displayBuffer.isNotEmpty()) {
                    outputBridge.updateLastCommittedWord(state.displayBuffer)
                }
                outputBridge.finishComposingText()
                outputBridge.commitText(" ")
                state.clearInternalStateOnly()
                showBigramPredictions()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "confirmAndLearnWord")
                )
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

    suspend fun coordinateSuggestionSelection(suggestion: String, checkAutoCapitalization: (String) -> Unit) {
        withContext(Dispatchers.Main) {
            try {
                val actualCursorPos = outputBridge.safeGetCursorPosition()

                if (state.composingRegionStart != -1 && state.displayBuffer.isNotEmpty()) {
                    @Suppress("UnnecessaryParentheses")
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
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinateSuggestionSelection")
                )
                outputBridge.coordinateStateClear()
            }
        }
    }

    suspend fun coordinatePostCommitReplacement(
        selectedSuggestion: String,
        replacementState: PostCommitReplacementState,
        checkAutoCapitalization: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            try {
                val deleteLength = replacementState.committedWord.length + 1
                val textBefore = outputBridge.safeGetTextBeforeCursor(deleteLength)
                val expectedText = replacementState.committedWord + " "
                if (textBefore != expectedText) {
                    state.postCommitReplacementState = null
                    state.clearSuggestionDisplay()
                    return@withContext
                }

                state.isActivelyEditing = true

                outputBridge.beginBatchEdit()
                try {
                    outputBridge.deleteSurroundingText(deleteLength, 0)
                    outputBridge.commitText("$selectedSuggestion ", 1)

                    val isAutocorrectUndo = replacementState.committedWord != replacementState.originalWord
                    if (isAutocorrectUndo) {
                        learnWordAndInvalidateCache(selectedSuggestion, InputMethod.TYPED)
                        val currentLanguage = languageManager.currentLanguage.value
                        wordFrequencyRepository.incrementFrequency(selectedSuggestion, currentLanguage)
                        wordFrequencyRepository.incrementFrequency(selectedSuggestion, currentLanguage)
                    } else {
                        recordWordUsage(selectedSuggestion)
                    }
                    outputBridge.updateLastCommittedWord(selectedSuggestion)
                    state.postCommitReplacementState = null
                    state.pendingSuggestions = emptyList()
                    state.clearSuggestionDisplay()
                    showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinatePostCommitReplacement")
                )
                state.postCommitReplacementState = null
                state.clearSuggestionDisplay()
            }
        }
    }

    suspend fun coordinateWordCompletion() {
        withContext(Dispatchers.Main) {
            try {
                state.isActivelyEditing = true
                outputBridge.coordinateStateClear()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinateWordCompletion")
                )
                outputBridge.coordinateStateClear()
            }
        }
    }

    private fun requestJapaneseSuggestions(hiraganaBuffer: String) {
        suggestionDebounceJob?.cancel()
        suggestionDebounceJob = serviceScope.launch(Dispatchers.Default) {
            try {
                delay(suggestionDebounceDelay)

                val rawCandidates = scriptConverterRegistry
                    .forLanguage(host.currentLanguage())
                    ?.getCandidates(hiraganaBuffer, host.currentLanguage())
                    ?: emptyList()
                val conversionCandidates = rawCandidates.map { candidate ->
                    SpellingSuggestion(
                        word = candidate.surface,
                        confidence = candidate.frequency.toDouble(),
                        ranking = 0,
                        source = candidate.source
                    )
                }

                val symspellCompletions = if (host.showSuggestions()) {
                    spellCheckManager.getSpellingSuggestionsWithConfidence(hiraganaBuffer)
                        .filter { it.source == "completion" }
                } else {
                    emptyList()
                }

                val combined = (conversionCandidates + symspellCompletions)
                    .distinctBy { it.word }
                    .take(host.effectiveSuggestionCount())

                withContext(Dispatchers.Main) {
                    if (combined.isNotEmpty()) {
                        state.pendingSuggestions = combined.map { it.word }
                        state.currentRawSuggestions = combined
                        state.updateSuggestionDisplay(combined.map { it.word })
                    } else {
                        state.pendingSuggestions = emptyList()
                        state.clearSuggestionDisplay()
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "requestJapaneseSuggestions")
                )
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
