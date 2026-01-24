package com.urik.keyboard.utils

object CursorEditingUtils {
    fun isPunctuation(char: Char): Boolean {
        if (char == '\'' || char == '\u2019' || char == '-') return false

        if (char == '#' || char == '$' || char == '%' || char == '&' || char == '*') {
            return false
        }

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

    fun isNonSequentialCursorMovement(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        composingRegionStart: Int,
        composingRegionEnd: Int,
    ): Boolean {
        val hadSelection = oldSelStart != oldSelEnd
        val hasSelection = newSelStart != newSelEnd

        if (hadSelection || hasSelection) return true

        val distance = kotlin.math.abs(newSelStart - oldSelStart)

        if (distance <= 1) return false

        if (composingRegionStart != -1 && composingRegionEnd != -1) {
            val composingLength = composingRegionEnd - composingRegionStart
            val maxExpectedDistance = composingLength + 2
            if (distance <= maxExpectedDistance) return false
        }

        return distance > NON_SEQUENTIAL_THRESHOLD
    }

    fun extractWordBoundedByParagraph(
        textBeforeCursor: String,
        maxWordLength: Int = MAX_WORD_BOUNDARY_LOOKBACK,
    ): Pair<String, Int>? {
        if (textBeforeCursor.isEmpty()) return null

        val searchText =
            if (textBeforeCursor.length > maxWordLength) {
                textBeforeCursor.takeLast(maxWordLength)
            } else {
                textBeforeCursor
            }

        val paragraphBoundary = searchText.lastIndexOf('\n')

        val textInParagraph =
            if (paragraphBoundary >= 0) {
                searchText.substring(paragraphBoundary + 1)
            } else {
                searchText
            }

        if (textInParagraph.isEmpty()) return null

        val wordBoundary =
            textInParagraph.indexOfLast { char ->
                char.isWhitespace() || isPunctuation(char)
            }

        val word =
            if (wordBoundary >= 0) {
                textInParagraph.substring(wordBoundary + 1)
            } else {
                textInParagraph
            }

        if (word.isEmpty() || !isValidTextInput(word)) return null

        val absoluteBoundary =
            if (textBeforeCursor.length > maxWordLength) {
                val offset = textBeforeCursor.length - maxWordLength
                if (paragraphBoundary >= 0) {
                    offset + paragraphBoundary + 1 + (if (wordBoundary >= 0) wordBoundary else -1)
                } else {
                    offset + (if (wordBoundary >= 0) wordBoundary else -1)
                }
            } else {
                if (paragraphBoundary >= 0) {
                    paragraphBoundary + 1 + (if (wordBoundary >= 0) wordBoundary else -1)
                } else {
                    if (wordBoundary >= 0) wordBoundary else -1
                }
            }

        return Pair(word, absoluteBoundary)
    }

    fun shouldAbortRecomposition(
        expectedCursorPosition: Int,
        actualCursorPosition: Int,
        expectedComposingStart: Int,
        actualComposingStart: Int,
        tolerance: Int = RECOMPOSITION_TOLERANCE,
    ): Boolean {
        val cursorMismatch = kotlin.math.abs(expectedCursorPosition - actualCursorPosition) > tolerance
        val composingMismatch =
            expectedComposingStart != -1 &&
                actualComposingStart != -1 &&
                expectedComposingStart != actualComposingStart

        return cursorMismatch || composingMismatch
    }

    fun crossesParagraphBoundary(
        startPosition: Int,
        endPosition: Int,
        textContext: String,
    ): Boolean {
        if (startPosition < 0 || endPosition < 0) return false
        if (startPosition >= textContext.length || endPosition > textContext.length) return false

        val relevantText =
            textContext.substring(
                kotlin.math.min(startPosition, endPosition),
                kotlin.math.max(startPosition, endPosition),
            )

        return relevantText.contains('\n')
    }

    private const val NON_SEQUENTIAL_THRESHOLD = 5

    private const val MAX_WORD_BOUNDARY_LOOKBACK = 64

    private const val RECOMPOSITION_TOLERANCE = 1
}
