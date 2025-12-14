package com.urik.keyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keyboard_settings",
)

/**
 * Manages keyboard settings persistence and learned word data.
 *
 * Provides reactive Flow-based settings access and atomic update operations.
 * All settings changes are validated before persistence.
 */
@Singleton
class SettingsRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val database: KeyboardDatabase,
        private val cacheMemoryManager: CacheMemoryManager,
    ) {
        private val dataStore = context.settingsDataStore

        private object PreferenceKeys {
            val SHOW_SUGGESTIONS = booleanPreferencesKey("show_suggestions")
            val SPELL_CHECK_ENABLED = booleanPreferencesKey("spell_check_enabled")
            val SUGGESTION_COUNT = intPreferencesKey("suggestion_count")
            val LEARN_NEW_WORDS = booleanPreferencesKey("learn_new_words")
            val CLIPBOARD_ENABLED = booleanPreferencesKey("clipboard_enabled")
            val CLIPBOARD_CONSENT_SHOWN = booleanPreferencesKey("clipboard_consent_shown")
            val ACTIVE_LANGUAGES = stringSetPreferencesKey("active_languages")
            val PRIMARY_LANGUAGE = stringPreferencesKey("primary_language")
            val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
            val VIBRATION_STRENGTH = stringPreferencesKey("vibration_strength")
            val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
            val SWIPE_ENABLED = booleanPreferencesKey("swipe_enabled")
            val SPACEBAR_CURSOR_CONTROL = booleanPreferencesKey("spacebar_cursor_control")
            val BACKSPACE_SWIPE_DELETE = booleanPreferencesKey("backspace_swipe_delete")
            val SPACEBAR_LONG_PRESS_PUNCTUATION = booleanPreferencesKey("spacebar_long_press_punctuation")
            val LONG_PRESS_DURATION = stringPreferencesKey("long_press_duration")
            val SHOW_NUMBER_ROW = booleanPreferencesKey("show_number_row")
            val SPACE_BAR_SIZE = stringPreferencesKey("space_bar_size")
            val KEYBOARD_THEME = stringPreferencesKey("keyboard_theme")
            val KEY_SIZE = stringPreferencesKey("key_size")
            val KEY_LABEL_SIZE = stringPreferencesKey("key_label_size")
            val FAVORITE_THEMES = stringSetPreferencesKey("favorite_themes")
        }

        /**
         * Reactive stream of keyboard settings.
         *
         * Emits validated settings on subscription and whenever preferences change.
         * Falls back to system locale defaults on deserialization errors.
         */
        val settings: Flow<KeyboardSettings> =
            dataStore.data
                .map { preferences ->
                    KeyboardSettings(
                        spellCheckEnabled = preferences[PreferenceKeys.SPELL_CHECK_ENABLED] ?: true,
                        showSuggestions = preferences[PreferenceKeys.SHOW_SUGGESTIONS] ?: true,
                        suggestionCount = preferences[PreferenceKeys.SUGGESTION_COUNT] ?: 3,
                        learnNewWords = preferences[PreferenceKeys.LEARN_NEW_WORDS] ?: true,
                        clipboardEnabled = preferences[PreferenceKeys.CLIPBOARD_ENABLED] ?: true,
                        clipboardConsentShown = preferences[PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] ?: false,
                        activeLanguages = preferences[PreferenceKeys.ACTIVE_LANGUAGES] ?: setOf(KeyboardSettings.DEFAULT_LANGUAGE),
                        primaryLanguage = preferences[PreferenceKeys.PRIMARY_LANGUAGE] ?: KeyboardSettings.DEFAULT_LANGUAGE,
                        hapticFeedback = preferences[PreferenceKeys.HAPTIC_FEEDBACK] ?: true,
                        vibrationStrength =
                            preferences[PreferenceKeys.VIBRATION_STRENGTH]?.let {
                                try {
                                    VibrationStrength.valueOf(it)
                                } catch (_: IllegalArgumentException) {
                                    VibrationStrength.MEDIUM
                                }
                            } ?: VibrationStrength.MEDIUM,
                        doubleSpacePeriod = preferences[PreferenceKeys.DOUBLE_SPACE_PERIOD] ?: true,
                        swipeEnabled = preferences[PreferenceKeys.SWIPE_ENABLED] ?: true,
                        spacebarCursorControl = preferences[PreferenceKeys.SPACEBAR_CURSOR_CONTROL] ?: true,
                        backspaceSwipeDelete = preferences[PreferenceKeys.BACKSPACE_SWIPE_DELETE] ?: true,
                        spacebarLongPressPunctuation = preferences[PreferenceKeys.SPACEBAR_LONG_PRESS_PUNCTUATION] ?: true,
                        longPressDuration =
                            preferences[PreferenceKeys.LONG_PRESS_DURATION]?.let {
                                try {
                                    LongPressDuration.valueOf(it)
                                } catch (_: IllegalArgumentException) {
                                    LongPressDuration.MEDIUM
                                }
                            } ?: LongPressDuration.MEDIUM,
                        showNumberRow = preferences[PreferenceKeys.SHOW_NUMBER_ROW] ?: true,
                        spaceBarSize =
                            preferences[PreferenceKeys.SPACE_BAR_SIZE]?.let {
                                try {
                                    SpaceBarSize.valueOf(it)
                                } catch (_: IllegalArgumentException) {
                                    SpaceBarSize.STANDARD
                                }
                            } ?: SpaceBarSize.STANDARD,
                        keySize =
                            preferences[PreferenceKeys.KEY_SIZE]?.let {
                                try {
                                    KeySize.valueOf(it)
                                } catch (_: IllegalArgumentException) {
                                    KeySize.MEDIUM
                                }
                            } ?: KeySize.MEDIUM,
                        keyLabelSize =
                            preferences[PreferenceKeys.KEY_LABEL_SIZE]?.let {
                                try {
                                    KeyLabelSize.valueOf(it)
                                } catch (_: IllegalArgumentException) {
                                    KeyLabelSize.MEDIUM
                                }
                            } ?: KeyLabelSize.MEDIUM,
                        keyboardTheme = preferences[PreferenceKeys.KEYBOARD_THEME] ?: "default",
                        favoriteThemes = preferences[PreferenceKeys.FAVORITE_THEMES] ?: emptySet(),
                    ).validated()
                }.catch { _ ->
                    emit(getDefaultSettings())
                }

        /**
         * Updates spell check toggle.
         */
        suspend fun updateSpellCheckEnabled(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.SPELL_CHECK_ENABLED] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates suggestion visibility.
         */
        suspend fun updateShowSuggestions(show: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.SHOW_SUGGESTIONS] = show }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates suggestion count with automatic clamping to valid range.
         */
        suspend fun updateSuggestionCount(count: Int): Result<Unit> =
            try {
                dataStore.edit {
                    it[PreferenceKeys.SUGGESTION_COUNT] =
                        count.coerceIn(
                            KeyboardSettings.MIN_SUGGESTION_COUNT,
                            KeyboardSettings.MAX_SUGGESTION_COUNT,
                        )
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates word learning toggle.
         */
        suspend fun updateLearnNewWords(learn: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.LEARN_NEW_WORDS] = learn }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates clipboard history toggle.
         */
        suspend fun updateClipboardEnabled(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.CLIPBOARD_ENABLED] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Marks clipboard consent message as shown.
         */
        suspend fun updateClipboardConsentShown(shown: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] = shown }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates active languages and primary language with validation.
         *
         * Filters to supported languages only. Primary must be in active set.
         * Falls back to default language if validation fails.
         */
        suspend fun updateLanguageSettings(
            activeLanguages: Set<String>,
            primaryLanguage: String,
        ): Result<Unit> =
            try {
                val validatedActiveLanguages =
                    activeLanguages
                        .intersect(
                            KeyboardSettings.SUPPORTED_LANGUAGES,
                        ).ifEmpty { setOf(KeyboardSettings.DEFAULT_LANGUAGE) }

                val validatedPrimaryLanguage =
                    if (validatedActiveLanguages.contains(primaryLanguage)) {
                        primaryLanguage
                    } else {
                        validatedActiveLanguages.first()
                    }

                dataStore.edit { preferences ->
                    preferences[PreferenceKeys.ACTIVE_LANGUAGES] = validatedActiveLanguages
                    preferences[PreferenceKeys.PRIMARY_LANGUAGE] = validatedPrimaryLanguage
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates haptic feedback toggle.
         */
        suspend fun updateHapticFeedback(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.HAPTIC_FEEDBACK] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates vibration strength level.
         */
        suspend fun updateVibrationStrength(strength: VibrationStrength): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.VIBRATION_STRENGTH] = strength.name }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates double-space-period shortcut toggle.
         */
        suspend fun updateDoubleSpacePeriod(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.DOUBLE_SPACE_PERIOD] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates swipe typing toggle.
         */
        suspend fun updateSwipeEnabled(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.SWIPE_ENABLED] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates spacebar cursor control toggle.
         */
        suspend fun updateSpacebarCursorControl(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.SPACEBAR_CURSOR_CONTROL] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates backspace swipe delete toggle.
         */
        suspend fun updateBackspaceSwipeDelete(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.BACKSPACE_SWIPE_DELETE] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates spacebar long press punctuation toggle.
         */
        suspend fun updateSpacebarLongPressPunctuation(enabled: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.SPACEBAR_LONG_PRESS_PUNCTUATION] = enabled }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates long press duration timing.
         */
        suspend fun updateLongPressDuration(duration: LongPressDuration): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.LONG_PRESS_DURATION] = duration.name }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates number row visibility.
         */
        suspend fun updateShowNumberRow(show: Boolean): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.SHOW_NUMBER_ROW] = show }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates space bar width multiplier.
         */
        suspend fun updateSpaceBarSize(size: SpaceBarSize): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.SPACE_BAR_SIZE] = size.name }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates key size scale factor.
         */
        suspend fun updateKeySize(size: KeySize): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.KEY_SIZE] = size.name }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates key label size scale factor.
         */
        suspend fun updateKeyLabelSize(size: KeyLabelSize): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.KEY_LABEL_SIZE] = size.name }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Updates keyboard theme by ID.
         */
        suspend fun updateKeyboardTheme(themeId: String): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.KEYBOARD_THEME] = themeId }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        suspend fun updateFavoriteThemes(favorites: Set<String>): Result<Unit> =
            try {
                dataStore.edit { it[PreferenceKeys.FAVORITE_THEMES] = favorites }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Clears all learned words from database and invalidates caches.
         *
         * This operation is irreversible. Clears learned words for all supported languages
         * within a single transaction to ensure atomicity.
         */
        suspend fun clearLearnedWords(): Result<Unit> =
            try {
                database.withTransaction {
                    val supportedLanguages = KeyboardSettings.SUPPORTED_LANGUAGES
                    supportedLanguages.forEach { languageTag ->
                        database.learnedWordDao().clearLanguage(languageTag)
                    }
                }
                cacheMemoryManager.forceCleanup()
                Result.success(Unit)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SettingsRepository",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("operation" to "clearLearnedWords"),
                )
                Result.failure(e)
            }

        /**
         * Resets all preferences to default values.
         *
         * Does not affect learned words. Use [clearLearnedWords] to remove learned vocabulary.
         */
        suspend fun resetToDefaults(): Result<Unit> =
            try {
                dataStore.edit { it.clear() }
                Result.success(Unit)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SettingsRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "resetToDefaults"),
                )
                Result.failure(e)
            }

        private fun getDefaultSettings(): KeyboardSettings {
            val systemLanguage = Locale.getDefault().language
            return KeyboardSettings.defaultForLocale(systemLanguage)
        }
    }
