package com.urik.keyboard.service

import com.ibm.icu.lang.UScript
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import com.urik.keyboard.KeyboardConstants.CacheConstants
import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class WordState(
    val buffer: String = "",
    val normalizedBuffer: String = "",
    val isFromSwipe: Boolean = false,
    val suggestions: List<SpellingSuggestion> = emptyList(),
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
        val shouldHighlight: Boolean = false,
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
    val suggestions: List<SpellingSuggestion>,
    val isValid: Boolean,
    val timestamp: Long,
)

@Singleton
class TextInputProcessor
    @Inject
    constructor(
        private val spellCheckManager: SpellCheckManager,
        settingsRepository: SettingsRepository,
        cacheMemoryManager: com.urik.keyboard.utils.CacheMemoryManager,
    ) {
        private val processingCache =
            cacheMemoryManager.createCache<String, ProcessingCache>(
                name = "text_processing_cache",
                maxSize = CacheConstants.PROCESSING_CACHE_MAX_SIZE,
            )
        private val suggestionCache =
            cacheMemoryManager.createCache<String, SuggestionCacheEntry>(
                name = "text_suggestion_cache",
                maxSize = CacheConstants.PROCESSING_CACHE_MAX_SIZE,
            )

        private var currentScriptCode = UScript.LATIN
        private var currentLocale: ULocale = ULocale.ENGLISH
        private var currentSettings = KeyboardSettings()

        private val processorJob = SupervisorJob()
        private val processorScope = CoroutineScope(processorJob + Dispatchers.Main)

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
                previousSettings.showSuggestions != settings.showSuggestions ||
                previousSettings.mergedDictionaries != settings.mergedDictionaries
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
                val suggestionsEnabled = currentSettings.showSuggestions
                val maxSuggestions = currentSettings.effectiveSuggestionCount

                val requiresSpellCheck = spellCheckEnabled && graphemeCount >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                val shouldGenerateSuggestions = suggestionsEnabled && graphemeCount >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                var isValid = true
                var suggestions = emptyList<SpellingSuggestion>()

                if (requiresSpellCheck || shouldGenerateSuggestions) {
                    val cachedEntry = getCachedSuggestions(normalized)
                    if (cachedEntry != null) {
                        suggestions = cachedEntry.suggestions.take(maxSuggestions)
                        isValid = cachedEntry.isValid
                    } else {
                        if (requiresSpellCheck) {
                            isValid = spellCheckManager.isWordInDictionary(normalized)
                        }
                        if (shouldGenerateSuggestions) {
                            suggestions =
                                spellCheckManager
                                    .getSpellingSuggestionsWithConfidence(normalized)
                                    .sortedByDescending { it.confidence }
                                    .take(maxSuggestions)
                        }
                        cacheSuggestions(normalized, suggestions, isValid)
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

                val shouldHighlight = requiresSpellCheck && !isValid

                return ProcessingResult.Success(
                    wordState = wordState,
                    shouldHighlight = shouldHighlight,
                )
            } catch (e: Exception) {
                return ProcessingResult.Error(
                    e,
                    WordState(buffer = word, normalizedBuffer = word.lowercase()),
                )
            }
        }

        suspend fun getSuggestions(word: String): List<SpellingSuggestion> =
            withContext(Dispatchers.Default) {
                val suggestionsEnabled = currentSettings.showSuggestions && currentSettings.spellCheckEnabled
                val maxSuggestions = currentSettings.effectiveSuggestionCount

                if (!suggestionsEnabled || word.length < TextProcessingConstants.MIN_SUGGESTION_QUERY_LENGTH) {
                    return@withContext emptyList()
                }

                val normalized = normalizeText(word)

                getCachedSuggestions(normalized)?.let { cached ->
                    return@withContext cached.suggestions.take(maxSuggestions)
                }

                val suggestions =
                    try {
                        spellCheckManager
                            .getSpellingSuggestionsWithConfidence(normalized)
                            .sortedByDescending { it.confidence }
                            .take(maxSuggestions)
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

        private fun countGraphemeClusters(text: String): Int {
            val iterator = BreakIterator.getCharacterInstance(currentLocale.toLocale())
            iterator.setText(text)
            var count = 0
            while (iterator.next() != BreakIterator.DONE) {
                count++
            }
            return count
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

        private fun isValidWordInput(word: String): Boolean =
            word.isNotBlank() && word.length <= TextProcessingConstants.MAX_WORD_INPUT_LENGTH

        private fun getCachedProcessing(word: String): ProcessingCache? {
            val cached = processingCache.getIfPresent(word) ?: return null
            return if (!isCacheExpired(cached.timestamp)) {
                cached
            } else {
                processingCache.invalidate(word)
                null
            }
        }

        private fun cacheProcessing(
            word: String,
            normalized: String,
            graphemeCount: Int,
        ) {
            processingCache.put(
                word,
                ProcessingCache(
                    normalized = normalized,
                    graphemeCount = graphemeCount,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }

        private fun getCachedSuggestions(word: String): SuggestionCacheEntry? {
            val cached = suggestionCache.getIfPresent(word) ?: return null
            return if (!isCacheExpired(cached.timestamp)) {
                cached
            } else {
                suggestionCache.invalidate(word)
                null
            }
        }

        private fun cacheSuggestions(
            word: String,
            suggestions: List<SpellingSuggestion>,
            isValid: Boolean,
        ) {
            suggestionCache.put(
                word,
                SuggestionCacheEntry(
                    suggestions = suggestions,
                    isValid = isValid,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }

        private fun isCacheExpired(timestamp: Long): Boolean = System.currentTimeMillis() - timestamp > CacheConstants.CACHE_TTL_MS

        suspend fun removeSuggestion(word: String): Result<Boolean> =
            withContext(Dispatchers.Default) {
                return@withContext try {
                    val result = spellCheckManager.removeSuggestion(word)
                    invalidateWord(word)
                    result
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        fun invalidateWord(word: String) {
            processingCache.invalidate(word)
            suggestionCache.invalidate(word)
            val normalized = normalizeText(word)
            if (normalized != word) {
                processingCache.invalidate(normalized)
                suggestionCache.invalidate(normalized)
            }
        }

        fun clearCaches() {
            processingCache.invalidateAll()
            suggestionCache.invalidateAll()
        }

        fun getCurrentSettings(): KeyboardSettings = currentSettings
    }
