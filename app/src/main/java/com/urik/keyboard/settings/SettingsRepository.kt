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
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keyboard_settings"
)

/** All settings changes are validated before persistence. */
@Singleton
class SettingsRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: KeyboardDatabase,
    private val cacheMemoryManager: CacheMemoryManager,
    private val wordFrequencyRepository: com.urik.keyboard.data.WordFrequencyRepository
) {
    private val dataStore = context.settingsDataStore

    private object PreferenceKeys {
        val SHOW_SUGGESTIONS = booleanPreferencesKey("show_suggestions")
        val SPELL_CHECK_ENABLED = booleanPreferencesKey("spell_check_enabled")
        val SUGGESTION_COUNT = intPreferencesKey("suggestion_count")
        val LEARN_NEW_WORDS = booleanPreferencesKey("learn_new_words")
        val CLIPBOARD_ENABLED = booleanPreferencesKey("clipboard_enabled")
        val CLIPBOARD_CONSENT_SHOWN = booleanPreferencesKey("clipboard_consent_shown")
        val ACTIVE_LANGUAGES_LIST = stringPreferencesKey("active_languages_list")
        val PRIMARY_LANGUAGE = stringPreferencesKey("primary_language")
        val PRIMARY_LAYOUT_LANGUAGE = stringPreferencesKey("primary_layout_language")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val VIBRATION_STRENGTH = intPreferencesKey("vibration_strength")
        val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        val AUTO_CAPITALIZATION_ENABLED = booleanPreferencesKey("auto_capitalization_enabled")
        val SWIPE_ENABLED = booleanPreferencesKey("swipe_enabled")
        val SPACEBAR_CURSOR_CONTROL = booleanPreferencesKey("spacebar_cursor_control")
        val BACKSPACE_SWIPE_DELETE = booleanPreferencesKey("backspace_swipe_delete")
        val LONG_PRESS_PUNCTUATION_MODE = stringPreferencesKey("long_press_punctuation_mode")
        val LONG_PRESS_DURATION = stringPreferencesKey("long_press_duration")
        val SHOW_NUMBER_ROW = booleanPreferencesKey("show_number_row")
        val SPACE_BAR_SIZE = stringPreferencesKey("space_bar_size")
        val KEYBOARD_THEME = stringPreferencesKey("keyboard_theme")
        val KEY_SIZE = stringPreferencesKey("key_size")
        val KEY_LABEL_SIZE = stringPreferencesKey("key_label_size")
        val CURSOR_SPEED = stringPreferencesKey("cursor_speed")
        val FAVORITE_THEMES = stringSetPreferencesKey("favorite_themes")
        val ALTERNATIVE_KEYBOARD_LAYOUT = stringPreferencesKey("alternative_keyboard_layout")
        val ADAPTIVE_KEYBOARD_MODES_ENABLED = booleanPreferencesKey("adaptive_keyboard_modes_enabled")
        val KEYBOARD_DISPLAY_MODE = stringPreferencesKey("keyboard_display_mode")
        val ONE_HANDED_MODE_ENABLED = booleanPreferencesKey("one_handed_mode_enabled")
        val SHOW_LANGUAGE_SWITCH_KEY = booleanPreferencesKey("show_language_switch_key")
        val MERGED_DICTIONARIES = booleanPreferencesKey("merged_dictionaries")
        val PAUSE_ON_MISSPELLED_WORD = booleanPreferencesKey("pause_on_misspelled_word")
        val AUTOCORRECTION_ENABLED = booleanPreferencesKey("autocorrection_enabled")
        val SHOW_NUMBER_HINTS = booleanPreferencesKey("show_number_hints")
        val RESET_TO_LETTERS_ON_DISMISS = booleanPreferencesKey("reset_to_letters_on_dismiss")
        val PRESS_HIGHLIGHT_ENABLED = booleanPreferencesKey("press_highlight_enabled")
    }

    /** Falls back to system locale defaults on deserialization errors. */
    val settings: Flow<KeyboardSettings> =
        dataStore.data
            .map { preferences ->
                val activeLanguages =
                    preferences[PreferenceKeys.ACTIVE_LANGUAGES_LIST]?.let {
                        if (it.isNotEmpty()) it.split(",").map { lang -> lang.trim() } else null
                    } ?: listOf(preferences[PreferenceKeys.PRIMARY_LANGUAGE] ?: KeyboardSettings.DEFAULT_LANGUAGE)

                val primaryLanguage =
                    preferences[PreferenceKeys.PRIMARY_LANGUAGE] ?: KeyboardSettings.DEFAULT_LANGUAGE

                val primaryLayoutLanguage =
                    preferences[PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE]
                        ?: primaryLanguage

                KeyboardSettings(
                    spellCheckEnabled = preferences[PreferenceKeys.SPELL_CHECK_ENABLED] ?: true,
                    showSuggestions = preferences[PreferenceKeys.SHOW_SUGGESTIONS] ?: true,
                    suggestionCount = preferences[PreferenceKeys.SUGGESTION_COUNT] ?: 3,
                    learnNewWords = preferences[PreferenceKeys.LEARN_NEW_WORDS] ?: true,
                    clipboardEnabled = preferences[PreferenceKeys.CLIPBOARD_ENABLED] ?: true,
                    clipboardConsentShown = preferences[PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] ?: false,
                    activeLanguages = activeLanguages,
                    primaryLanguage = primaryLanguage,
                    primaryLayoutLanguage = primaryLayoutLanguage,
                    hapticFeedback = preferences[PreferenceKeys.HAPTIC_FEEDBACK] ?: true,
                    vibrationStrength = preferences[PreferenceKeys.VIBRATION_STRENGTH] ?: 128,
                    doubleSpacePeriod = preferences[PreferenceKeys.DOUBLE_SPACE_PERIOD] ?: true,
                    autoCapitalizationEnabled = preferences[PreferenceKeys.AUTO_CAPITALIZATION_ENABLED] ?: true,
                    swipeEnabled = preferences[PreferenceKeys.SWIPE_ENABLED] ?: true,
                    spacebarCursorControl = preferences[PreferenceKeys.SPACEBAR_CURSOR_CONTROL] ?: true,
                    backspaceSwipeDelete = preferences[PreferenceKeys.BACKSPACE_SWIPE_DELETE] ?: true,
                    longPressPunctuationMode =
                    preferences[PreferenceKeys.LONG_PRESS_PUNCTUATION_MODE]?.let {
                        try {
                            LongPressPunctuationMode.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "LONG_PRESS_PUNCTUATION_MODE", "value" to it)
                            )
                            LongPressPunctuationMode.PERIOD
                        }
                    } ?: LongPressPunctuationMode.PERIOD,
                    longPressDuration =
                    preferences[PreferenceKeys.LONG_PRESS_DURATION]?.let {
                        try {
                            LongPressDuration.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "LONG_PRESS_DURATION", "value" to it)
                            )
                            LongPressDuration.MEDIUM
                        }
                    } ?: LongPressDuration.MEDIUM,
                    showNumberRow = preferences[PreferenceKeys.SHOW_NUMBER_ROW] ?: true,
                    spaceBarSize =
                    preferences[PreferenceKeys.SPACE_BAR_SIZE]?.let {
                        try {
                            SpaceBarSize.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "SPACE_BAR_SIZE", "value" to it)
                            )
                            SpaceBarSize.STANDARD
                        }
                    } ?: SpaceBarSize.STANDARD,
                    keySize =
                    preferences[PreferenceKeys.KEY_SIZE]?.let {
                        try {
                            KeySize.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "KEY_SIZE", "value" to it)
                            )
                            KeySize.MEDIUM
                        }
                    } ?: KeySize.MEDIUM,
                    keyLabelSize =
                    preferences[PreferenceKeys.KEY_LABEL_SIZE]?.let {
                        try {
                            KeyLabelSize.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "KEY_LABEL_SIZE", "value" to it)
                            )
                            KeyLabelSize.MEDIUM
                        }
                    } ?: KeyLabelSize.MEDIUM,
                    cursorSpeed =
                    preferences[PreferenceKeys.CURSOR_SPEED]?.let {
                        try {
                            CursorSpeed.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "CURSOR_SPEED", "value" to it)
                            )
                            CursorSpeed.MEDIUM
                        }
                    } ?: CursorSpeed.MEDIUM,
                    keyboardTheme = preferences[PreferenceKeys.KEYBOARD_THEME] ?: "default",
                    favoriteThemes = preferences[PreferenceKeys.FAVORITE_THEMES] ?: emptySet(),
                    alternativeKeyboardLayout =
                    preferences[PreferenceKeys.ALTERNATIVE_KEYBOARD_LAYOUT]?.let {
                        try {
                            AlternativeKeyboardLayout.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "ALTERNATIVE_KEYBOARD_LAYOUT", "value" to it)
                            )
                            AlternativeKeyboardLayout.DEFAULT
                        }
                    } ?: AlternativeKeyboardLayout.DEFAULT,
                    adaptiveKeyboardModesEnabled =
                    preferences[PreferenceKeys.ADAPTIVE_KEYBOARD_MODES_ENABLED] ?: true,
                    keyboardDisplayMode =
                    preferences[PreferenceKeys.KEYBOARD_DISPLAY_MODE]?.let {
                        try {
                            KeyboardDisplayMode.valueOf(it)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    },
                    oneHandedModeEnabled = preferences[PreferenceKeys.ONE_HANDED_MODE_ENABLED] ?: false,
                    showLanguageSwitchKey = preferences[PreferenceKeys.SHOW_LANGUAGE_SWITCH_KEY] ?: false,
                    mergedDictionaries = preferences[PreferenceKeys.MERGED_DICTIONARIES] ?: true,
                    pauseOnMisspelledWord = preferences[PreferenceKeys.PAUSE_ON_MISSPELLED_WORD] ?: true,
                    autocorrectionEnabled = preferences[PreferenceKeys.AUTOCORRECTION_ENABLED] ?: false,
                    showNumberHints = preferences[PreferenceKeys.SHOW_NUMBER_HINTS] ?: false,
                    resetToLettersOnDismiss = preferences[PreferenceKeys.RESET_TO_LETTERS_ON_DISMISS] ?: true,
                    keyPressHighlightEnabled = preferences[PreferenceKeys.PRESS_HIGHLIGHT_ENABLED] ?: true
                ).validated()
            }.catch { e ->
                ErrorLogger.logException(
                    component = "SettingsRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "loadSettings")
                )
                emit(getDefaultSettings())
            }

    suspend fun updateSpellCheckEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SPELL_CHECK_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowSuggestions(show: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_SUGGESTIONS] = show }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSuggestionCount(count: Int): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.SUGGESTION_COUNT] =
                count.coerceIn(
                    KeyboardSettings.MIN_SUGGESTION_COUNT,
                    KeyboardSettings.MAX_SUGGESTION_COUNT
                )
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateLearnNewWords(learn: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.LEARN_NEW_WORDS] = learn }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateClipboardEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.CLIPBOARD_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateClipboardConsentShown(shown: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] = shown }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Filters to supported languages only, limits to max 3, maintains order.
     * Primary layout language must be in active set. Falls back to default if validation fails.
     */
    suspend fun updateActiveLanguages(activeLanguages: List<String>, primaryLayoutLanguage: String): Result<Unit> =
        try {
            val validatedActiveLanguages =
                activeLanguages
                    .filter { it in KeyboardSettings.SUPPORTED_LANGUAGES }
                    .distinct()
                    .take(KeyboardSettings.MAX_ACTIVE_LANGUAGES)
                    .ifEmpty { listOf(KeyboardSettings.DEFAULT_LANGUAGE) }

            val validatedPrimaryLayoutLanguage =
                if (validatedActiveLanguages.contains(primaryLayoutLanguage)) {
                    primaryLayoutLanguage
                } else {
                    validatedActiveLanguages.first()
                }

            dataStore.edit { preferences ->
                preferences[PreferenceKeys.ACTIVE_LANGUAGES_LIST] = validatedActiveLanguages.joinToString(",")
                preferences[PreferenceKeys.PRIMARY_LANGUAGE] = validatedPrimaryLayoutLanguage
                preferences[PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE] = validatedPrimaryLayoutLanguage
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Switches primary layout language without changing active languages set.
     * Layout language must be in active languages. Falls back to first active if invalid.
     */
    suspend fun updatePrimaryLayoutLanguage(primaryLayoutLanguage: String): Result<Unit> = try {
        dataStore.edit { preferences ->
            val activeLanguagesStr = preferences[PreferenceKeys.ACTIVE_LANGUAGES_LIST]
            val activeLanguages =
                activeLanguagesStr?.split(",")?.map { it.trim() }
                    ?: listOf(KeyboardSettings.DEFAULT_LANGUAGE)

            val validatedPrimaryLayoutLanguage =
                if (activeLanguages.contains(primaryLayoutLanguage)) {
                    primaryLayoutLanguage
                } else {
                    activeLanguages.first()
                }

            preferences[PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE] = validatedPrimaryLayoutLanguage
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateHapticFeedback(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.HAPTIC_FEEDBACK] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVibrationStrength(strength: Int): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.VIBRATION_STRENGTH] = strength.coerceIn(1, 255)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateDoubleSpacePeriod(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.DOUBLE_SPACE_PERIOD] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAutoCapitalizationEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.AUTO_CAPITALIZATION_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSwipeEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SWIPE_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSpacebarCursorControl(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SPACEBAR_CURSOR_CONTROL] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateCursorSpeed(speed: CursorSpeed): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.CURSOR_SPEED] = speed.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateBackspaceSwipeDelete(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.BACKSPACE_SWIPE_DELETE] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateLongPressPunctuationMode(mode: LongPressPunctuationMode): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.LONG_PRESS_PUNCTUATION_MODE] = mode.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateLongPressDuration(duration: LongPressDuration): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.LONG_PRESS_DURATION] = duration.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowNumberRow(show: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_NUMBER_ROW] = show }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSpaceBarSize(size: SpaceBarSize): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SPACE_BAR_SIZE] = size.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeySize(size: KeySize): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.KEY_SIZE] = size.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyLabelSize(size: KeyLabelSize): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.KEY_LABEL_SIZE] = size.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyboardTheme(themeId: String): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.KEYBOARD_THEME] = themeId }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateFavoriteThemes(favorites: Set<String>): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.FAVORITE_THEMES] = favorites }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAlternativeKeyboardLayout(layout: AlternativeKeyboardLayout): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.ALTERNATIVE_KEYBOARD_LAYOUT] = layout.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAdaptiveKeyboardModesEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.ADAPTIVE_KEYBOARD_MODES_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyboardDisplayMode(mode: KeyboardDisplayMode?): Result<Unit> = try {
        dataStore.edit {
            if (mode == null) {
                it.remove(PreferenceKeys.KEYBOARD_DISPLAY_MODE)
            } else {
                it[PreferenceKeys.KEYBOARD_DISPLAY_MODE] = mode.name
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateOneHandedModeEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.ONE_HANDED_MODE_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowLanguageSwitchKey(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_LANGUAGE_SWITCH_KEY] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateMergedDictionaries(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.MERGED_DICTIONARIES] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updatePauseOnMisspelledWord(enabled: Boolean): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.PAUSE_ON_MISSPELLED_WORD] = enabled
            if (enabled) it[PreferenceKeys.AUTOCORRECTION_ENABLED] = false
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAutocorrectionEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.AUTOCORRECTION_ENABLED] = enabled
            if (enabled) it[PreferenceKeys.PAUSE_ON_MISSPELLED_WORD] = false
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowNumberHints(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_NUMBER_HINTS] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateResetToLettersOnDismiss(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.RESET_TO_LETTERS_ON_DISMISS] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyPressHighlightEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.PRESS_HIGHLIGHT_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Irreversible. Clears all supported languages atomically within a single transaction.
     */
    suspend fun clearLearnedWords(): Result<Unit> = try {
        database.withTransaction {
            val supportedLanguages = KeyboardSettings.SUPPORTED_LANGUAGES
            supportedLanguages.forEach { languageTag ->
                database.learnedWordDao().clearLanguage(languageTag)
            }
            database.userWordFrequencyDao().clearAll()
            database.userKanjiFrequencyDao().clearAll()
        }
        wordFrequencyRepository.clearCache()
        cacheMemoryManager.forceCleanup()
        Result.success(Unit)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = e,
            context = mapOf("operation" to "clearLearnedWords")
        )
        Result.failure(e)
    }

    /** Does not affect learned words. Use [clearLearnedWords] to remove learned vocabulary. */
    suspend fun resetToDefaults(): Result<Unit> = try {
        dataStore.edit { it.clear() }
        Result.success(Unit)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "resetToDefaults")
        )
        Result.failure(e)
    }

    /**
     * Exports all exportable preferences as a string map. Excludes [PreferenceKeys.CLIPBOARD_CONSENT_SHOWN].
     * Set<String> values are joined with [EXPORT_SET_DELIMITER].
     */
    suspend fun exportPreferences(): Result<Map<String, String>> = try {
        val prefs = dataStore.data.first()
        val map = mutableMapOf<String, String>()

        booleanExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it.toString() } }
        intExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it.toString() } }
        stringExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it } }
        setExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it.joinToString(EXPORT_SET_DELIMITER) } }

        Result.success(map)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "exportPreferences")
        )
        Result.failure(e)
    }

    /**
     * Imports preferences from string map. Unknown keys are skipped. Only updates keys present
     * in the map — does not clear absent keys. [PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] is ignored.
     */
    suspend fun importPreferences(prefs: Map<String, String>): Result<Unit> = try {
        val boolLookup = booleanExportKeys.associateBy { it.name }
        val intLookup = intExportKeys.associateBy { it.name }
        val stringLookup = stringExportKeys.associateBy { it.name }
        val setLookup = setExportKeys.associateBy { it.name }

        dataStore.edit { mutablePrefs ->
            prefs.forEach { (keyName, value) ->
                when {
                    keyName in boolLookup -> boolLookup[keyName]?.let {
                        mutablePrefs[it] = value.toBoolean()
                    }
                    keyName in intLookup -> intLookup[keyName]?.let {
                        value.toIntOrNull()?.let { v -> mutablePrefs[it] = v }
                    }
                    keyName in setLookup -> setLookup[keyName]?.let {
                        mutablePrefs[it] = if (value.isEmpty()) {
                            emptySet()
                        } else {
                            value.split(EXPORT_SET_DELIMITER).toSet()
                        }
                    }
                    keyName in stringLookup -> stringLookup[keyName]?.let {
                        mutablePrefs[it] = value
                    }
                }
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "importPreferences")
        )
        Result.failure(e)
    }

    private fun getDefaultSettings(): KeyboardSettings {
        val systemLanguage = Locale.getDefault().language
        return KeyboardSettings.defaultForLocale(systemLanguage)
    }

    companion object {
        const val EXPORT_SET_DELIMITER = ","

        internal val booleanExportKeys: List<Preferences.Key<Boolean>> = listOf(
            PreferenceKeys.SHOW_SUGGESTIONS,
            PreferenceKeys.SPELL_CHECK_ENABLED,
            PreferenceKeys.LEARN_NEW_WORDS,
            PreferenceKeys.CLIPBOARD_ENABLED,
            PreferenceKeys.HAPTIC_FEEDBACK,
            PreferenceKeys.DOUBLE_SPACE_PERIOD,
            PreferenceKeys.AUTO_CAPITALIZATION_ENABLED,
            PreferenceKeys.SWIPE_ENABLED,
            PreferenceKeys.SPACEBAR_CURSOR_CONTROL,
            PreferenceKeys.BACKSPACE_SWIPE_DELETE,
            PreferenceKeys.SHOW_NUMBER_ROW,
            PreferenceKeys.ADAPTIVE_KEYBOARD_MODES_ENABLED,
            PreferenceKeys.ONE_HANDED_MODE_ENABLED,
            PreferenceKeys.SHOW_LANGUAGE_SWITCH_KEY,
            PreferenceKeys.MERGED_DICTIONARIES,
            PreferenceKeys.PAUSE_ON_MISSPELLED_WORD,
            PreferenceKeys.AUTOCORRECTION_ENABLED,
            PreferenceKeys.SHOW_NUMBER_HINTS,
            PreferenceKeys.RESET_TO_LETTERS_ON_DISMISS,
            PreferenceKeys.PRESS_HIGHLIGHT_ENABLED
        )

        internal val intExportKeys: List<Preferences.Key<Int>> = listOf(
            PreferenceKeys.SUGGESTION_COUNT,
            PreferenceKeys.VIBRATION_STRENGTH
        )

        internal val stringExportKeys: List<Preferences.Key<String>> = listOf(
            PreferenceKeys.ACTIVE_LANGUAGES_LIST,
            PreferenceKeys.PRIMARY_LANGUAGE,
            PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE,
            PreferenceKeys.LONG_PRESS_PUNCTUATION_MODE,
            PreferenceKeys.LONG_PRESS_DURATION,
            PreferenceKeys.SPACE_BAR_SIZE,
            PreferenceKeys.KEYBOARD_THEME,
            PreferenceKeys.KEY_SIZE,
            PreferenceKeys.KEY_LABEL_SIZE,
            PreferenceKeys.CURSOR_SPEED,
            PreferenceKeys.ALTERNATIVE_KEYBOARD_LAYOUT,
            PreferenceKeys.KEYBOARD_DISPLAY_MODE
        )

        internal val setExportKeys: List<Preferences.Key<Set<String>>> = listOf(
            PreferenceKeys.FAVORITE_THEMES
        )
    }
}
