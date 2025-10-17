package com.urik.keyboard.utils

import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale

/**
 * Detects Unicode script codes from locale information for text processing and layout.
 *
 * @see UScript for Unicode script code constants
 */
object ScriptDetector {
    /**
     * Detects Unicode script code from locale with language fallback.
     *
     * Detection order:
     * 1. Parse explicit script subtag from locale (e.g., "zh-Hans" → HAN)
     * 2. If missing/invalid, infer primary script from language code
     * 3. Default to LATIN if language unmapped
     *
     * Examples:
     * - "en-US" → LATIN (no script tag, English defaults)
     * - "ar-EG" → ARABIC (no script tag, Arabic defaults)
     * - "zh-Hans-CN" → HAN (explicit Simplified Chinese script)
     * - "sr-Latn-RS" → LATIN (explicit Latin Serbian script)
     * - "hi-IN" → DEVANAGARI (Hindi defaults to Devanagari)
     * - "xx-YY" → LATIN (unknown language defaults)
     *
     * @param locale ULocale to extract script from
     * @return Unicode script code (UScript constant like UScript.LATIN, UScript.ARABIC, etc.)
     */
    fun getScriptFromLocale(locale: ULocale): Int {
        val scriptString = locale.script
        if (!scriptString.isNullOrEmpty()) {
            try {
                return UScript.getCodeFromName(scriptString)
            } catch (_: Exception) {
            }
        }

        return when (locale.language) {
            "ar" -> UScript.ARABIC
            "he" -> UScript.HEBREW
            "zh" -> UScript.HAN
            "ko" -> UScript.HANGUL
            "ja" -> UScript.HIRAGANA
            "hi", "mr", "ne" -> UScript.DEVANAGARI
            "bn" -> UScript.BENGALI
            "ta" -> UScript.TAMIL
            "gu" -> UScript.GUJARATI
            "th" -> UScript.THAI
            "lo" -> UScript.LAO
            "km" -> UScript.KHMER
            "ru", "bg", "mk", "sr" -> UScript.CYRILLIC
            else -> UScript.LATIN
        }
    }

    /**
     * Checks if script requires right-to-left text direction.
     *
     * Returns true for scripts that require RTL layout:
     * - Arabic (ar, ur, fa, ps, etc.)
     * - Hebrew (he, yi)
     *
     * @param scriptCode Unicode script code from getScriptFromLocale()
     * @return true if script requires RTL rendering, false otherwise
     */
    fun isRtlScript(scriptCode: Int): Boolean =
        when (scriptCode) {
            UScript.ARABIC, UScript.HEBREW -> true
            else -> false
        }
}
