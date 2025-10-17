@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.settings

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests KeyboardSettings validation, computed properties, and locale defaults.
 */
class KeyboardSettingsTest {
    @Test
    fun `validated filters out unsupported languages`() {
        val settings =
            KeyboardSettings(
                activeLanguages = setOf("en", "sv"),
                primaryLanguage = "en",
            )

        val validated = settings.validated()

        assertEquals(setOf("en", "sv"), validated.activeLanguages)
    }

    @Test
    fun `validated defaults to english when all languages unsupported`() {
        val settings =
            KeyboardSettings(
                activeLanguages = setOf("fr", "de", "es"),
                primaryLanguage = "fr",
            )

        val validated = settings.validated()

        assertEquals(setOf("en"), validated.activeLanguages)
        assertEquals("en", validated.primaryLanguage)
    }

    @Test
    fun `validated fixes primary language not in active set`() {
        val settings =
            KeyboardSettings(
                activeLanguages = setOf("en", "sv"),
                primaryLanguage = "fr",
            )

        val validated = settings.validated()

        assertTrue(validated.activeLanguages.contains(validated.primaryLanguage))
    }

    @Test
    fun `validated preserves valid primary language`() {
        val settings =
            KeyboardSettings(
                activeLanguages = setOf("en", "sv"),
                primaryLanguage = "sv",
            )

        val validated = settings.validated()

        assertEquals("sv", validated.primaryLanguage)
        assertEquals(setOf("en", "sv"), validated.activeLanguages)
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
    fun `effectiveSuggestionCount returns zero when spell check disabled`() {
        val settings =
            KeyboardSettings(
                spellCheckEnabled = false,
                showSuggestions = true,
                suggestionCount = 3,
            )

        assertEquals(0, settings.effectiveSuggestionCount)
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
    fun `effectiveVibrationDurationMs returns duration when enabled`() {
        val settings =
            KeyboardSettings(
                hapticFeedback = true,
                vibrationStrength = VibrationStrength.STRONG,
            )

        assertEquals(40L, settings.effectiveVibrationDurationMs)
    }

    @Test
    fun `effectiveVibrationDurationMs returns zero when disabled`() {
        val settings =
            KeyboardSettings(
                hapticFeedback = false,
                vibrationStrength = VibrationStrength.STRONG,
            )

        assertEquals(0L, settings.effectiveVibrationDurationMs)
    }

    @Test
    fun `effectiveSoundVolume returns volume when enabled`() {
        val settings =
            KeyboardSettings(
                keyClickSound = true,
                soundVolume = SoundVolume.HIGH,
            )

        assertEquals(1.0f, settings.effectiveSoundVolume, 0.01f)
    }

    @Test
    fun `effectiveSoundVolume returns zero when disabled`() {
        val settings =
            KeyboardSettings(
                keyClickSound = false,
                soundVolume = SoundVolume.HIGH,
            )

        assertEquals(0.0f, settings.effectiveSoundVolume, 0.01f)
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

        assertEquals(setOf("sv"), settings.activeLanguages)
        assertEquals("sv", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale falls back to english for unsupported`() {
        val settings = KeyboardSettings.defaultForLocale("fr")

        assertEquals(setOf("en"), settings.activeLanguages)
        assertEquals("en", settings.primaryLanguage)
    }

    @Test
    fun `defaultForLocale handles empty string`() {
        val settings = KeyboardSettings.defaultForLocale("")

        assertEquals(setOf("en"), settings.activeLanguages)
        assertEquals("en", settings.primaryLanguage)
    }

    @Test
    fun `validated handles empty active languages`() {
        val settings =
            KeyboardSettings(
                activeLanguages = emptySet(),
                primaryLanguage = "",
            )

        val validated = settings.validated()

        assertEquals(setOf("en"), validated.activeLanguages)
        assertEquals("en", validated.primaryLanguage)
    }

    @Test
    fun `validated is idempotent`() {
        val settings =
            KeyboardSettings(
                activeLanguages = setOf("en", "fr"),
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
                activeLanguages = setOf("en", "sv", "fr", "de"),
                primaryLanguage = "de",
                suggestionCount = -1,
            )

        val result1 = settings.validated()
        val result2 = settings.validated()

        assertEquals(result1.activeLanguages, result2.activeLanguages)
        assertEquals(result1.primaryLanguage, result2.primaryLanguage)
        assertEquals(result1.suggestionCount, result2.suggestionCount)
    }
}
