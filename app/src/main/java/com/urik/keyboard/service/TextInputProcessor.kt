package com.urik.keyboard.service

import com.ibm.icu.lang.UScript
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.ULocale
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Script-aware processing configuration integrated with user settings.
 *
 * Combines base script requirements (CJK needs length 1, Thai needs length 4)
 * with user preference overrides (suggestion count, spell check enabled).
 */
data class InputProcessingConfig(
    val minWordLengthForAutoInsertion: Int,
    val minWordLengthForSpellCheck: Int,
    val minWordLengthForLanguageDetection: Int,
    val minFrequencyForAutoInsertion: Int,
    val minSuggestionQueryLength: Int,
    val learningConfidenceForCorrection: Double,
    val learningConfidenceForSelection: Double,
    val learningConfidenceForTyped: Double,
    val maxSuggestions: Int,
    val suggestionsEnabled: Boolean,
    val spellCheckEnabled: Boolean,
) {
    companion object {
        /**
         * Creates config for specific script with user settings applied.
         *
         * Base configs account for script characteristics:
         * - CJK (HAN): 1-char min (logographic)
         * - Thai/Khmer: 4-char min (no spaces, complex clusters)
         * - Arabic/Hebrew: 2-char min (RTL, ligatures)
         * - Latin: 2-char min (standard)
         *
         * User settings override: maxSuggestions, suggestionsEnabled, spellCheckEnabled
         */
        fun forScriptAndSettings(
            scriptCode: Int,
            settings: KeyboardSettings,
        ): InputProcessingConfig {
            val baseConfig =
                when (scriptCode) {
                    UScript.HAN ->
                        InputProcessingConfig(
                            minWordLengthForAutoInsertion = 1,
                            minWordLengthForSpellCheck = 1,
                            minWordLengthForLanguageDetection = 2,
                            minFrequencyForAutoInsertion = 1,
                            minSuggestionQueryLength = 1,
                            learningConfidenceForCorrection = 0.9,
                            learningConfidenceForSelection = 0.95,
                            learningConfidenceForTyped = 0.8,
                            maxSuggestions = 3,
                            suggestionsEnabled = true,
                            spellCheckEnabled = true,
                        )
                    UScript.ARABIC, UScript.HEBREW ->
                        InputProcessingConfig(
                            minWordLengthForAutoInsertion = 2,
                            minWordLengthForSpellCheck = 2,
                            minWordLengthForLanguageDetection = 3,
                            minFrequencyForAutoInsertion = 2,
                            minSuggestionQueryLength = 1,
                            learningConfidenceForCorrection = 0.85,
                            learningConfidenceForSelection = 0.9,
                            learningConfidenceForTyped = 0.75,
                            maxSuggestions = 3,
                            suggestionsEnabled = true,
                            spellCheckEnabled = true,
                        )
                    UScript.THAI, UScript.LAO, UScript.KHMER ->
                        InputProcessingConfig(
                            minWordLengthForAutoInsertion = 4,
                            minWordLengthForSpellCheck = 4,
                            minWordLengthForLanguageDetection = 6,
                            minFrequencyForAutoInsertion = 2,
                            minSuggestionQueryLength = 2,
                            learningConfidenceForCorrection = 0.9,
                            learningConfidenceForSelection = 0.95,
                            learningConfidenceForTyped = 0.8,
                            maxSuggestions = 3,
                            suggestionsEnabled = true,
                            spellCheckEnabled = true,
                        )
                    UScript.HANGUL ->
                        InputProcessingConfig(
                            minWordLengthForAutoInsertion = 2,
                            minWordLengthForSpellCheck = 2,
                            minWordLengthForLanguageDetection = 3,
                            minFrequencyForAutoInsertion = 2,
                            minSuggestionQueryLength = 1,
                            learningConfidenceForCorrection = 0.9,
                            learningConfidenceForSelection = 0.95,
                            learningConfidenceForTyped = 0.8,
                            maxSuggestions = 3,
                            suggestionsEnabled = true,
                            spellCheckEnabled = true,
                        )
                    else ->
                        InputProcessingConfig(
                            minWordLengthForAutoInsertion = 2,
                            minWordLengthForSpellCheck = 2,
                            minWordLengthForLanguageDetection = 3,
                            minFrequencyForAutoInsertion = 2,
                            minSuggestionQueryLength = 1,
                            learningConfidenceForCorrection = 0.9,
                            learningConfidenceForSelection = 0.95,
                            learningConfidenceForTyped = 0.8,
                            maxSuggestions = 3,
                            suggestionsEnabled = true,
                            spellCheckEnabled = true,
                        )
                }

            return baseConfig.copy(
                maxSuggestions = settings.effectiveSuggestionCount,
                suggestionsEnabled = settings.showSuggestions && settings.spellCheckEnabled,
                spellCheckEnabled = settings.spellCheckEnabled,
                minSuggestionQueryLength =
                    if (settings.showSuggestions && settings.spellCheckEnabled) {
                        baseConfig.minSuggestionQueryLength
                    } else {
                        Int.MAX_VALUE
                    },
            )
        }
    }
}

