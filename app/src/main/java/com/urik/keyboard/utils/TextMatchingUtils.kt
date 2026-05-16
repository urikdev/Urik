package com.urik.keyboard.utils

object TextMatchingUtils {
    fun isWordSeparatingPunctuation(char: Char): Boolean {
        val type = Character.getType(char.code)
        return when (type) {
            Character.DASH_PUNCTUATION.toInt() -> true
            Character.START_PUNCTUATION.toInt() -> false
            Character.END_PUNCTUATION.toInt() -> false
            Character.CONNECTOR_PUNCTUATION.toInt() -> false
            Character.OTHER_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.MODIFIER_LETTER.toInt()
            -> {
                when (char.code) {
                    0x0027, 0x02BC, 0x055A, 0x05F3, 0x2019, 0x201B, 0x2032 -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    fun isValidWordPunctuation(char: Char): Boolean {
        val type = Character.getType(char.code)
        return when (type) {
            Character.DASH_PUNCTUATION.toInt() -> true
            Character.OTHER_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.MODIFIER_LETTER.toInt()
            -> {
                when (char.code) {
                    0x0027, 0x02BC, 0x055A, 0x05F3, 0x2019, 0x201B, 0x2032 -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    fun stripWordPunctuation(word: String): String {
        if (word.isEmpty()) return word
        val hasPunctuation = word.any { isWordSeparatingPunctuation(it) }
        if (!hasPunctuation) return word
        return word.filter { !isWordSeparatingPunctuation(it) }
    }

    /**
     * Checks if candidate is a contraction suggestion for input.
     *
     * Returns true only for "add punctuation" direction:
     * - Input has no punctuation (e.g., "dont")
     * - Candidate has punctuation (e.g., "don't")
     * - Stripped forms match
     *
     * @param input User-typed word (normalized)
     * @param candidate Dictionary/learned word to suggest
     * @return true if candidate is valid contraction suggestion
     */
    fun isContractionSuggestion(input: String, candidate: String): Boolean {
        if (input.isEmpty() || candidate.isEmpty()) return false

        val strippedInput = stripWordPunctuation(input)
        val strippedCandidate = stripWordPunctuation(candidate)

        val apostropheIndex = candidate.indexOfFirst { isWordSeparatingPunctuation(it) }
        val apostropheNotAtEnd = apostropheIndex > 0 && apostropheIndex < candidate.length - 1

        return apostropheNotAtEnd &&
            strippedInput.equals(strippedCandidate, ignoreCase = true) &&
            candidate != strippedCandidate &&
            input == strippedInput
    }
}
