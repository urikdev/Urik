package com.urik.keyboard.utils

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class SelectionState(
    val selectionStart: Int,
    val selectionEnd: Int,
    val composingStart: Int,
    val composingEnd: Int,
    val sequence: Long,
) {
    val hasSelection: Boolean get() = selectionStart != selectionEnd

    val hasComposingRegion: Boolean get() = composingStart != -1 && composingEnd != -1

    companion object {
        val INVALID =
            SelectionState(
                selectionStart = -1,
                selectionEnd = -1,
                composingStart = -1,
                composingEnd = -1,
                sequence = -1L,
            )
    }
}

class SelectionStateTracker {
    private val currentState = AtomicReference(SelectionState.INVALID)
    private val sequenceCounter = AtomicLong(0)

    private var lastKnownValidPosition: Int = 0
    private var lastParagraphBoundary: Int = -1

    @Volatile
    private var pendingOperationExpectedPosition: Int = -1

    fun updateSelection(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): SelectionChangeResult {
        val previousState = currentState.get()
        val newSequence = sequenceCounter.incrementAndGet()

        val newState =
            SelectionState(
                selectionStart = newSelStart,
                selectionEnd = newSelEnd,
                composingStart = candidatesStart,
                composingEnd = candidatesEnd,
                sequence = newSequence,
            )

        currentState.set(newState)

        if (previousState == SelectionState.INVALID) {
            lastKnownValidPosition = newSelStart
            return SelectionChangeResult.Initial
        }

        val previousCursor = if (previousState.hasSelection) -1 else previousState.selectionStart
        val newCursor = if (newState.hasSelection) -1 else newState.selectionStart

        if (previousCursor == -1 || newCursor == -1) {
            if (isAppSelectionExtension(previousState, newState)) {
                lastKnownValidPosition = newSelStart
                return SelectionChangeResult.AppSelectionExtended(
                    anchorPosition = newSelStart,
                    selectionEnd = newSelEnd,
                )
            }
            lastKnownValidPosition = newSelStart
            return SelectionChangeResult.SelectionChanged
        }

        val distance = kotlin.math.abs(newCursor - previousCursor)

        if (isNonSequentialJump(previousCursor, newCursor, previousState.composingStart, previousState.composingEnd)) {
            val result =
                SelectionChangeResult.NonSequentialJump(
                    previousPosition = previousCursor,
                    newPosition = newCursor,
                    distance = distance,
                )
            lastKnownValidPosition = newCursor
            return result
        }

        if (previousState.hasComposingRegion && !newState.hasComposingRegion) {
            lastKnownValidPosition = newCursor
            return SelectionChangeResult.ComposingRegionLost
        }

        if (previousState.hasComposingRegion &&
            (newCursor < previousState.composingStart || newCursor > previousState.composingEnd)
        ) {
            lastKnownValidPosition = newCursor
            return SelectionChangeResult.CursorLeftComposingRegion(
                composingStart = previousState.composingStart,
                composingEnd = previousState.composingEnd,
            )
        }

        lastKnownValidPosition = newCursor
        return SelectionChangeResult.Sequential
    }

    private fun isNonSequentialJump(
        previousCursor: Int,
        newCursor: Int,
        composingStart: Int,
        composingEnd: Int,
    ): Boolean {
        val distance = kotlin.math.abs(newCursor - previousCursor)

        if (distance <= 1) return false

        if (composingStart != -1 && composingEnd != -1) {
            val composingLength = composingEnd - composingStart
            val maxExpectedDistance = composingLength + 2
            if (distance <= maxExpectedDistance) return false
        }

        if (pendingOperationExpectedPosition != -1) {
            val expectedDistance = kotlin.math.abs(newCursor - pendingOperationExpectedPosition)
            if (expectedDistance <= 1) {
                pendingOperationExpectedPosition = -1
                return false
            }
        }

        return distance > JUMP_THRESHOLD
    }

