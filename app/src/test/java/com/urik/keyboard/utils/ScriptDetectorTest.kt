package com.urik.keyboard.utils

import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests ScriptDetector locale-to-script mapping and RTL detection.
 */
@RunWith(RobolectricTestRunner::class)
class ScriptDetectorTest {
    @Test
    fun `explicit script in locale takes precedence`() {
        val latinScript = ULocale.forLanguageTag("en-Latn")
        val arabicScript = ULocale.forLanguageTag("ar-Arab")
        val cyrillicScript = ULocale.forLanguageTag("ru-Cyrl")

        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(latinScript))
        assertEquals(UScript.ARABIC, ScriptDetector.getScriptFromLocale(arabicScript))
        assertEquals(UScript.CYRILLIC, ScriptDetector.getScriptFromLocale(cyrillicScript))
    }

    @Test
    fun `explicit script overrides language fallback`() {
        val cyrillicSerbian = ULocale.forLanguageTag("sr-Cyrl")
        val latinSerbian = ULocale.forLanguageTag("sr-Latn")

        assertEquals(UScript.CYRILLIC, ScriptDetector.getScriptFromLocale(cyrillicSerbian))
        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(latinSerbian))
    }

    @Test
    fun `RTL languages use correct scripts`() {
        val arabic = ULocale.forLanguageTag("ar")
        val hebrew = ULocale.forLanguageTag("he")

        assertEquals(UScript.ARABIC, ScriptDetector.getScriptFromLocale(arabic))
        assertEquals(UScript.HEBREW, ScriptDetector.getScriptFromLocale(hebrew))
    }

    @Test
    fun `East Asian languages use correct scripts`() {
        val chinese = ULocale.forLanguageTag("zh")
        val korean = ULocale.forLanguageTag("ko")
        val japanese = ULocale.forLanguageTag("ja")

        assertEquals(UScript.HAN, ScriptDetector.getScriptFromLocale(chinese))
        assertEquals(UScript.HANGUL, ScriptDetector.getScriptFromLocale(korean))
        assertEquals(UScript.HIRAGANA, ScriptDetector.getScriptFromLocale(japanese))
    }

    @Test
    fun `Indic languages use correct scripts`() {
        val hindi = ULocale.forLanguageTag("hi")
        val tamil = ULocale.forLanguageTag("ta")
        val bengali = ULocale.forLanguageTag("bn")

        assertEquals(UScript.DEVANAGARI, ScriptDetector.getScriptFromLocale(hindi))
        assertEquals(UScript.TAMIL, ScriptDetector.getScriptFromLocale(tamil))
        assertEquals(UScript.BENGALI, ScriptDetector.getScriptFromLocale(bengali))
    }

    @Test
    fun `Southeast Asian languages use correct scripts`() {
        val thai = ULocale.forLanguageTag("th")
        val lao = ULocale.forLanguageTag("lo")
        val khmer = ULocale.forLanguageTag("km")

        assertEquals(UScript.THAI, ScriptDetector.getScriptFromLocale(thai))
        assertEquals(UScript.LAO, ScriptDetector.getScriptFromLocale(lao))
        assertEquals(UScript.KHMER, ScriptDetector.getScriptFromLocale(khmer))
    }

    @Test
    fun `Cyrillic languages use Cyrillic script`() {
        val russian = ULocale.forLanguageTag("ru")
        val bulgarian = ULocale.forLanguageTag("bg")
        val serbian = ULocale.forLanguageTag("sr")

        assertEquals(UScript.CYRILLIC, ScriptDetector.getScriptFromLocale(russian))
        assertEquals(UScript.CYRILLIC, ScriptDetector.getScriptFromLocale(bulgarian))
        assertEquals(UScript.CYRILLIC, ScriptDetector.getScriptFromLocale(serbian))
    }

    @Test
    fun `Latin languages use Latin script`() {
        val english = ULocale.forLanguageTag("en")
        val swedish = ULocale.forLanguageTag("sv")
        val french = ULocale.forLanguageTag("fr")

        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(english))
        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(swedish))
        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(french))
    }

    @Test
    fun `unknown language defaults to Latin`() {
        val unknown = ULocale.forLanguageTag("xyz")

        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(unknown))
    }

    @Test
    fun `invalid script name falls back to language`() {
        val locale = ULocale.forLanguageTag("en")

        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(locale))
    }

    @Test
    fun `empty script uses language fallback`() {
        val locale = ULocale.forLanguageTag("ar")

        assertEquals(UScript.ARABIC, ScriptDetector.getScriptFromLocale(locale))
    }

    @Test
    fun `Arabic and Hebrew are RTL`() {
        assertTrue(ScriptDetector.isRtlScript(UScript.ARABIC))
        assertTrue(ScriptDetector.isRtlScript(UScript.HEBREW))
    }

    @Test
    fun `other scripts are not RTL`() {
        assertFalse(ScriptDetector.isRtlScript(UScript.LATIN))
        assertFalse(ScriptDetector.isRtlScript(UScript.HAN))
        assertFalse(ScriptDetector.isRtlScript(UScript.CYRILLIC))
        assertFalse(ScriptDetector.isRtlScript(UScript.DEVANAGARI))
    }

    @Test
    fun `English locale works correctly`() {
        val usEnglish = ULocale.forLanguageTag("en-US")

        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(usEnglish))
        assertFalse(ScriptDetector.isRtlScript(UScript.LATIN))
    }

    @Test
    fun `Swedish locale works correctly`() {
        val swedish = ULocale.forLanguageTag("sv-SE")

        assertEquals(UScript.LATIN, ScriptDetector.getScriptFromLocale(swedish))
        assertFalse(ScriptDetector.isRtlScript(UScript.LATIN))
    }

    @Test
    fun `Chinese locales use Han script`() {
        val chineseNoScript = ULocale.forLanguageTag("zh")
        val chineseWithRegion = ULocale.forLanguageTag("zh-CN")

        assertEquals(UScript.HAN, ScriptDetector.getScriptFromLocale(chineseNoScript))
        assertEquals(UScript.HAN, ScriptDetector.getScriptFromLocale(chineseWithRegion))
    }

    @Test
    fun `multiple Devanagari languages map correctly`() {
        val hindi = ULocale.forLanguageTag("hi")
        val marathi = ULocale.forLanguageTag("mr")
        val nepali = ULocale.forLanguageTag("ne")

        assertEquals(UScript.DEVANAGARI, ScriptDetector.getScriptFromLocale(hindi))
        assertEquals(UScript.DEVANAGARI, ScriptDetector.getScriptFromLocale(marathi))
        assertEquals(UScript.DEVANAGARI, ScriptDetector.getScriptFromLocale(nepali))
    }

    @Test
    fun `locale with region code still works`() {
        val saudiArabic = ULocale.forLanguageTag("ar-SA")
        val israeliHebrew = ULocale.forLanguageTag("he-IL")
        val chinaSimplified = ULocale.forLanguageTag("zh-CN")

        assertEquals(UScript.ARABIC, ScriptDetector.getScriptFromLocale(saudiArabic))
        assertEquals(UScript.HEBREW, ScriptDetector.getScriptFromLocale(israeliHebrew))
        assertEquals(UScript.HAN, ScriptDetector.getScriptFromLocale(chinaSimplified))
    }
}
