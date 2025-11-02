package com.urik.keyboard.utils

import com.urik.keyboard.utils.CursorEditingUtils

object BackspaceUtils {
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
