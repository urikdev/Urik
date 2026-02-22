package com.urik.keyboard.utils

import com.ibm.icu.text.BreakIterator
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.SpellingSuggestion
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaseTransformer
    @Inject
    constructor() {
        private val breakIteratorCache =
            ThreadLocal.withInitial {
                BreakIterator.getCharacterInstance()
            }

        fun applyCasing(
            suggestion: SpellingSuggestion,
            keyboardState: KeyboardState,
            isSentenceStart: Boolean = false,
            locale: java.util.Locale = java.util.Locale.ROOT,
        ): String {
            if (keyboardState.isCapsLockOn) {
                return suggestion.word.uppercase(locale)
            }

            if (keyboardState.isShiftPressed && !keyboardState.isAutoShift) {
                return capitalizeFirstLetter(suggestion.word, locale)
            }

            if (keyboardState.isShiftPressed) {
                return if (suggestion.preserveCase) {
                    suggestion.word
                } else {
                    capitalizeFirstLetter(suggestion.word, locale)
                }
            }

            if (isSentenceStart) {
                return if (suggestion.preserveCase) {
                    suggestion.word
                } else {
                    capitalizeFirstLetter(suggestion.word, locale)
                }
            }

            return suggestion.word
        }

        fun applyCasingToSuggestions(
            suggestions: List<SpellingSuggestion>,
            keyboardState: KeyboardState,
            isSentenceStart: Boolean = false,
            locale: java.util.Locale = java.util.Locale.ROOT,
        ): List<String> = suggestions.map { applyCasing(it, keyboardState, isSentenceStart, locale) }

        private fun capitalizeFirstLetter(word: String, locale: java.util.Locale = java.util.Locale.ROOT): String {
            if (word.isEmpty()) return word

            val firstChar = word[0]
            if (isAsciiLetter(firstChar) && isAsciiOnly(word)) {
                return firstChar.uppercaseChar() + word.substring(1)
            }

            val iterator = breakIteratorCache.get()
            iterator.setText(word)
            val firstGraphemeEnd = iterator.next()

            if (firstGraphemeEnd == BreakIterator.DONE) return word

            val firstGrapheme = word.take(firstGraphemeEnd)
            val rest = word.substring(firstGraphemeEnd)

            return firstGrapheme.uppercase(locale) + rest
        }

        private fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

        private fun isAsciiOnly(text: String): Boolean {
            for (i in text.indices) {
                if (text[i].code > 127) return false
            }
            return true
        }
    }
