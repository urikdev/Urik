package com.urik.keyboard.utils

object CursorEditingUtils {
    fun extractWordAtCursor(
        textBefore: String,
        textAfter: String,
    ): String? {
        if (textBefore.isEmpty() && textAfter.isEmpty()) return null

        val limitedBefore = textBefore.takeLast(20)
        val limitedAfter = textAfter.take(20)

        val beforeLastBoundary =
            limitedBefore.indexOfLast { char ->
                char.isWhitespace() || char in ".,!?;:\n"
            }

        val afterFirstBoundary =
            limitedAfter.indexOfFirst { char ->
                char.isWhitespace() || char in ".,!?;:\n"
            }

        val wordBefore =
            if (beforeLastBoundary >= 0) {
                limitedBefore.substring(beforeLastBoundary + 1)
            } else {
                limitedBefore
            }

        val wordAfter =
            if (afterFirstBoundary >= 0) {
                limitedAfter.substring(0, afterFirstBoundary)
            } else {
                limitedAfter
            }

        val fullWord = wordBefore + wordAfter

        if (fullWord.length >= 40) return null

        return if (fullWord.isNotEmpty() && isValidTextInput(fullWord)) {
            fullWord
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