    private fun isAppSelectionExtension(
        previousState: SelectionState,
        newState: SelectionState,
    ): Boolean {
        if (previousState.hasSelection) return false

        val previousCursor = previousState.selectionStart
        val newStart = newState.selectionStart
        val newEnd = newState.selectionEnd

        if (newStart == newEnd) return false
        if (newStart != previousCursor) return false
        if (newEnd <= newStart) return false

        return !newState.hasComposingRegion
    }

    fun getCurrentState(): SelectionState = currentState.get()

    fun getCurrentSequence(): Long = sequenceCounter.get()

    fun validateOperationPosition(
        expectedStart: Int,
        expectedEnd: Int,
        tolerance: Int = 0,
    ): Boolean {
        val state = currentState.get()
        if (state == SelectionState.INVALID) return false

        val actualStart = state.selectionStart
        val actualEnd = state.selectionEnd

        return kotlin.math.abs(actualStart - expectedStart) <= tolerance &&
            kotlin.math.abs(actualEnd - expectedEnd) <= tolerance
    }

    fun validateComposingRegionIntegrity(
        expectedComposingStart: Int,
        expectedComposingEnd: Int,
    ): Boolean {
        val state = currentState.get()

        if (expectedComposingStart == -1 || expectedComposingEnd == -1) {
            return !state.hasComposingRegion
        }

        return state.composingStart == expectedComposingStart &&
            state.composingEnd == expectedComposingEnd
    }

    fun setExpectedPositionAfterOperation(expectedPosition: Int) {
        pendingOperationExpectedPosition = expectedPosition
    }

    fun clearExpectedPosition() {
        pendingOperationExpectedPosition = -1
    }

    fun reset() {
        currentState.set(SelectionState.INVALID)
        lastKnownValidPosition = 0
        lastParagraphBoundary = -1
        pendingOperationExpectedPosition = -1
    }

    fun isCursorAtParagraphBoundary(textBeforeCursor: String): Boolean {
        if (textBeforeCursor.isEmpty()) return true
        return textBeforeCursor.lastOrNull() == '\n'
    }

    fun findParagraphBoundaryBefore(
        textBeforeCursor: String,
        maxLookback: Int = MAX_PARAGRAPH_LOOKBACK,
    ): Int {
        if (textBeforeCursor.isEmpty()) return 0

        val searchText =
            if (textBeforeCursor.length > maxLookback) {
                textBeforeCursor.takeLast(maxLookback)
            } else {
                textBeforeCursor
            }

        val newlineIndex = searchText.lastIndexOf('\n')

        return if (newlineIndex >= 0) {
            if (textBeforeCursor.length > maxLookback) {
                textBeforeCursor.length - maxLookback + newlineIndex + 1
            } else {
                newlineIndex + 1
            }
        } else {
            0
        }
    }

    fun getLastKnownValidPosition(): Int = lastKnownValidPosition

    companion object {
        const val JUMP_THRESHOLD = 5

        const val MAX_PARAGRAPH_LOOKBACK = 256
    }
}

sealed class SelectionChangeResult {
    data object Initial : SelectionChangeResult()

    data object Sequential : SelectionChangeResult()

    data object SelectionChanged : SelectionChangeResult()

    data object ComposingRegionLost : SelectionChangeResult()

    data class CursorLeftComposingRegion(
        val composingStart: Int,
        val composingEnd: Int,
    ) : SelectionChangeResult()

    data class NonSequentialJump(
        val previousPosition: Int,
        val newPosition: Int,
        val distance: Int,
    ) : SelectionChangeResult()

    data class AppSelectionExtended(
        val anchorPosition: Int,
        val selectionEnd: Int,
    ) : SelectionChangeResult()

    fun requiresStateInvalidation(): Boolean =
        when (this) {
            is NonSequentialJump -> true
            is ComposingRegionLost -> true
            is CursorLeftComposingRegion -> true
            else -> false
        }
}