/**
 * Word processing state with metadata.
 *
 * Tracks buffer content, normalization, validity, and suggestions.
 */
data class WordState(
    val buffer: String = "",
    val normalizedBuffer: String = "",
    val isFromSwipe: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val graphemeCount: Int = 0,
    val scriptCode: Int = UScript.LATIN,
    val isValid: Boolean = false,
    val requiresSpellCheck: Boolean = false,
) {
    val hasContent: Boolean get() = buffer.isNotEmpty()
    val isEmpty: Boolean get() = buffer.isEmpty()
}

/**
 * Processing operation result.
 */
sealed class ProcessingResult {
    data class Success(
        val wordState: WordState,
        val shouldGenerateSuggestions: Boolean = false,
        val shouldHighlight: Boolean = false,
        val processed: Boolean = true,
    ) : ProcessingResult()

    data class Error(
        val exception: Throwable,
        val fallbackState: WordState = WordState(),
    ) : ProcessingResult()
}

/**
 * Cached processing data to avoid redundant expensive operations.
 */
private data class ProcessingCache(
    val normalized: String,
    val graphemeCount: Int,
    val scriptCode: Int,
    val timestamp: Long,
)

/**
 * Cached suggestion data with validity state.
 */
private data class SuggestionCacheEntry(
    val suggestions: List<String>,
    val isValid: Boolean,
    val timestamp: Long,
)

/**
 * Processes text input with script-aware normalization and spell checking.
 */
