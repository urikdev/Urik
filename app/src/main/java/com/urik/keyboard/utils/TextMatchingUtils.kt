package com.urik.keyboard.utils

/**
 * Utilities for text matching with punctuation handling.
 *
 */
object TextMatchingUtils {
    /**
     * Checks if character is word-separating punctuation (apostrophes, hyphens).
     *
     * @return true if character should be stripped for matching purposes
     */
    fun isWordSeparatingPunctuation(char: Char): Boolean {
        val type = Character.getType(char.code)
        return when (type) {
            Character.DASH_PUNCTUATION.toInt() -> true
            Character.START_PUNCTUATION.toInt() -> false
            Character.END_PUNCTUATION.toInt() -> false
            Character.CONNECTOR_PUNCTUATION.toInt() -> false
            Character.OTHER_PUNCTUATION.toInt() -> {
                when (char.code) {
                    0x0027, 0x02BC, 0x055A, 0x05F3, 0x2019, 0x201B, 0x2032 -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    /**
     * Checks if character is valid punctuation within a word for learning/storage.
     *
     * @return true if character is valid within a learned word
     */
    fun isValidWordPunctuation(char: Char): Boolean {
        val type = Character.getType(char.code)
        return when (type) {
            Character.DASH_PUNCTUATION.toInt() -> true
            Character.OTHER_PUNCTUATION.toInt() -> {
                when (char.code) {
                    0x0027, 0x02BC, 0x055A, 0x05F3, 0x2019, 0x201B, 0x2032 -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    /**
     * Strips word-separating punctuation for matching purposes.
     *
     * @param word Original word with punctuation
     * @return Word with apostrophes and hyphens removed
     */
    fun stripWordPunctuation(word: String): String {
        if (word.isEmpty()) return word
        return word.filter { !isWordSeparatingPunctuation(it) }
    }
}
