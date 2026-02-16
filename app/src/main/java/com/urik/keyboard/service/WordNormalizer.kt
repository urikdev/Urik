package com.urik.keyboard.service

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.ULocale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WordNormalizer
    @Inject
    constructor() {
        private val nfcNormalizer = Normalizer2.getNFCInstance()
        private val nfdNormalizer = Normalizer2.getNFDInstance()

        fun normalize(
            word: String,
            languageTag: String,
        ): String {
            val standardNormalized = nfcNormalizer.normalize(word.trim())
            val locale =
                try {
                    ULocale.forLanguageTag(languageTag)
                } catch (_: Exception) {
                    ULocale.ENGLISH
                }
            return UCharacter
                .toLowerCase(locale, standardNormalized)
                .trim()
        }

        fun stripDiacritics(word: String): String {
            val decomposed = nfdNormalizer.normalize(word)
            return buildString(decomposed.length) {
                for (ch in decomposed) {
                    if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                        append(ch)
                    }
                }
            }
        }
    }
