@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests KeyboardSettings validation, computed properties, and locale defaults.
 */
class KeyboardSettingsTest {
    @Test
    fun `validated filters out unsupported languages`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "sv"),
                primaryLanguage = "en",
            )

        val validated = settings.validated()

        assertEquals(listOf("en", "sv"), validated.activeLanguages)
    }

    @Test
    fun `validated preserves german as supported language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("de", "en", "fr"),
                primaryLanguage = "de",
            )

        val validated = settings.validated()

        assertEquals(listOf("de", "en"), validated.activeLanguages)
        assertEquals("de", validated.primaryLanguage)
    }

    @Test
    fun `validated preserves czech as supported language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("cs", "en", "fr"),
                primaryLanguage = "cs",
            )

        val validated = settings.validated()

        assertEquals(listOf("cs", "en"), validated.activeLanguages)
        assertEquals("cs", validated.primaryLanguage)
    }

    @Test
    fun `validated preserves portuguese as supported language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("pt", "en", "fr"),
                primaryLanguage = "pt",
            )

        val validated = settings.validated()

        assertEquals(listOf("pt", "en"), validated.activeLanguages)
        assertEquals("pt", validated.primaryLanguage)
    }

    @Test
    fun `validated defaults to english when all languages unsupported`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("fr", "ja", "it"),
                primaryLanguage = "fr",
            )

        val validated = settings.validated()

        assertEquals(listOf("en"), validated.activeLanguages)
        assertEquals("en", validated.primaryLanguage)
    }

    @Test
    fun `validated fixes primary language not in active set`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "sv"),
                primaryLanguage = "fr",
            )

        val validated = settings.validated()

        assertTrue(validated.activeLanguages.contains(validated.primaryLanguage))
    }

    @Test
    fun `validated preserves valid primary language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "sv"),
                primaryLanguage = "sv",
            )

        val validated = settings.validated()

        assertEquals("sv", validated.primaryLanguage)
        assertEquals(listOf("en", "sv"), validated.activeLanguages)
    }

    @Test
    fun `validated coerces suggestion count to min`() {
        val settings = KeyboardSettings(suggestionCount = 0)

        val validated = settings.validated()

        assertEquals(KeyboardSettings.MIN_SUGGESTION_COUNT, validated.suggestionCount)
    }

    @Test
    fun `validated coerces suggestion count to max`() {
        val settings = KeyboardSettings(suggestionCount = 10)

        val validated = settings.validated()

        assertEquals(KeyboardSettings.MAX_SUGGESTION_COUNT, validated.suggestionCount)
    }

    @Test
    fun `validated preserves valid suggestion count`() {
        val settings = KeyboardSettings(suggestionCount = 2)

        val validated = settings.validated()

        assertEquals(2, validated.suggestionCount)
    }

    @Test
    fun `validated handles negative suggestion count`() {
        val settings = KeyboardSettings(suggestionCount = -5)

        val validated = settings.validated()

        assertEquals(KeyboardSettings.MIN_SUGGESTION_COUNT, validated.suggestionCount)
    }

    @Test
    fun `effectiveSuggestionCount returns count when enabled`() {
        val settings =
            KeyboardSettings(
                spellCheckEnabled = true,
                showSuggestions = true,
                suggestionCount = 3,
            )

        assertEquals(3, settings.effectiveSuggestionCount)
    }

    @Test
    fun `effectiveSuggestionCount returns count when spell check disabled but suggestions enabled`() {
        val settings =
            KeyboardSettings(
                spellCheckEnabled = false,
                showSuggestions = true,
                suggestionCount = 3,
            )

        assertEquals(3, settings.effectiveSuggestionCount)
    }

    @Test
    fun `effectiveSuggestionCount returns zero when suggestions disabled`() {
        val settings =
            KeyboardSettings(
                spellCheckEnabled = true,
                showSuggestions = false,
                suggestionCount = 3,
            )

        assertEquals(0, settings.effectiveSuggestionCount)
    }

    @Test
    fun `effectiveSuggestionCount returns zero when both disabled`() {
        val settings =
            KeyboardSettings(
                spellCheckEnabled = false,
                showSuggestions = false,
                suggestionCount = 3,
            )

        assertEquals(0, settings.effectiveSuggestionCount)
    }

    @Test
    fun `effectiveVibrationAmplitude returns amplitude when enabled`() {
        val settings =
            KeyboardSettings(
                hapticFeedback = true,
                vibrationStrength = 255,
            )

        assertEquals(255, settings.effectiveVibrationAmplitude)
    }

    @Test
    fun `effectiveVibrationAmplitude returns zero when disabled`() {
        val settings =
            KeyboardSettings(
                hapticFeedback = false,
                vibrationStrength = 255,
            )

        assertEquals(0, settings.effectiveVibrationAmplitude)
    }

    @Test
    fun `isWordLearningEnabled reflects learnNewWords`() {
        val enabled = KeyboardSettings(learnNewWords = true)
        val disabled = KeyboardSettings(learnNewWords = false)

        assertTrue(enabled.isWordLearningEnabled)
        assertFalse(disabled.isWordLearningEnabled)
    }

    @Test
    fun `defaultForLocale uses supported language`() {
        val settings = KeyboardSettings.defaultForLocale("sv")

        assertEquals(listOf("sv"), settings.activeLanguages)
        assertEquals("sv", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale supports german`() {
        val settings = KeyboardSettings.defaultForLocale("de")

        assertEquals(listOf("de"), settings.activeLanguages)
        assertEquals("de", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale supports czech`() {
        val settings = KeyboardSettings.defaultForLocale("cs")

        assertEquals(listOf("cs"), settings.activeLanguages)
        assertEquals("cs", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale supports portuguese`() {
        val settings = KeyboardSettings.defaultForLocale("pt")

        assertEquals(listOf("pt"), settings.activeLanguages)
        assertEquals("pt", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale supports russian`() {
        val settings = KeyboardSettings.defaultForLocale("ru")

        assertEquals(listOf("ru"), settings.activeLanguages)
        assertEquals("ru", settings.primaryLanguage)
    }

    @Test
    fun `validated preserves russian as supported language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("ru", "en", "fr"),
                primaryLanguage = "ru",
            )

        val validated = settings.validated()

        assertEquals(listOf("ru", "en"), validated.activeLanguages)
        assertEquals("ru", validated.primaryLanguage)
    }

    @Test
    fun `defaultForLocale supports ukrainian`() {
        val settings = KeyboardSettings.defaultForLocale("uk")

        assertEquals(listOf("uk"), settings.activeLanguages)
        assertEquals("uk", settings.primaryLanguage)
    }

    @Test
    fun `validated preserves ukrainian as supported language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("uk", "en", "fr"),
                primaryLanguage = "uk",
            )

        val validated = settings.validated()

        assertEquals(listOf("uk", "en"), validated.activeLanguages)
        assertEquals("uk", validated.primaryLanguage)
    }

    @Test
    fun `defaultForLocale supports polish`() {
        val settings = KeyboardSettings.defaultForLocale("pl")

        assertEquals(listOf("pl"), settings.activeLanguages)
        assertEquals("pl", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale supports farsi`() {
        val settings = KeyboardSettings.defaultForLocale("fa")

        assertEquals(listOf("fa"), settings.activeLanguages)
        assertEquals("fa", settings.primaryLanguage)
    }

    @Test
    fun `validated preserves farsi as supported language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("fa", "en", "fr"),
                primaryLanguage = "fa",
            )

        val validated = settings.validated()

        assertEquals(listOf("fa", "en"), validated.activeLanguages)
        assertEquals("fa", validated.primaryLanguage)
    }

    @Test
    fun `validated preserves polish as supported language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("pl", "en", "fr"),
                primaryLanguage = "pl",
            )

        val validated = settings.validated()

        assertEquals(listOf("pl", "en"), validated.activeLanguages)
        assertEquals("pl", validated.primaryLanguage)
    }

    @Test
    fun `defaultForLocale falls back to english for unsupported`() {
        val settings = KeyboardSettings.defaultForLocale("fr")

        assertEquals(listOf("en"), settings.activeLanguages)
        assertEquals("en", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale handles empty string`() {
        val settings = KeyboardSettings.defaultForLocale("")

        assertEquals(listOf("en"), settings.activeLanguages)
        assertEquals("en", settings.primaryLanguage)
    }

    @Test
    fun `validated handles empty active languages`() {
        val settings =
            KeyboardSettings(
                activeLanguages = emptyList(),
                primaryLanguage = "",
            )

        val validated = settings.validated()

        assertEquals(listOf("en"), validated.activeLanguages)
        assertEquals("en", validated.primaryLanguage)
    }

    @Test
    fun `validated is idempotent`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "fr"),
                primaryLanguage = "fr",
                suggestionCount = 10,
            )

        val validated1 = settings.validated()
        val validated2 = validated1.validated()

        assertEquals(validated1, validated2)
    }

    @Test
    fun `multiple validation calls produce same result`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "sv", "fr", "ja"),
                primaryLanguage = "ja",
                suggestionCount = -1,
            )

        val result1 = settings.validated()
        val result2 = settings.validated()

        assertEquals(result1.activeLanguages, result2.activeLanguages)
        assertEquals(result1.primaryLanguage, result2.primaryLanguage)
        assertEquals(result1.suggestionCount, result2.suggestionCount)
    }

    @Test
    fun `validated preserves favoriteThemes`() {
        val favorites = setOf("ocean", "crimson", "lavender")
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "fr"),
                primaryLanguage = "fr",
                favoriteThemes = favorites,
            )

        val validated = settings.validated()

        assertEquals(favorites, validated.favoriteThemes)
    }

    @Test
    fun `favoriteThemes defaults to empty set`() {
        val settings = KeyboardSettings()

        assertEquals(emptySet<String>(), settings.favoriteThemes)
    }

    @Test
    fun `validated enforces max 3 active languages`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "es", "de", "fr"),
                primaryLanguage = "en",
            )

        val validated = settings.validated()

        assertEquals(3, validated.activeLanguages.size)
        assertEquals(listOf("en", "es", "de"), validated.activeLanguages)
    }

    @Test
    fun `validated ensures primaryLayoutLanguage is in active languages`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("en", "es"),
                primaryLanguage = "en",
                primaryLayoutLanguage = "de",
            )

        val validated = settings.validated()

        assertTrue(validated.activeLanguages.contains(validated.primaryLayoutLanguage))
        assertEquals("en", validated.primaryLayoutLanguage)
    }

    @Test
    fun `validated preserves order of active languages`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("es", "en", "de"),
                primaryLanguage = "es",
            )

        val validated = settings.validated()

        assertEquals(listOf("es", "en", "de"), validated.activeLanguages)
    }

    @Test
    fun `validated with all unsupported languages falls back to english`() {
        val settings =
            KeyboardSettings(
                activeLanguages = listOf("fr", "it", "ja"),
                primaryLanguage = "fr",
                primaryLayoutLanguage = "it",
            )

        val validated = settings.validated()

        assertEquals(listOf("en"), validated.activeLanguages)
        assertEquals("en", validated.primaryLanguage)
        assertEquals("en", validated.primaryLayoutLanguage)
    }
}
