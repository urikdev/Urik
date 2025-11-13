package com.urik.keyboard.utils

import com.ibm.icu.text.BreakIterator

object BackspaceUtils {
    fun getLastGraphemeClusterLength(text: String): Int {
        if (text.isEmpty()) return 0

        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)

        var previousBoundary = 0
        var currentBoundary = iterator.next()

        while (currentBoundary != BreakIterator.DONE) {
            if (currentBoundary == text.length) {
                return text.length - previousBoundary
            }
            previousBoundary = currentBoundary
            currentBoundary = iterator.next()
        }

        return text.length - previousBoundary
    }

    fun deleteGraphemeClusterBeforePosition(
        text: String,
        codeUnitPosition: Int,
    ): String {
        if (text.isEmpty() || codeUnitPosition <= 0 || codeUnitPosition > text.length) return text

        val textBeforePosition = text.substring(0, codeUnitPosition)
        val graphemeLength = getLastGraphemeClusterLength(textBeforePosition)

        val deleteStart = codeUnitPosition - graphemeLength
        val deleteEnd = codeUnitPosition

        return text.substring(0, deleteStart) + text.substring(deleteEnd)
    }

    fun extractWordBeforeCursor(textBeforeCursor: String): Pair<String, Int>? {
        if (textBeforeCursor.isEmpty()) return null

        val lastWordBoundary =
            textBeforeCursor.indexOfLast { char ->
                char.isWhitespace() || char == '\n' || CursorEditingUtils.isPunctuation(char)
            }

        val wordBeforeCursor =
            if (lastWordBoundary >= 0) {
                textBeforeCursor.substring(lastWordBoundary + 1)
            } else {
                textBeforeCursor
            }

        if (wordBeforeCursor.isEmpty() || !CursorEditingUtils.isValidTextInput(wordBeforeCursor)) {
            return null
        }

        return Pair(wordBeforeCursor, lastWordBoundary)
    }

    fun shouldDeleteTrailingSpace(
        textBeforeCursor: String,
        wordLength: Int,
    ): Boolean {
        if (textBeforeCursor.length <= wordLength) return false

        val charBeforeWord = textBeforeCursor[textBeforeCursor.length - wordLength - 1]
        return charBeforeWord.isWhitespace()
    }

    fun calculateDeleteLength(
        wordLength: Int,
        shouldDeleteSpace: Boolean,
    ): Int = if (shouldDeleteSpace) wordLength + 1 else wordLength
}
