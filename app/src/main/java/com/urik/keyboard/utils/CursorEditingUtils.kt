package com.urik.keyboard.utils

import com.urik.keyboard.KeyboardConstants.TextProcessingConstants

object CursorEditingUtils {
    private fun isUrlContent(text: String): Boolean {
        if (text.contains("://")) return true
        if (text.startsWith("www.")) return true
        if (text.contains("@") && text.contains(".") && !text.contains(" ")) return true
        if (text.contains("/") && text.contains(".") && !text.contains(" ")) return true
        return false
    }

    fun isPunctuation(char: Char): Boolean {
        if (char == '\'' || char == '\u2019' || char == '-') return false

        val type = Character.getType(char.code)
        return type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }

    fun extractWordAtCursor(
        textBefore: String,
        textAfter: String,
    ): String? {
        if (textBefore.isEmpty() && textAfter.isEmpty()) return null

        val limitedBefore = textBefore.takeLast(TextProcessingConstants.CURSOR_CONTEXT_BEFORE_LENGTH)
        val limitedAfter = textAfter.take(TextProcessingConstants.CURSOR_CONTEXT_AFTER_LENGTH)

        val beforeLastBoundary =
            limitedBefore.indexOfLast { char ->
                char.isWhitespace() || char == '\n'
            }

        val afterFirstBoundary =
            limitedAfter.indexOfFirst { char ->
                char.isWhitespace() || char == '\n'
            }

        val tokenBefore =
            if (beforeLastBoundary >= 0) {
                limitedBefore.substring(beforeLastBoundary + 1)
            } else {
                limitedBefore
            }

        val tokenAfter =
            if (afterFirstBoundary >= 0) {
                limitedAfter.take(afterFirstBoundary)
            } else {
                limitedAfter
            }

        val fullToken = tokenBefore + tokenAfter

        if (fullToken.length >= TextProcessingConstants.MAX_TOKEN_LENGTH_BEFORE_FILTER) return null

        if (isUrlContent(fullToken)) return null

        val trimmedWord = fullToken.trim { isPunctuation(it) }

        if (trimmedWord.length >= TextProcessingConstants.MAX_WORD_LENGTH_AFTER_TRIM) return null

        return if (trimmedWord.isNotEmpty() && isValidTextInput(trimmedWord)) {
            trimmedWord
        } else {
            null
        }
    }

    fun isValidTextInput(text: String): Boolean {
        if (text.isBlank()) return false

        return text.any { char ->
            Character.isLetter(char.code) ||
                Character.isIdeographic(char.code) ||
                Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                char == '\'' ||
                char == '\u2019' ||
                char == '-'
        }
    }

    fun shouldClearStateOnEmptyField(
        newSelStart: Int,
        newSelEnd: Int,
        textBefore: String?,
        textAfter: String?,
        displayBuffer: String,
        hasWordStateContent: Boolean,
    ): Boolean {
        if (newSelStart != 0 || newSelEnd != 0) return false

        return textBefore.isNullOrEmpty() &&
            textAfter.isNullOrEmpty() &&
            (displayBuffer.isNotEmpty() || hasWordStateContent)
    }

    fun calculateCursorPositionInWord(
        absoluteCursorPos: Int,
        composingRegionStart: Int,
        displayBufferLength: Int,
    ): Int {
        if (composingRegionStart == -1) return displayBufferLength
        return (absoluteCursorPos - composingRegionStart).coerceIn(0, displayBufferLength)
    }

    fun recalculateComposingRegionStart(
        currentTextLength: Int,
        displayBufferLength: Int,
    ): Int = currentTextLength - displayBufferLength
}
