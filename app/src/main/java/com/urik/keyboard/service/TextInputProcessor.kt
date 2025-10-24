package com.urik.keyboard.service

import com.ibm.icu.lang.UScript
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

private data class ProcessingCache(
    val normalized: String,
    val graphemeCount: Int,
    val timestamp: Long,
)

private data class SuggestionCacheEntry(
    val suggestions: List<String>,
    val isValid: Boolean,
    val timestamp: Long,
)

@Singleton
class TextInputProcessor
    @Inject
    constructor(
        private val spellCheckManager: SpellCheckManager,
        settingsRepository: SettingsRepository,
    ) {
        private val processingCache = ConcurrentHashMap<String, ProcessingCache>()
        private val suggestionCache = ConcurrentHashMap<String, SuggestionCacheEntry>()

        private var currentScriptCode = UScript.LATIN
        private var currentLocale: ULocale = ULocale.ENGLISH
        private var currentSettings = KeyboardSettings()

        private val processorJob = SupervisorJob()
        private val processorScope = CoroutineScope(processorJob + Dispatchers.Main)

        private companion object {
            const val MAX_CACHE_SIZE = 200
            const val CACHE_TTL_MS = 300000L
            const val CLEANUP_THRESHOLD = 250
            const val MIN_SPELL_CHECK_LENGTH = 2
            const val MIN_SUGGESTION_QUERY_LENGTH = 1
        }

        init {
            settingsRepository.settings
                .onEach { newSettings ->
                    updateConfiguration(newSettings)
                }.launchIn(processorScope)
        }

        private fun updateConfiguration(settings: KeyboardSettings) {
            val previousSettings = currentSettings
            currentSettings = settings

            if (previousSettings.spellCheckEnabled != settings.spellCheckEnabled ||
                previousSettings.showSuggestions != settings.showSuggestions
            ) {
                clearCaches()
                spellCheckManager.clearCaches()
            }
        }

        fun updateScriptContext(
            locale: ULocale,
            scriptCode: Int,
        ) {
            if (currentScriptCode != scriptCode || currentLocale != locale) {
                currentLocale = locale
                currentScriptCode = scriptCode
                clearCaches()
            }
        }

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
                        val normalizedText = normalizeText(word)
                        val graphemes = countGraphemeClusters(normalizedText)

                        cacheProcessing(word, normalizedText, graphemes)

                        Pair(normalizedText, graphemes)
                    }

                val spellCheckEnabled = currentSettings.spellCheckEnabled
                val suggestionsEnabled = currentSettings.showSuggestions && spellCheckEnabled
                val maxSuggestions = currentSettings.effectiveSuggestionCount

                val requiresSpellCheck = spellCheckEnabled && graphemeCount >= MIN_SPELL_CHECK_LENGTH
                var isValid = true
                var suggestions = emptyList<String>()

                if (requiresSpellCheck) {
                    val cachedEntry = getCachedSuggestions(normalized)
                    if (cachedEntry != null) {
                        suggestions = cachedEntry.suggestions.take(maxSuggestions)
                        isValid = cachedEntry.isValid
                    } else {
                        isValid = spellCheckManager.isWordInDictionary(normalized)
                        if (suggestionsEnabled) {
                            suggestions =
                                spellCheckManager.generateSuggestions(
                                    normalized,
                                    maxSuggestions = maxSuggestions,
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
                        suggestions = if (suggestionsEnabled) suggestions else emptyList(),
                        graphemeCount = graphemeCount,
                        scriptCode = currentScriptCode,
                        isValid = isValid,
                        requiresSpellCheck = requiresSpellCheck,
                    )

                val shouldGenerateSuggestions = suggestionsEnabled && graphemeCount >= MIN_SUGGESTION_QUERY_LENGTH
                val shouldHighlight = requiresSpellCheck && !isValid

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

        suspend fun getSuggestions(word: String): List<String> =
            withContext(Dispatchers.Default) {
                val suggestionsEnabled = currentSettings.showSuggestions && currentSettings.spellCheckEnabled
                val maxSuggestions = currentSettings.effectiveSuggestionCount

                if (!suggestionsEnabled || word.length < MIN_SUGGESTION_QUERY_LENGTH) {
                    return@withContext emptyList()
                }

                val normalized = normalizeText(word)

                getCachedSuggestions(normalized)?.let { cached ->
                    return@withContext cached.suggestions.take(maxSuggestions)
                }

                val suggestions =
                    try {
                        spellCheckManager.generateSuggestions(normalized, maxSuggestions = maxSuggestions)
                    } catch (_: Exception) {
                        emptyList()
                    }

                cacheSuggestions(normalized, suggestions, true)
                return@withContext suggestions
            }

        suspend fun validateWord(word: String): Boolean =
            withContext(Dispatchers.Default) {
                if (!currentSettings.spellCheckEnabled) {
                    return@withContext true
                }

                val normalized = normalizeText(word)

                return@withContext try {
                    spellCheckManager.isWordInDictionary(normalized)
                } catch (_: Exception) {
                    false
                }
            }

        private fun normalizeText(text: String): String {
            if (text.isBlank()) return text
            return text.lowercase(currentLocale.toLocale()).trim()
        }

        private fun countGraphemeClusters(text: String): Int = text.length

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
            return if (cached != null && !isCacheExpired(cached.timestamp)) {
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

        fun invalidateWord(word: String) {
            processingCache.remove(word)
            suggestionCache.remove(word)
            val normalized = normalizeText(word)
            if (normalized != word) {
                processingCache.remove(normalized)
                suggestionCache.remove(normalized)
            }
        }

        fun clearCaches() {
            processingCache.clear()
            suggestionCache.clear()
        }

        fun getCurrentSettings(): KeyboardSettings = currentSettings

        fun cleanup() {
            clearCaches()
            processorJob.cancel()
        }
    }
