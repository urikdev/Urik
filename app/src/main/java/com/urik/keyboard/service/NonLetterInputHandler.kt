package com.urik.keyboard.service

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.UrlEmailDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NonLetterInputHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val autoCorrectionEngine: AutoCorrectionEngine,
    private val textInputProcessor: TextInputProcessor,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val languageManager: LanguageManager,
    private val candidateBarController: CandidateBarController,
    private val serviceScope: CoroutineScope,
    private val onGetCurrentSettings: () -> KeyboardSettings,
    private val onCoordinateStateClear: () -> Unit,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit,
    private val onDisableCapsLockAfterPunctuation: () -> Unit
) {
    fun handle(char: String) {
        if (inputState.requiresDirectCommit) {
            outputBridge.sendCharacter(char)
            return
        }
        serviceScope.launch {
            try {
                inputState.lastSpaceTime = 0

                if (char.length == 1) {
                    val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(1)
                    if (swipeSpaceManager.shouldRemoveSpaceForPunctuation(char.single(), textBeforeCursor)) {
                        outputBridge.deleteSurroundingText(1, 0)
                    }
                }

                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        onCoordinateStateClear()
                    }
                }

                if (inputState.postCommitReplacementState != null) {
                    inputState.postCommitReplacementState = null
                    candidateBarController.clearSuggestions()
                }
                inputState.lastAutocorrection = null

                if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    outputBridge.beginBatchEdit()
                    try {
                        suggestionPipeline.confirmAndLearnWord(onCheckAutoCapitalization)
                        outputBridge.commitText(char, 1)

                        if (char.length == 1) {
                            val singleChar = char.single()
                            if (isSentenceEndingPunctuation(singleChar) && !inputState.requiresDirectCommit) {
                                onDisableCapsLockAfterPunctuation()
                                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                onCheckAutoCapitalization(textBefore)
                            }
                        }
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return@launch
                }

                if (inputState.displayBuffer.isNotEmpty() &&
                    onGetCurrentSettings().spellCheckEnabled &&
                    inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                ) {
                    val textBefore = outputBridge.safeGetTextBeforeCursor(100)
                    val isUrlOrEmail =
                        UrlEmailDetector.isUrlOrEmailContext(
                            currentWord = inputState.displayBuffer,
                            textBeforeCursor = textBefore,
                            nextChar = char
                        )

                    if (!isUrlOrEmail) {
                        val isPunctuation =
                            char.length == 1 && CursorEditingUtils.isPunctuation(char.single())

                        if (isPunctuation) {
                            suggestionPipeline.cancelDebounceJob()
                            val isValid = textInputProcessor.validateWord(inputState.displayBuffer)
                            if (!isValid) {
                                outputBridge.beginBatchEdit()
                                try {
                                    val pronounLang = languageManager.currentLanguage.value.split("-").first()
                                    if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
                                        val corrected = EnglishPronounCorrection.capitalize(
                                            inputState.displayBuffer.lowercase()
                                        )
                                        if (corrected != null && corrected != inputState.displayBuffer) {
                                            inputState.onPronounCapitalized(corrected)
                                            outputBridge.setComposingText(corrected, 1)
                                        }
                                    }
                                    suggestionPipeline.learnWordAndInvalidateCache(
                                        inputState.displayBuffer,
                                        InputMethod.TYPED
                                    )
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(char, 1)

                                    val singleChar = char.single()
                                    if (isSentenceEndingPunctuation(singleChar) &&
                                        !inputState.requiresDirectCommit
                                    ) {
                                        onDisableCapsLockAfterPunctuation()
                                        val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                        onCheckAutoCapitalization(textBefore)
                                    }

                                    onCoordinateStateClear()
                                    suggestionPipeline.showBigramPredictions()
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }
                        } else {
                            suggestionPipeline.cancelDebounceJob()
                            val decision = autoCorrectionEngine.decide(
                                buffer = inputState.displayBuffer,
                                spellCheckEnabled = onGetCurrentSettings().spellCheckEnabled,
                                autocorrectionEnabled = onGetCurrentSettings().autocorrectionEnabled,
                                pauseOnMisspelledWord = onGetCurrentSettings().pauseOnMisspelledWord,
                                lastAutocorrection = inputState.lastAutocorrection,
                                textBeforeCursor = textBefore,
                                nextChar = char
                            )
                            when (decision) {
                                is AutocorrectDecision.Pause -> {
                                    inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                    inputState.pendingWordForLearning = inputState.displayBuffer
                                    outputBridge.highlightCurrentWord()
                                    val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                    val displaySuggestions =
                                        suggestionPipeline.storeAndCapitalizeSuggestions(
                                            suggestions,
                                            inputState.isCurrentWordAtSentenceStart
                                        )
                                    inputState.pendingSuggestions = displaySuggestions
                                    if (displaySuggestions.isNotEmpty()) {
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        candidateBarController.clearSuggestions()
                                    }
                                    return@launch
                                }

                                else -> { /* fall through to commit char */ }
                            }
                        }
                    }
                }

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
                    suggestionPipeline.coordinateWordCompletion()
                    suggestionPipeline.showBigramPredictions()
                    outputBridge.commitText(char, 1)

                    if (char.length == 1) {
                        val singleChar = char.single()
                        if (isSentenceEndingPunctuation(singleChar) && !inputState.requiresDirectCommit) {
                            onDisableCapsLockAfterPunctuation()
                            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                            onCheckAutoCapitalization(textBefore)
                        }
                    }
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleNonLetterInput")
                )
            }
        }
    }

    private fun isSentenceEndingPunctuation(char: Char): Boolean =
        UCharacter.hasBinaryProperty(char.code, UProperty.S_TERM)
}
