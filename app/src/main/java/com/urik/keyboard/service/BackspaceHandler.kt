package com.urik.keyboard.service

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import com.urik.keyboard.utils.BackspaceUtils
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.ErrorLogger

class BackspaceHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val candidateBarController: CandidateBarController,
    private val layoutManager: KeyboardLayoutManager,
    private val onCoordinateStateClear: () -> Unit,
    private val onInvalidateComposingState: () -> Unit,
    private val onDisableShiftAfterBackspace: () -> Unit,
    private val onGetKeyboardState: () -> KeyboardState,
    private val onSendDownUpKeyEvents: (keyCode: Int) -> Unit
) {
    fun handle() {
        try {
            if (inputState.isTerminalField) {
                outputBridge.sendBackspace()
                return
            }

            val actualCursorPos = outputBridge.safeGetCursorPosition()

            if (inputState.displayBuffer.isNotEmpty() && inputState.composingRegionStart != -1) {
                @Suppress("UnnecessaryParentheses")
                val expectedCursorRange =
                    inputState.composingRegionStart..(inputState.composingRegionStart + inputState.displayBuffer.length)
                if (actualCursorPos !in expectedCursorRange) {
                    onInvalidateComposingState()
                }
            }

            if (inputState.lastKnownCursorPosition != -1 && actualCursorPos != -1) {
                val cursorDrift = kotlin.math.abs(actualCursorPos - inputState.lastKnownCursorPosition)
                if (cursorDrift > NON_SEQUENTIAL_JUMP_THRESHOLD) {
                    onInvalidateComposingState()
                }
            }

            val selectedText = outputBridge.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                outputBridge.commitText("", 1)
                onCoordinateStateClear()
                return
            }

            if (inputState.isDirectCommitField) {
                val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(1)
                if (textBeforeCursor.isNotEmpty()) {
                    outputBridge.deleteSurroundingText(
                        BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor),
                        0
                    )
                } else {
                    onSendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
                }
                return
            }

            if (inputState.displayBuffer.isEmpty()) {
                val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(1)
                if (textBeforeCursor.isEmpty()) {
                    if (inputState.isAcceleratedDeletion) {
                        layoutManager.stopAcceleratedBackspace()
                    }
                    return
                }
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.wordState.isFromSwipe) {
                outputBridge.setComposingText("", 1)
                onCoordinateStateClear()
                return
            }

            if (inputState.isSecureField) {
                val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(WORD_BOUNDARY_CONTEXT_LENGTH)
                if (textBeforeCursor.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                    outputBridge.deleteSurroundingText(graphemeLength, 0)
                }
                return
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                inputState.clearSpellConfirmationFields()
            }

            if (inputState.postCommitReplacementState != null) {
                inputState.postCommitReplacementState = null
                candidateBarController.clearSuggestions()
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.composingRegionStart != -1) {
                val cursorOffsetInWord = (actualCursorPos - inputState.composingRegionStart).coerceIn(
                    0,
                    inputState.displayBuffer.length
                )
                val charsAfterCursorInWord = inputState.displayBuffer.length - cursorOffsetInWord

                val textBeforePart = if (cursorOffsetInWord > 0) {
                    outputBridge.safeGetTextBeforeCursor(cursorOffsetInWord).takeLast(cursorOffsetInWord)
                } else {
                    ""
                }
                val textAfterPart = if (charsAfterCursorInWord > 0) {
                    outputBridge.safeGetTextAfterCursor(charsAfterCursorInWord).take(charsAfterCursorInWord)
                } else {
                    ""
                }

                if (textBeforePart + textAfterPart != inputState.displayBuffer) {
                    onCoordinateStateClear()
                    handleCommittedTextBackspace()
                    return
                }
            } else if (inputState.displayBuffer.isNotEmpty()) {
                val currentText = outputBridge.safeGetTextBeforeCursor(inputState.displayBuffer.length + 10)
                val expectedComposingText =
                    if (currentText.length >= inputState.displayBuffer.length) {
                        currentText.substring(maxOf(0, currentText.length - inputState.displayBuffer.length))
                    } else {
                        ""
                    }

                if (expectedComposingText != inputState.displayBuffer) {
                    onCoordinateStateClear()
                    handleCommittedTextBackspace()
                    return
                }
            }

            inputState.isActivelyEditing = true

            if (inputState.displayBuffer.isNotEmpty()) {
                val cursorPosInWord =
                    if (inputState.composingRegionStart != -1) {
                        CursorEditingUtils.calculateCursorPositionInWord(
                            actualCursorPos,
                            inputState.composingRegionStart,
                            inputState.displayBuffer.length
                        )
                    } else {
                        val potentialStart = actualCursorPos - inputState.displayBuffer.length
                        if (potentialStart >= 0) {
                            inputState.composingRegionStart = potentialStart
                            CursorEditingUtils.calculateCursorPositionInWord(
                                actualCursorPos,
                                inputState.composingRegionStart,
                                inputState.displayBuffer.length
                            )
                        } else {
                            inputState.displayBuffer.length
                        }
                    }

                if (cursorPosInWord == 0) {
                    outputBridge.beginBatchEdit()
                    try {
                        onCoordinateStateClear()
                        val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                        if (textBefore.isNotEmpty()) {
                            val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBefore)
                            outputBridge.deleteSurroundingText(graphemeLength, 0)
                        }
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return
                }

                if (cursorPosInWord > 0) {
                    val deletedChar = inputState.displayBuffer.getOrNull(cursorPosInWord - 1)
                    val shouldResetShift =
                        deletedChar != null &&
                            isSentenceEndingPunctuation(deletedChar) &&
                            onGetKeyboardState().isShiftPressed &&
                            !onGetKeyboardState().isCapsLockOn

                    val previousLength = inputState.displayBuffer.length
                    inputState.displayBuffer =
                        BackspaceUtils.deleteGraphemeClusterBeforePosition(inputState.displayBuffer, cursorPosInWord)
                    val graphemeDeleted = previousLength - inputState.displayBuffer.length
                    val newCursorPositionInText = cursorPosInWord - graphemeDeleted

                    if (shouldResetShift) {
                        onDisableShiftAfterBackspace()
                    }

                    if (inputState.displayBuffer.isNotEmpty()) {
                        val needsCursorRepositioning =
                            inputState.composingRegionStart != -1 &&
                                newCursorPositionInText != inputState.displayBuffer.length

                        if (needsCursorRepositioning) {
                            outputBridge.beginBatchEdit()
                            try {
                                outputBridge.setComposingText(inputState.displayBuffer, 1)
                                val absoluteCursorPosition = inputState.composingRegionStart + newCursorPositionInText
                                outputBridge.setSelection(absoluteCursorPosition, absoluteCursorPosition)
                            } finally {
                                outputBridge.endBatchEdit()
                            }
                        } else {
                            outputBridge.setComposingText(inputState.displayBuffer, 1)
                        }

                        if (!inputState.isAcceleratedDeletion && !inputState.isUrlOrEmailField) {
                            suggestionPipeline.requestSuggestions(
                                buffer = inputState.displayBuffer,
                                inputMethod = InputMethod.TYPED
                            )
                        }
                    } else {
                        outputBridge.setComposingText("", 1)
                        onCoordinateStateClear()
                    }
                }
            } else {
                handleCommittedTextBackspace()
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleBackspace")
            )
            try {
                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                if (textBefore.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBefore)
                    outputBridge.deleteSurroundingText(graphemeLength, 0)
                }
            } catch (e2: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e2,
                    context = mapOf("operation" to "handleBackspace_fallback")
                )
            }
            onCoordinateStateClear()
        }
    }

    private fun handleCommittedTextBackspace() {
        try {
            val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(WORD_BOUNDARY_CONTEXT_LENGTH)

            if (textBeforeCursor.isNotEmpty()) {
                val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                val deletedChar = textBeforeCursor.lastOrNull()
                val cursorPositionBeforeDeletion = outputBridge.safeGetCursorPosition()

                if (deletedChar == '\n') {
                    outputBridge.beginBatchEdit()
                    try {
                        inputState.isActivelyEditing = true
                        outputBridge.finishComposingText()
                        outputBridge.deleteSurroundingText(graphemeLength, 0)
                        onCoordinateStateClear()
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return
                }

                outputBridge.beginBatchEdit()
                try {
                    inputState.isActivelyEditing = true
                    outputBridge.finishComposingText()
                    outputBridge.deleteSurroundingText(graphemeLength, 0)

                    val expectedNewPosition = cursorPositionBeforeDeletion - graphemeLength
                    inputState.selectionStateTracker.setExpectedPositionAfterOperation(expectedNewPosition)
                    inputState.lastKnownCursorPosition = expectedNewPosition

                    if (deletedChar != null) {
                        val shouldResetShift =
                            if (isSentenceEndingPunctuation(deletedChar)) {
                                true
                            } else if (deletedChar.isWhitespace()) {
                                val trimmed = textBeforeCursor.dropLast(graphemeLength).trimEnd()
                                trimmed.isNotEmpty() && isSentenceEndingPunctuation(trimmed.last())
                            } else {
                                false
                            }

                        if (shouldResetShift &&
                            onGetKeyboardState().isShiftPressed &&
                            !onGetKeyboardState().isCapsLockOn
                        ) {
                            onDisableShiftAfterBackspace()
                        }
                    }

                    if (!inputState.isAcceleratedDeletion && !inputState.isUrlOrEmailField) {
                        val remainingText = textBeforeCursor.dropLast(graphemeLength)

                        if (remainingText.isNotEmpty() && remainingText.last() == '\n') {
                            onCoordinateStateClear()
                            return
                        }

                        val composingRegion =
                            outputBridge.calculateParagraphBoundedComposingRegion(
                                remainingText,
                                expectedNewPosition
                            )

                        if (composingRegion != null) {
                            val (wordStart, wordEnd, word) = composingRegion

                            val actualCursorPos = outputBridge.safeGetCursorPosition()
                            if (CursorEditingUtils.shouldAbortRecomposition(
                                    expectedCursorPosition = expectedNewPosition,
                                    actualCursorPosition = actualCursorPos,
                                    expectedComposingStart = wordStart,
                                    actualComposingStart = inputState.composingRegionStart
                                )
                            ) {
                                onCoordinateStateClear()
                                return
                            }

                            outputBridge.setComposingRegion(wordStart, wordEnd)

                            inputState.displayBuffer = word
                            inputState.composingRegionStart = wordStart

                            val autocorrection = inputState.lastAutocorrection
                            if (autocorrection != null &&
                                word.equals(autocorrection.correctedWord, ignoreCase = true)
                            ) {
                                outputBridge.setComposingText(autocorrection.originalTypedWord, 1)
                                inputState.displayBuffer = autocorrection.originalTypedWord
                                inputState.pendingSuggestions = emptyList()
                                candidateBarController.clearSuggestions()
                            } else {
                                inputState.lastAutocorrection = null
                                suggestionPipeline.requestSuggestions(
                                    buffer = word,
                                    inputMethod = InputMethod.TYPED
                                )
                            }
                        } else {
                            onCoordinateStateClear()
                        }
                    }
                } finally {
                    outputBridge.endBatchEdit()
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleCommittedTextBackspace")
            )
            onCoordinateStateClear()
        }
    }

    private fun isSentenceEndingPunctuation(char: Char): Boolean =
        UCharacter.hasBinaryProperty(char.code, UProperty.S_TERM)

    private companion object {
        const val NON_SEQUENTIAL_JUMP_THRESHOLD = 5
        const val WORD_BOUNDARY_CONTEXT_LENGTH = 64
    }
}
