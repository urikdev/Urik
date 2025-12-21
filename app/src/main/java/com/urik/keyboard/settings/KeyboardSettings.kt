package com.urik.keyboard.settings

import com.ibm.icu.util.ULocale
import com.urik.keyboard.R
import com.urik.keyboard.settings.KeyboardSettings.Companion.DEFAULT_LANGUAGE

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
    EXTRA_LARGE(R.string.key_size_extra_large, 1.35f),
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
    val clipboardEnabled: Boolean = true,
    val clipboardConsentShown: Boolean = false,
    val activeLanguages: List<String> = listOf(DEFAULT_LANGUAGE),
    val primaryLanguage: String = DEFAULT_LANGUAGE,
    val primaryLayoutLanguage: String = DEFAULT_LANGUAGE,
    val hapticFeedback: Boolean = true,
    val vibrationStrength: Int = 128,
    val doubleSpacePeriod: Boolean = true,
    val swipeEnabled: Boolean = true,
    val spacebarCursorControl: Boolean = true,
    val backspaceSwipeDelete: Boolean = true,
    val spacebarLongPressPunctuation: Boolean = true,
    val longPressDuration: LongPressDuration = LongPressDuration.MEDIUM,
    val showNumberRow: Boolean = true,
    val spaceBarSize: SpaceBarSize = SpaceBarSize.STANDARD,
    val keySize: KeySize = KeySize.MEDIUM,
    val keyLabelSize: KeyLabelSize = KeyLabelSize.MEDIUM,
    val keyboardTheme: String = "default",
    val favoriteThemes: Set<String> = emptySet(),
) {
    /**
     * Returns validated copy with constraints enforced.
     *
     * Filters languages to supported set, limits to max 3, clamps suggestion count,
     * ensures primary language and layout language are active. Falls back to
     * [DEFAULT_LANGUAGE] if validation fails.
     */
    fun validated(): KeyboardSettings {
        val validActiveLanguages =
            activeLanguages
                .filter { it in SUPPORTED_LANGUAGES }
                .distinct()
                .take(MAX_ACTIVE_LANGUAGES)
                .ifEmpty { listOf(DEFAULT_LANGUAGE) }

        val validPrimaryLanguage =
            if (validActiveLanguages.contains(primaryLanguage)) {
                primaryLanguage
            } else {
                validActiveLanguages.first()
            }

        val validPrimaryLayoutLanguage =
            if (validActiveLanguages.contains(primaryLayoutLanguage)) {
                primaryLayoutLanguage
            } else {
                validActiveLanguages.first()
            }

        return copy(
            suggestionCount = suggestionCount.coerceIn(MIN_SUGGESTION_COUNT, MAX_SUGGESTION_COUNT),
            activeLanguages = validActiveLanguages,
            primaryLanguage = validPrimaryLanguage,
            primaryLayoutLanguage = validPrimaryLayoutLanguage,
        )
    }

    /**
     * Whether word learning is enabled via [learnNewWords] flag.
     */
    val isWordLearningEnabled: Boolean
        get() = learnNewWords

    /**
     * Effective suggestion count respecting suggestion toggle.
     * Returns 0 if suggestions are disabled.
     */
    val effectiveSuggestionCount: Int
        get() = if (showSuggestions) suggestionCount else 0

    /**
     * Effective vibration amplitude respecting haptic feedback toggle.
     * Returns 0 if haptic feedback is disabled.
     */
    val effectiveVibrationAmplitude: Int
        get() = if (hapticFeedback) vibrationStrength else 0

    companion object {
        const val MIN_SUGGESTION_COUNT = 1
        const val MAX_SUGGESTION_COUNT = 3
        const val MAX_ACTIVE_LANGUAGES = 3

        /**
         * Default language used as fallback when user locale is unsupported.
         */
        const val DEFAULT_LANGUAGE = "en"

        /**
         * Languages with full keyboard layout, dictionary, and localization support.
         */
        val SUPPORTED_LANGUAGES = setOf("cs", "de", "en", "es", "fa", "it", "pl", "pt", "ru", "sv", "uk")

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
            val lang = if (languageCode in SUPPORTED_LANGUAGES) languageCode else DEFAULT_LANGUAGE

            return KeyboardSettings(
                activeLanguages = listOf(lang),
                primaryLanguage = lang,
                primaryLayoutLanguage = lang,
            )
        }
    }
}
