package com.urik.keyboard.service

import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WordNormalizer
@Inject
constructor() {
    @Volatile private var cachedLocaleTag: String = ""

    @Volatile private var cachedLocale: Locale = Locale.ENGLISH

    private fun getLocale(languageTag: String): Locale {
        if (languageTag == cachedLocaleTag) return cachedLocale
        val locale = Locale.forLanguageTag(languageTag)
        cachedLocaleTag = languageTag
        cachedLocale = locale
        return locale
    }

    fun normalize(word: String, languageTag: String): String {
        val standardNormalized = Normalizer.normalize(word.trim(), Normalizer.Form.NFC)
        val canonicalized = canonicalizeApostrophes(standardNormalized)
        return canonicalized.lowercase(getLocale(languageTag)).trim()
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
        val decomposed = Normalizer.normalize(word, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                    append(ch)
                }
            }
        }
    }
}
