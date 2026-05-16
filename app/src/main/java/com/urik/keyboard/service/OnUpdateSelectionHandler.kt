package com.urik.keyboard.service

import androidx.annotation.VisibleForTesting
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.SelectionChangeResult

class OnUpdateSelectionHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val imeStateCoordinator: ImeStateCoordinator,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit
) {
    fun handle(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (handleDirectCommitInProgress()) return

        val selectionResult =
            inputState.selectionStateTracker.updateSelection(
                newSelStart = newSelStart,
                newSelEnd = newSelEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd
            )

        if (newSelStart == 0 && newSelEnd == 0) {
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            val textAfter = outputBridge.safeGetTextAfterCursor(50)

            if (CursorEditingUtils.shouldClearStateOnEmptyField(
                    newSelStart,
                    newSelEnd,
                    textBefore,
                    textAfter,
                    inputState.displayBuffer,
                    inputState.wordState.hasContent
                )
            ) {
                invalidateComposingStateOnCursorJump()
            }

            if (!inputState.isUrlOrEmailField && !inputState.isTerminalField) {
                onCheckAutoCapitalization(textBefore)
            }
        }

        if (handleAppSelectionExtended(selectionResult, newSelStart)) return

        if (handleUrlOrEmailField()) return

        if (handleNonSequentialJump(selectionResult, newSelStart, newSelEnd)) return

        val ousConsumed = inputState.tryConsumeTypingOus(newSelStart, candidatesStart, candidatesEnd)
        if (handleTypingOusConsumed(ousConsumed, newSelStart)) return

        val hasComposingText = candidatesStart != -1 && candidatesEnd != -1
        val cursorInComposingRegion =
            hasComposingText &&
                newSelStart >= candidatesStart &&
                newSelStart <= candidatesEnd &&
                newSelEnd >= candidatesStart &&
                newSelEnd <= candidatesEnd

        if (handleActivelyEditing(newSelStart)) return

        if (selectionResult.requiresStateInvalidation()) {
            if (inputState.displayBuffer.isNotEmpty() && outputBridge.reassertComposingRegion(newSelStart)) {
                inputState.lastKnownCursorPosition = newSelStart
                return
            }
            invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        if (hasComposingText && !cursorInComposingRegion) {
            invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        if (!hasComposingText && (inputState.wordState.hasContent || inputState.displayBuffer.isNotEmpty())) {
            if (inputState.displayBuffer.isNotEmpty() && outputBridge.reassertComposingRegion(newSelStart)) {
                inputState.lastKnownCursorPosition = newSelStart
                return
            }
            invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        inputState.lastKnownCursorPosition = newSelStart

        if (!hasComposingText && !inputState.isActivelyEditing && newSelStart == newSelEnd) {
            outputBridge.attemptRecompositionAtCursor(newSelStart)
        }
    }

    @VisibleForTesting
    internal fun handleDirectCommitInProgress(): Boolean = inputState.requiresDirectCommit

    @VisibleForTesting
    internal fun handleAppSelectionExtended(selectionResult: SelectionChangeResult, newSelStart: Int): Boolean {
        if (selectionResult !is SelectionChangeResult.AppSelectionExtended) return false
        if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
            inputState.clearInternalStateOnly()
            inputState.isActivelyEditing = false
        }
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    @VisibleForTesting
    internal fun handleUrlOrEmailField(): Boolean = inputState.isUrlOrEmailField

    @VisibleForTesting
    internal fun handleNonSequentialJump(
        selectionResult: SelectionChangeResult,
        newSelStart: Int,
        newSelEnd: Int
    ): Boolean {
        if (selectionResult !is SelectionChangeResult.NonSequentialJump) return false
        if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
            invalidateComposingStateOnCursorJump()
        }
        inputState.lastKnownCursorPosition = newSelStart
        if (newSelStart == newSelEnd) {
            outputBridge.attemptRecompositionAtCursor(newSelStart)
        }
        return true
    }

    @VisibleForTesting
    internal fun handleTypingOusConsumed(ousConsumed: Boolean, newSelStart: Int): Boolean {
        if (!ousConsumed) return false
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    @VisibleForTesting
    internal fun handleActivelyEditing(newSelStart: Int): Boolean {
        if (!inputState.isActivelyEditing) return false
        inputState.isActivelyEditing = false
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    private fun invalidateComposingStateOnCursorJump() = imeStateCoordinator.invalidateComposingStateOnCursorJump()
}
