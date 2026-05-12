package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CaseTransformer
import com.urik.keyboard.utils.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SwipeWordHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val textInputProcessor: TextInputProcessor,
    private val wordLearningEngine: WordLearningEngine,
    private val languageManager: LanguageManager,
    private val caseTransformer: CaseTransformer,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val swipeDetector: SwipeDetector,
    private val serviceScope: CoroutineScope,
    private val onGetKeyboardState: () -> KeyboardState,
    private val onCoordinateStateClear: () -> Unit,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit,
    private val onDisableShiftAfterSwipe: () -> Unit
) {
    fun handle(validatedWord: String) {
        try {
            inputState.clearBigramPredictions()

            if (inputState.requiresDirectCommit) {
                if (!inputState.isSecureField && !inputState.isRawKeyEventField) {
                    val textBefore = outputBridge.safeGetTextBeforeCursor(1)
                    if (textBefore.isNotEmpty() && !swipeSpaceManager.isWhitespace(textBefore)) {
                        outputBridge.commitText(" ", 1)
                        swipeSpaceManager.markAutoSpaceInserted()
                    }
                }
                outputBridge.commitText(validatedWord, 1)
                return
            }

            if (inputState.displayBuffer.isNotEmpty()) {
                outputBridge.beginBatchEdit()
                try {
                    val pronounLang = languageManager.currentLanguage.value.split("-").first()
                    if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
                        val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
                        if (corrected != null && corrected != inputState.displayBuffer) {
                            inputState.onPronounCapitalized(corrected)
                            outputBridge.setComposingText(corrected, 1)
                        }
                    }
                    swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                    outputBridge.commitText("${inputState.displayBuffer} ", 1)

                    onCoordinateStateClear()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    onCheckAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                outputBridge.clearSpellConfirmationState()
            }

            if (validatedWord.isEmpty()) return

            val keyboardState = onGetKeyboardState()
            val isSentenceStart = keyboardState.isAutoShift
            val isManualShifted =
                keyboardState.isShiftPressed && !keyboardState.isAutoShift && !keyboardState.isCapsLockOn
            inputState.isCurrentWordAtSentenceStart = isSentenceStart
            inputState.isCurrentWordManualShifted = isManualShifted

            val effectiveState =
                if (isManualShifted) {
                    keyboardState.copy(isShiftPressed = true, isAutoShift = false)
                } else {
                    keyboardState
                }

            if (keyboardState.isShiftPressed && !keyboardState.isCapsLockOn) {
                onDisableShiftAfterSwipe()
            }

            serviceScope.launch {
                val swipeScriptCode = textInputProcessor.currentScriptCode
                try {
                    val currentLanguage =
                        languageManager.currentLanguage.value
                            .split("-")
                            .first()

                    val learnedOriginalCase =
                        wordLearningEngine.getLearnedWordOriginalCase(validatedWord, currentLanguage)

                    val displayWord =
                        computeSwipeDisplayWord(
                            validatedWord = validatedWord,
                            learnedOriginalCase = learnedOriginalCase,
                            currentLanguage = currentLanguage,
                            keyboardState = effectiveState,
                            isSentenceStart = isSentenceStart
                        )

                    val result =
                        textInputProcessor.processWordInput(validatedWord, InputMethod.SWIPED)

                    when (result) {
                        is ProcessingResult.Success -> {
                            withContext(Dispatchers.Main) {
                                inputState.isActivelyEditing = true
                                outputBridge.commitPreviousSwipeAndInsertSpace()
                                outputBridge.setComposingText(displayWord, 1)
                                inputState.composingRegionStart =
                                    outputBridge.safeGetCursorPosition() - displayWord.length
                                inputState.displayBuffer = displayWord
                                suggestionPipeline.coordinateStateTransition(result.wordState)

                                if (result.shouldHighlight) {
                                    inputState.spellConfirmationState =
                                        SpellConfirmationState.AWAITING_CONFIRMATION
                                    inputState.pendingWordForLearning = result.wordState.buffer
                                    outputBridge.highlightCurrentWord()
                                }
                            }
                        }

                        is ProcessingResult.Error -> {
                            withContext(Dispatchers.Main) {
                                inputState.isActivelyEditing = true
                                outputBridge.commitPreviousSwipeAndInsertSpace()
                                outputBridge.setComposingText(displayWord, 1)
                                inputState.composingRegionStart =
                                    outputBridge.safeGetCursorPosition() - displayWord.length
                                inputState.displayBuffer = displayWord
                                inputState.wordState =
                                    WordState(
                                        buffer = displayWord,
                                        normalizedBuffer = validatedWord.lowercase(),
                                        isFromSwipe = true,
                                        graphemeCount = displayWord.length,
                                        scriptCode = swipeScriptCode
                                    )
                            }
                        }
                    }
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "handleSwipeWord_commitWord")
                    )
                    withContext(Dispatchers.Main) {
                        val fallbackSuggestion =
                            SpellingSuggestion(
                                word = validatedWord,
                                confidence = 1.0,
                                ranking = 0,
                                source = "swipe",
                                preserveCase = false
                            )
                        val fallbackDisplay = caseTransformer.applyCasing(
                            fallbackSuggestion,
                            effectiveState,
                            isSentenceStart
                        )
                        inputState.isActivelyEditing = true
                        outputBridge.commitPreviousSwipeAndInsertSpace()
                        outputBridge.setComposingText(fallbackDisplay, 1)
                        inputState.composingRegionStart = outputBridge.safeGetCursorPosition() - fallbackDisplay.length
                        inputState.displayBuffer = fallbackDisplay
                        inputState.wordState =
                            WordState(
                                buffer = fallbackDisplay,
                                normalizedBuffer = validatedWord.lowercase(),
                                isFromSwipe = true,
                                graphemeCount = fallbackDisplay.length,
                                scriptCode = swipeScriptCode
                            )
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleSwipeWord")
            )
            outputBridge.setComposingText(validatedWord, 1)
        }
    }

    private fun computeSwipeDisplayWord(
        validatedWord: String,
        learnedOriginalCase: String?,
        currentLanguage: String,
        keyboardState: KeyboardState,
        isSentenceStart: Boolean = false
    ): String {
        val normalizedWord = validatedWord.lowercase()

        if (currentLanguage == "en") {
            val englishPronounForm = getEnglishPronounIForm(normalizedWord)
            if (englishPronounForm != null) {
                return englishPronounForm
            }
        }

        val wordToUse = learnedOriginalCase ?: validatedWord
        val preserveCase = learnedOriginalCase != null

        val suggestion =
            SpellingSuggestion(
                word = wordToUse,
                confidence = 1.0,
                ranking = 0,
                source = if (preserveCase) "learned" else "swipe",
                preserveCase = preserveCase
            )

        return caseTransformer.applyCasing(suggestion, keyboardState, isSentenceStart)
    }

    private fun getEnglishPronounIForm(normalizedWord: String): String? =
        EnglishPronounCorrection.capitalize(normalizedWord)
}
