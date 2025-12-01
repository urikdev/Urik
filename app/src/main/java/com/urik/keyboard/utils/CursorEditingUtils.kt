package com.urik.keyboard.utils

object CursorEditingUtils {
    fun isPunctuation(char: Char): Boolean {
        if (char == '\'' || char == '\u2019' || char == '-') return false

        val type = Character.getType(char.code)
        return type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
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
