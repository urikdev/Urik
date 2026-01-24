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
        private val normalizer = Normalizer2.getNFCInstance()

        fun normalize(
            word: String,
            languageTag: String,
        ): String {
            val standardNormalized = normalizer.normalize(word.trim())
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
    }
