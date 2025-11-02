package com.urik.keyboard.settings

import com.ibm.icu.util.ULocale
import com.urik.keyboard.R

/**
 * Visual theme options.
 */
enum class Theme(
    val displayNameRes: Int,
) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
}

/**
 * Key size scale factors.
 */
enum class KeySize(
    val displayNameRes: Int,
    val scaleFactor: Float,
) {
    SMALL(R.string.key_size_small, 0.85f),
    MEDIUM(R.string.key_size_medium, 1.0f),
    LARGE(R.string.key_size_large, 1.15f),
}

/**
 * Vibration duration levels in milliseconds.
 */
enum class VibrationStrength(
    val displayNameRes: Int,
    val durationMs: Long,
) {
    LIGHT(R.string.vibration_strength_light, 10L),
    MEDIUM(R.string.vibration_strength_medium, 20L),
    STRONG(R.string.vibration_strength_strong, 40L),
}

/**
 * Long press activation timing in milliseconds.
 */
enum class LongPressDuration(
    val displayNameRes: Int,
    val durationMs: Long,
) {
    SHORT(R.string.long_press_duration_short, 300L),
    MEDIUM(R.string.long_press_duration_medium, 500L),
    LONG(R.string.long_press_duration_long, 800L),
}

/**
 * Space bar width as key-count multiplier.
 */
enum class SpaceBarSize(
    val displayNameRes: Int,
    val widthMultiplier: Float,
) {
    COMPACT(R.string.space_bar_size_compact, 3.0f),
    STANDARD(R.string.space_bar_size_standard, 4.0f),
    WIDE(R.string.space_bar_size_wide, 5.0f),
}

/**
 * Key label size scale factors.
 */
enum class KeyLabelSize(
    val displayNameRes: Int,
    val scaleFactor: Float,
) {
    SMALL(R.string.key_label_size_small, 0.85f),
    MEDIUM(R.string.key_label_size_medium, 1.0f),
    LARGE(R.string.key_label_size_large, 1.2f),
}

/**
 * Keyboard configuration and user preferences.
 *
 * Direct construction bypasses validation. SettingsRepository enforces validation
 * at persistence boundaries via [validated]. Test code may construct directly.
 */
data class KeyboardSettings(
    val spellCheckEnabled: Boolean = true,
    val showSuggestions: Boolean = true,
    val suggestionCount: Int = 3,
    val learnNewWords: Boolean = true,
    val activeLanguages: Set<String> = setOf(DEFAULT_LANGUAGE),
    val primaryLanguage: String = DEFAULT_LANGUAGE,
    val keyClickSound: Boolean = false,
    val hapticFeedback: Boolean = true,
    val vibrationStrength: VibrationStrength = VibrationStrength.MEDIUM,
    val doubleSpacePeriod: Boolean = true,
    val longPressDuration: LongPressDuration = LongPressDuration.MEDIUM,
    val showNumberRow: Boolean = true,
    val spaceBarSize: SpaceBarSize = SpaceBarSize.STANDARD,
    val theme: Theme = Theme.SYSTEM,
    val keySize: KeySize = KeySize.MEDIUM,
    val keyLabelSize: KeyLabelSize = KeyLabelSize.MEDIUM,
) {
    /**
     * Returns validated copy with constraints enforced.
     *
     * Filters languages to supported set, clamps suggestion count, ensures primary
     * language is active. Falls back to [DEFAULT_LANGUAGE] if validation fails.
     */
    fun validated(): KeyboardSettings {
        val validActiveLanguages =
            activeLanguages
                .intersect(SUPPORTED_LANGUAGES)
                .ifEmpty { setOf(DEFAULT_LANGUAGE) }

        val validPrimaryLanguage =
            if (validActiveLanguages.contains(primaryLanguage)) {
                primaryLanguage
            } else {
                validActiveLanguages.first()
            }

        return copy(
            suggestionCount = suggestionCount.coerceIn(MIN_SUGGESTION_COUNT, MAX_SUGGESTION_COUNT),
            activeLanguages = validActiveLanguages,
            primaryLanguage = validPrimaryLanguage,
        )
    }

    /**
     * Whether word learning is enabled via [learnNewWords] flag.
     */
    val isWordLearningEnabled: Boolean
        get() = learnNewWords

    /**
     * Effective suggestion count respecting spell check and suggestion toggles.
     * Returns 0 if either is disabled.
     */
    val effectiveSuggestionCount: Int
        get() = if (showSuggestions && spellCheckEnabled) suggestionCount else 0

    /**
     * Effective vibration duration respecting haptic feedback toggle.
     * Returns 0 if haptic feedback is disabled.
     */
    val effectiveVibrationDurationMs: Long
        get() = if (hapticFeedback) vibrationStrength.durationMs else 0L

    companion object {
        const val MIN_SUGGESTION_COUNT = 1
        const val MAX_SUGGESTION_COUNT = 3

        /**
         * Default language used as fallback when user locale is unsupported.
         */
        const val DEFAULT_LANGUAGE = "en"

        /**
         * Languages with full keyboard layout, dictionary, and localization support.
         */
        val SUPPORTED_LANGUAGES = setOf("en", "es", "sv")

        /**
         * Returns localized display names for all supported languages.
         *
         * Display names are in the system's current display locale and capitalized.
         */
        fun getLanguageDisplayNames(): Map<String, String> {
            val displayLocale = ULocale.getDefault()
            return SUPPORTED_LANGUAGES.associateWith { languageCode ->
                val displayName =
                    ULocale
                        .forLanguageTag(languageCode)
                        .getDisplayName(displayLocale)

                if (displayName.isNullOrEmpty() || displayName.length <= 2) {
                    java.util.Locale
                        .forLanguageTag(languageCode)
                        .getDisplayLanguage(java.util.Locale.getDefault())
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                } else {
                    displayName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            }
        }

        /**
         * Creates settings with language defaulted to system locale if supported.
         * Falls back to [DEFAULT_LANGUAGE] for unsupported locales.
         */
        fun defaultForLocale(languageCode: String): KeyboardSettings {
            val primaryLanguage =
                if (SUPPORTED_LANGUAGES.contains(languageCode)) {
                    languageCode
                } else {
                    DEFAULT_LANGUAGE
                }

            return KeyboardSettings(
                activeLanguages = setOf(primaryLanguage),
                primaryLanguage = primaryLanguage,
            )
        }
    }
}