@Singleton
class TextInputProcessor
    @Inject
    constructor(
        private val spellCheckManager: SpellCheckManager,
        settingsRepository: SettingsRepository,
    ) {
        private val normalizer = Normalizer2.getNFCInstance()

        private val processingCache = ConcurrentHashMap<String, ProcessingCache>()
        private val suggestionCache = ConcurrentHashMap<String, SuggestionCacheEntry>()

        private var currentScriptCode = UScript.LATIN
        private var currentConfig =
            InputProcessingConfig.forScriptAndSettings(
                UScript.LATIN,
                KeyboardSettings(),
            )
        private var currentLocale: ULocale = ULocale.ENGLISH
        private var currentSettings = KeyboardSettings()

        private val processorJob = SupervisorJob()
        private val processorScope = CoroutineScope(processorJob + Dispatchers.Main)

        private companion object {
            const val MAX_CACHE_SIZE = 200
            const val CACHE_TTL_MS = 300000L
            const val CLEANUP_THRESHOLD = 250
        }

        init {
            settingsRepository.settings
                .onEach { newSettings ->
                    updateConfiguration(newSettings)
                }.launchIn(processorScope)
        }

        /**
         * Updates configuration when settings change.
         *
         * Clears caches if spell check or suggestions toggled to prevent stale data.
         */
        private fun updateConfiguration(settings: KeyboardSettings) {
            val previousSettings = currentSettings
            currentSettings = settings

            currentConfig = InputProcessingConfig.forScriptAndSettings(currentScriptCode, settings)

            if (previousSettings.spellCheckEnabled != settings.spellCheckEnabled ||
                previousSettings.showSuggestions != settings.showSuggestions
            ) {
                clearCaches()
                spellCheckManager.clearCaches()
            }
        }

        /**
         * Updates script context for i18n-aware processing.
         *
         * Call when language changes to recalculate processing rules.
         * Clears caches since normalization/grapheme counting rules differ per script.
         */
        fun updateScriptContext(
            locale: ULocale,
            scriptCode: Int,
        ) {
            if (currentScriptCode != scriptCode || currentLocale != locale) {
                currentLocale = locale
                currentScriptCode = scriptCode

                currentConfig = InputProcessingConfig.forScriptAndSettings(scriptCode, currentSettings)

                clearCaches()
            }
        }

        /**
         * Processes single character input (incremental typing).
         *
         * Optimized for partial word processing during character-by-character input.
         *
         * @param char Single character typed
         * @param currentWord Full word buffer after adding char
         * @return Processing result with word state and suggestions
         */
        suspend fun processCharacterInput(
            char: String,
            currentWord: String,
            inputMethod: InputMethod = InputMethod.TYPED,
        ): ProcessingResult =
            withContext(Dispatchers.Default) {
                if (!isValidCharacterInput(char)) {
                    return@withContext ProcessingResult.Error(
                        IllegalArgumentException("Invalid character input: $char"),
                    )
                }
                return@withContext processWordInternal(currentWord, inputMethod)
            }

        /**
         * Processes complete word input (swipe gesture).
         *
         * Optimized for full word validation and suggestion generation.
         *
         * @param word Complete swiped word
         * @return Processing result with validity and suggestions
         */
        suspend fun processWordInput(
            word: String,
            inputMethod: InputMethod = InputMethod.SWIPED,
        ): ProcessingResult =
            withContext(Dispatchers.Default) {
                if (!isValidWordInput(word)) {
                    return@withContext ProcessingResult.Error(
                        IllegalArgumentException("Invalid word input: $word"),
                        WordState(),
                    )
                }

                return@withContext processWordInternal(word, inputMethod)
            }

        private suspend fun processWordInternal(
            word: String,
            inputMethod: InputMethod,
        ): ProcessingResult {
            try {
                val cachedProcessing = getCachedProcessing(word)

                val (normalized, graphemeCount) =
                    if (cachedProcessing != null) {
                        Pair(cachedProcessing.normalized, cachedProcessing.graphemeCount)
                    } else {
                        val normalizedText = normalizeText(word, currentLocale)
                        val graphemes = countGraphemeClusters(normalizedText)

                        cacheProcessing(word, normalizedText, graphemes)

                        Pair(normalizedText, graphemes)
                    }

                val requiresSpellCheck =
                    currentConfig.spellCheckEnabled &&
                        graphemeCount >= currentConfig.minWordLengthForSpellCheck
                var isValid = true
                var suggestions = emptyList<String>()

                if (requiresSpellCheck) {
                    val cachedEntry = getCachedSuggestions(normalized)
                    if (cachedEntry != null) {
                        suggestions = cachedEntry.suggestions.take(currentConfig.maxSuggestions)
                        isValid = cachedEntry.isValid
                    } else {
                        isValid = spellCheckManager.isWordInDictionary(normalized)
                        if (currentConfig.suggestionsEnabled) {
                            suggestions =
                                spellCheckManager.generateSuggestions(
                                    normalized,
                                    maxSuggestions = currentConfig.maxSuggestions,
                                )
                            cacheSuggestions(normalized, suggestions, isValid)
                        } else {
                            cacheSuggestions(normalized, emptyList(), isValid)
                        }
                    }
                }

                val wordState =
                    WordState(
                        buffer = word,
                        normalizedBuffer = normalized,
                        isFromSwipe = inputMethod == InputMethod.SWIPED,
                        suggestions = if (currentConfig.suggestionsEnabled) suggestions else emptyList(),
                        graphemeCount = graphemeCount,
                        scriptCode = currentScriptCode,
                        isValid = isValid,
                        requiresSpellCheck = requiresSpellCheck,
                    )

                val shouldGenerateSuggestions =
                    currentConfig.suggestionsEnabled &&
                        graphemeCount >= currentConfig.minSuggestionQueryLength
                val shouldHighlight = requiresSpellCheck && !isValid && currentConfig.spellCheckEnabled

                return ProcessingResult.Success(
                    wordState = wordState,
                    shouldGenerateSuggestions = shouldGenerateSuggestions,
                    shouldHighlight = shouldHighlight,
                )
            } catch (e: Exception) {
                return ProcessingResult.Error(
                    e,
                    WordState(buffer = word, normalizedBuffer = word.lowercase()),
                )
            }
        }

        /**
         * Gets suggestions for partial word (incremental typing).
         *
         * Respects user settings for suggestion count and enabled state.
         */
        suspend fun getSuggestions(word: String): List<String> =
            withContext(Dispatchers.Default) {
                if (!currentConfig.suggestionsEnabled || word.length < currentConfig.minSuggestionQueryLength) {
                    return@withContext emptyList()
                }

                val normalized = normalizeText(word, currentLocale)

                getCachedSuggestions(normalized)?.let { cached ->
                    return@withContext cached.suggestions.take(currentConfig.maxSuggestions)
                }

                val suggestions =
                    try {
                        spellCheckManager.generateSuggestions(normalized, maxSuggestions = currentConfig.maxSuggestions)
                    } catch (_: Exception) {
                        emptyList()
                    }

                cacheSuggestions(normalized, suggestions, true)
                return@withContext suggestions
            }

        /**
         * Validates word for dictionary/learning purposes.
         *
         * Returns true if spell check disabled (user preference).
         */
        suspend fun validateWord(word: String): Boolean =
            withContext(Dispatchers.Default) {
                if (!currentConfig.spellCheckEnabled) {
                    return@withContext true
                }

                val normalized = normalizeText(word, currentLocale)

                return@withContext try {
                    spellCheckManager.isWordInDictionary(normalized)
                } catch (_: Exception) {
                    false
                }
            }

        /**
         * Unicode-aware text normalization.
         *
         * Strategy:
         * - Arabic/Hebrew/CJK: NFC normalization only (preserve case)
         * - Other scripts: lowercase + trim (Latin, Cyrillic, etc.)
         */
        private fun normalizeText(
            text: String,
            locale: ULocale?,
        ): String {
            if (text.isBlank()) return text

            return try {
                when (currentScriptCode) {
                    UScript.ARABIC, UScript.HEBREW, UScript.HAN -> {
                        normalizer.normalize(text)
                    }
                    else -> {
                        val localeToUse = locale?.toLocale() ?: currentLocale.toLocale()
                        text.lowercase(localeToUse).trim()
                    }
                }
            } catch (_: Exception) {
                text.lowercase(currentLocale.toLocale()).trim()
            }
        }

        /**
         * Script-aware grapheme counting.
         *
         * Strategy:
         * - Complex scripts (Arabic, Thai, CJK): ICU BreakIterator (handles clusters)
         * - Simple scripts (Latin): String.length (performance)
         *
         */
        private fun countGraphemeClusters(text: String): Int {
            if (text.isEmpty()) return 0

            return try {
                when (currentScriptCode) {
                    UScript.ARABIC, UScript.HEBREW, UScript.HAN,
                    UScript.THAI, UScript.KHMER,
                    -> {
                        val breaker = BreakIterator.getCharacterInstance()
                        breaker.setText(text)
                        var count = 0
                        breaker.first()
                        while (breaker.next() != BreakIterator.DONE) {
                            count++
                        }
                        count
                    }
                    else -> text.length
                }
            } catch (_: Exception) {
                text.codePointCount(0, text.length)
            }
        }

        private fun isValidCharacterInput(char: String): Boolean {
            if (char.isBlank()) return false

            return char.any { character ->
                Character.isLetter(character.code) ||
                    Character.isIdeographic(character.code) ||
                    Character.getType(character.code) == Character.OTHER_LETTER.toInt() ||
                    character == '\'' ||
                    character == '\u2019' ||
                    character == '-'
            }
        }

        private fun isValidWordInput(word: String): Boolean = word.isNotBlank() && word.length <= 50

        private fun getCachedProcessing(word: String): ProcessingCache? {
            val cached = processingCache[word]
            return if (cached != null && !isCacheExpired(cached.timestamp) && cached.scriptCode == currentScriptCode) {
                cached
            } else {
                processingCache.remove(word)
                null
            }
        }

        private fun cacheProcessing(
            word: String,
            normalized: String,
            graphemeCount: Int,
        ) {
            if (processingCache.size >= CLEANUP_THRESHOLD) {
                cleanupExpiredEntries()
            }

            if (processingCache.size < MAX_CACHE_SIZE) {
                processingCache[word] =
                    ProcessingCache(
                        normalized = normalized,
                        graphemeCount = graphemeCount,
                        scriptCode = currentScriptCode,
                        timestamp = System.currentTimeMillis(),
                    )
            }
        }

        private fun getCachedSuggestions(word: String): SuggestionCacheEntry? {
            val cached = suggestionCache[word]
            return if (cached != null && !isCacheExpired(cached.timestamp)) {
                cached
            } else {
                suggestionCache.remove(word)
                null
            }
        }

        private fun cacheSuggestions(
            word: String,
            suggestions: List<String>,
            isValid: Boolean,
        ) {
            if (suggestionCache.size >= CLEANUP_THRESHOLD) {
                cleanupSuggestionCache()
            }

            if (suggestionCache.size < MAX_CACHE_SIZE) {
                suggestionCache[word] =
                    SuggestionCacheEntry(
                        suggestions = suggestions,
                        isValid = isValid,
                        timestamp = System.currentTimeMillis(),
                    )
            }
        }

        private fun isCacheExpired(timestamp: Long): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL_MS

        private fun cleanupExpiredEntries() {
            val currentTime = System.currentTimeMillis()
            processingCache.entries.removeIf { (_, cache) ->
                currentTime - cache.timestamp > CACHE_TTL_MS
            }
        }

        private fun cleanupSuggestionCache() {
            val currentTime = System.currentTimeMillis()
            suggestionCache.entries.removeIf { (_, cache) ->
                currentTime - cache.timestamp > CACHE_TTL_MS
            }
        }

        /**
         * Invalidates cached data for specific word.
         *
         * Call after word learned/removed to ensure fresh spell check results.
         */
        fun invalidateWord(word: String) {
            processingCache.remove(word)
            suggestionCache.remove(word)
            val normalized = normalizeText(word, currentLocale)
            if (normalized != word) {
                processingCache.remove(normalized)
                suggestionCache.remove(normalized)
            }
        }

        /**
         * Clears all caches.
         *
         * Call on script change or settings change.
         */
        fun clearCaches() {
            processingCache.clear()
            suggestionCache.clear()
        }

        fun getCurrentConfig(): InputProcessingConfig = currentConfig

        fun getCurrentSettings(): KeyboardSettings = currentSettings

        /**
         * Cleans up resources and cancels settings observer.
         *
         * Call when keyboard service destroyed.
         */
        fun cleanup() {
            clearCaches()
            processorJob.cancel()
        }
    }
