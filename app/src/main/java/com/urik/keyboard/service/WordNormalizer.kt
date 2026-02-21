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
            val canonicalized = canonicalizeApostrophes(standardNormalized)
            val locale =
                try {
                    ULocale.forLanguageTag(languageTag)
                } catch (_: Exception) {
                    ULocale.ENGLISH
                }
            return UCharacter
                .toLowerCase(locale, canonicalized)
                .trim()
        }

        fun canonicalizeApostrophes(text: String): String {
            if (text.isEmpty()) return text
            var hasVariant = false
            for (ch in text) {
                val code = ch.code
                if (code == 0x2019 || code == 0x201B || code == 0x02BC || code == 0x2032) {
                    hasVariant = true
                    break
                }
            }
            if (!hasVariant) return text
            return buildString(text.length) {
                for (ch in text) {
                    val code = ch.code
                    if (code == 0x2019 || code == 0x201B || code == 0x02BC || code == 0x2032) {
                        append('\'')
                    } else {
                        append(ch)
                    }
                }
            }
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
