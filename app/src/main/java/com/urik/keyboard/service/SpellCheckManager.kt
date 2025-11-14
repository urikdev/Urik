@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import com.darkrockstudios.symspellkt.api.SpellChecker
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.impl.SymSpell
import com.urik.keyboard.KeyboardConstants.CacheConstants
import com.urik.keyboard.KeyboardConstants.SpellCheckConstants
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Spelling suggestion with confidence score.
 *
 * @property source "learned" (user data), "symspell" (dictionary), or "completion" (predictive)
 */
data class SpellingSuggestion(
    val word: String,
    val confidence: Double,
    val ranking: Int,
    val source: String = "unknown",
)

/**
 * Spell checking and suggestion generation using SymSpell algorithm.
 */
@Singleton
class SpellCheckManager
    @Inject
    constructor(
        private val context: Context,
        private val languageManager: LanguageManager,
        private val wordLearningEngine: WordLearningEngine,
        cacheMemoryManager: CacheMemoryManager,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        private val initializationComplete = CompletableDeferred<Boolean>()
        private var initializationJob: Job? = null
        private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private val spellCheckers = ConcurrentHashMap<String, SpellChecker>()
        private var currentLanguage: String = "en"

        private val suggestionCache: ManagedCache<String, List<SpellingSuggestion>> =
            cacheMemoryManager.createCache(
                name = "spell_suggestions",
                maxSize = CacheConstants.SUGGESTION_CACHE_SIZE,
                onEvict = { _, _ -> },
            )

        private val dictionaryCache: ManagedCache<String, Boolean> =
            cacheMemoryManager.createCache(
                name = "dictionary_cache",
                maxSize = CacheConstants.DICTIONARY_CACHE_SIZE,
                onEvict = { _, _ -> },
            )

        private val blacklistedWords = mutableSetOf<String>()

        @Volatile
        private var commonWordsCache = emptyList<Pair<String, Int>>()

        @Volatile
        private var commonWordsCacheStripped = emptyList<Triple<String, Int, String>>()

        @Volatile
        private var commonWordsCacheLanguage = ""

        @Volatile
        private var isInitialized = false

        init {
            initializationJob =
                initScope.launch {
                    val success =
                        try {
                            withContext(ioDispatcher) {
                                initializeSymSpell()
                            }
                            true
                        } catch (e: Exception) {
                            ErrorLogger.logException(
                                component = "SpellCheckManager",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("phase" to "initialization"),
                            )
                            false
                        }
                    initializationComplete.complete(success)
                }

            initScope.launch {
                languageManager.currentLanguage.collect { newLanguage ->
                    val languageCode = newLanguage.split("-").first()
                    if (languageCode != currentLanguage && isInitialized) {
                        withContext(ioDispatcher) {
                            preloadLanguage(languageCode)
                        }
                    }
                }
            }
        }

        private suspend fun ensureInitialized(): Boolean =
            withTimeoutOrNull(SpellCheckConstants.INITIALIZATION_TIMEOUT_MS) {
                initializationComplete.await()
            } ?: run {
                ErrorLogger.logException(
                    component = "SpellCheckManager",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = TimeoutException("Initialization timeout after ${SpellCheckConstants.INITIALIZATION_TIMEOUT_MS}ms"),
                    context = mapOf("phase" to "ensureInitialized"),
                )
                false
            }

        private suspend fun initializeSymSpell() {
            try {
                currentLanguage = getCurrentLanguage()

                val spellChecker = createSpellChecker(context, currentLanguage)
                if (spellChecker != null) {
                    spellCheckers[currentLanguage] = spellChecker
                    isInitialized = true
                } else {
                    throw IllegalStateException("Failed to create spell checker")
                }
            } catch (e: Exception) {
                isInitialized = false
                throw e
            }
        }

        private suspend fun preloadLanguage(languageCode: String) {
            try {
                if (languageCode !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                    return
                }

                currentLanguage = languageCode

                if (spellCheckers[languageCode] == null) {
                    val spellChecker = createSpellChecker(context, languageCode)
                    if (spellChecker != null) {
                        spellCheckers[languageCode] = spellChecker
                        loadCommonWordsCache(languageCode)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SpellCheckManager",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("phase" to "language_preload", "language" to languageCode),
                )
            }
        }

        private suspend fun checkSymSpellDictionary(
            normalizedWord: String,
            languageCode: String,
        ): Boolean {
            val cacheKey = buildCacheKey(normalizedWord, languageCode)
            dictionaryCache.getIfPresent(cacheKey)?.let { return it }

            val spellChecker = getSpellCheckerForLanguage(languageCode)
            if (spellChecker != null) {
                val suggestions = spellChecker.lookup(normalizedWord, Verbosity.All, SpellCheckConstants.MAX_EDIT_DISTANCE)
                val isInDictionary =
                    suggestions.any {
                        it.term.equals(normalizedWord, ignoreCase = true) && it.distance == 0.0
                    }
                dictionaryCache.put(cacheKey, isInDictionary)
                return isInDictionary
            } else {
                dictionaryCache.put(cacheKey, false)
                return false
            }
        }

        suspend fun isWordInSymSpellDictionary(word: String): Boolean =
            withContext(Dispatchers.Default) {
                try {
                    if (!isValidInput(word)) {
                        return@withContext false
                    }

                    if (!ensureInitialized()) {
                        return@withContext false
                    }

                    val currentLang = getCurrentLanguage()
                    val locale = getLocaleForLanguage()
                    val normalizedWord = word.lowercase(locale).trim()

                    return@withContext checkSymSpellDictionary(normalizedWord, currentLang)
                } catch (_: Exception) {
                    return@withContext false
                }
            }

        private suspend fun loadCommonWordsCache(languageCode: String) {
            if (languageCode == commonWordsCacheLanguage && commonWordsCache.isNotEmpty()) {
                return
            }

            try {
                val words = getCommonWords()
                commonWordsCache = words
                commonWordsCacheLanguage = languageCode
            } catch (_: Exception) {
                commonWordsCache = emptyList()
            }
        }

        private suspend fun createSpellChecker(
            context: Context,
            languageCode: String,
        ): SpellChecker? =
            coroutineScope {
                try {
                    val settings =
                        SpellCheckSettings(
                            maxEditDistance = SpellCheckConstants.MAX_EDIT_DISTANCE,
                            prefixLength = SpellCheckConstants.PREFIX_LENGTH,
                            countThreshold = SpellCheckConstants.COUNT_THRESHOLD,
                        )

                    val symSpell = SymSpell(settings)
                    val dictionaryFile = "dictionaries/${languageCode}_symspell.txt"
                    val inputStream = context.assets.open(dictionaryFile)

                    inputStream.bufferedReader().use { reader ->
                        val lines = reader.readLines()

                        lines.chunked(SpellCheckConstants.DICTIONARY_BATCH_SIZE).forEach { batch ->
                            ensureActive()

                            batch.forEach { line ->
                                if (line.isNotBlank()) {
                                    val parts = line.trim().split(" ", limit = 2)
                                    if (parts.size >= 2) {
                                        val word = parts[0]
                                        val frequency = parts[1].toIntOrNull() ?: 1
                                        symSpell.createDictionaryEntry(word, frequency)
                                    } else if (parts.size == 1) {
                                        symSpell.createDictionaryEntry(parts[0], 1)
                                    }
                                }
                            }

                            yield()
                        }

                        symSpell
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    ErrorLogger.logException(
                        component = "SpellCheckManager",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context =
                            mapOf(
                                "phase" to "dictionary_load",
                                "language" to languageCode,
                            ),
                    )
                    null
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SpellCheckManager",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context =
                            mapOf(
                                "phase" to "spell_checker_creation",
                                "language" to languageCode,
                            ),
                    )
                    null
                }
            }

        private suspend fun getSpellCheckerForLanguage(languageCode: String): SpellChecker? {
            spellCheckers[languageCode]?.let { return it }

            if (!isInitialized || languageCode !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                return null
            }

            return createSpellChecker(context, languageCode)?.also { newChecker ->
                spellCheckers.putIfAbsent(languageCode, newChecker)
                loadCommonWordsCache(languageCode)
            }
        }

        /**
         * Checks if word exists in dictionary or learned words.
         *
         * Lookup order:
         * 1. Learned words (always checked first, dynamic data)
         * 2. Cache (static dictionary results)
         * 3. SymSpell dictionary (exact match, edit distance = 0)
         *
         * Word is normalized (lowercase, trimmed) before checking.
         *
         * @return true if word valid, false if typo/unknown
         */
        suspend fun isWordInDictionary(word: String): Boolean =
            withContext(Dispatchers.Default) {
                try {
                    if (!isValidInput(word)) {
                        return@withContext false
                    }

                    if (!ensureInitialized()) {
                        return@withContext false
                    }

                    val currentLang = getCurrentLanguage()
                    val locale = getLocaleForLanguage()
                    val normalizedWord = word.lowercase(locale).trim()

                    val result = isWordInDictionary(normalizedWord, currentLang)
                    return@withContext result
                } catch (_: Exception) {
                    return@withContext false
                }
            }

        private suspend fun isWordInDictionary(
            normalizedWord: String,
            languageCode: String,
        ): Boolean {
            val isLearned = wordLearningEngine.isWordLearned(normalizedWord)
            if (isLearned) {
                return true
            }

            return checkSymSpellDictionary(normalizedWord, languageCode)
        }

        /**
         * Batch dictionary check for multiple words.
         *
         * Uses single batch query to WordLearningEngine for learned words.
         *
         * @return Map of original word → validity
         */
        suspend fun areWordsInDictionary(words: List<String>): Map<String, Boolean> =
            withContext(Dispatchers.Default) {
                if (words.isEmpty()) {
                    return@withContext emptyMap()
                }

                if (!ensureInitialized()) {
                    return@withContext words.associateWith { false }
                }

                val results = mutableMapOf<String, Boolean>()
                val currentLang = getCurrentLanguage()
                val locale = getLocaleForLanguage()
                val spellChecker = getSpellCheckerForLanguage(currentLang)

                val wordsToProcess = mutableListOf<Pair<String, String>>()

                for (word in words) {
                    if (!isValidInput(word)) {
                        results[word] = false
                        continue
                    }

                    val normalizedWord = word.lowercase(locale).trim()
                    val cacheKey = buildCacheKey(normalizedWord, currentLang)

                    dictionaryCache.getIfPresent(cacheKey)?.let { cached ->
                        results[word] = cached
                        continue
                    }

                    wordsToProcess.add(word to normalizedWord)
                }

                val learnedStatus =
                    if (wordsToProcess.isNotEmpty()) {
                        try {
                            val normalizedWords = wordsToProcess.map { it.second }
                            wordLearningEngine.areWordsLearned(normalizedWords)
                        } catch (_: Exception) {
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }

                for ((originalWord, normalizedWord) in wordsToProcess) {
                    val cacheKey = buildCacheKey(normalizedWord, currentLang)

                    val isLearned = learnedStatus[normalizedWord] ?: false
                    if (isLearned) {
                        dictionaryCache.put(cacheKey, true)
                        results[originalWord] = true
                        continue
                    }

                    if (spellChecker != null) {
                        val suggestions = spellChecker.lookup(normalizedWord, Verbosity.All, SpellCheckConstants.MAX_EDIT_DISTANCE)
                        val isInDictionary =
                            suggestions.any {
                                it.term.equals(normalizedWord, ignoreCase = true) && it.distance == 0.0
                            }

                        dictionaryCache.put(cacheKey, isInDictionary)
                        results[originalWord] = isInDictionary
                    } else {
                        dictionaryCache.put(cacheKey, false)
                        results[originalWord] = false
                    }
                }

                return@withContext results
            }

        /**
         * Generates spelling suggestions for misspelled word.
         *
         * Simpler API wrapping getSpellingSuggestionsWithConfidence().
         *
         * @param maxSuggestions Number of suggestions to return (default 3)
         * @return List of suggested corrections, confidence-sorted
         */
        suspend fun generateSuggestions(
            word: String,
            maxSuggestions: Int = 3,
        ): List<String> {
            return try {
                if (!ensureInitialized()) {
                    return emptyList()
                }

                val suggestions = getSpellingSuggestionsWithConfidence(word)
                suggestions
                    .sortedByDescending { it.confidence }
                    .take(maxSuggestions)
                    .map { it.word }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * Generates spelling suggestions with confidence scores.
         *
         * Combines learned words + dictionary corrections + prefix completions, ranked by confidence.
         * Learned words boosted by frequency, corrections penalized by edit distance, completions by prefix match.
         *
         * Cached (500 entries, LRU) for performance.
         *
         * @return List of suggestions sorted by confidence (highest first)
         */
        suspend fun getSpellingSuggestionsWithConfidence(word: String): List<SpellingSuggestion> =
            withContext(Dispatchers.Default) {
                try {
                    if (!isValidInput(word)) {
                        return@withContext emptyList()
                    }

                    if (!ensureInitialized()) {
                        return@withContext emptyList()
                    }

                    val currentLang = getCurrentLanguage()
                    val locale = getLocaleForLanguage()
                    val normalizedWord = word.lowercase(locale).trim()

                    return@withContext getSpellingSuggestions(normalizedWord, currentLang)
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }

        private suspend fun getSpellingSuggestions(
            normalizedWord: String,
            languageCode: String,
        ): List<SpellingSuggestion> {
            val cacheKey = buildCacheKey(normalizedWord, languageCode)

            suggestionCache.getIfPresent(cacheKey)?.let { cached ->
                return cached
            }

            val allSuggestions = requestCombinedSuggestions(normalizedWord, languageCode)

            if (allSuggestions.isNotEmpty()) {
                suggestionCache.put(cacheKey, allSuggestions)
            }

            return allSuggestions
        }

        private suspend fun requestCombinedSuggestions(
            normalizedWord: String,
            languageCode: String,
        ): List<SpellingSuggestion> {
            try {
                val allSuggestions = mutableListOf<SpellingSuggestion>()
                val seenWords = mutableSetOf<String>()

                try {
                    val learnedSuggestions = wordLearningEngine.getSimilarLearnedWordsWithFrequency(normalizedWord, maxResults = 5)

                    learnedSuggestions
                        .filter { (word, _) -> !isWordBlacklisted(word) }
                        .forEachIndexed { index, (word, frequency) ->
                            val frequencyBoost = ln(frequency.toDouble() + 1.0) * SpellCheckConstants.FREQUENCY_BOOST_MULTIPLIER
                            val baseConfidence = SpellCheckConstants.LEARNED_WORD_BASE_CONFIDENCE - (index * 0.02)

                            allSuggestions.add(
                                SpellingSuggestion(
                                    word = word,
                                    confidence =
                                        (baseConfidence + frequencyBoost).coerceIn(
                                            SpellCheckConstants.LEARNED_WORD_CONFIDENCE_MIN,
                                            SpellCheckConstants.LEARNED_WORD_CONFIDENCE_MAX,
                                        ),
                                    ranking = index,
                                    source = "learned",
                                ),
                            )
                            seenWords.add(word.lowercase())
                        }
                } catch (_: Exception) {
                }

                try {
                    if (normalizedWord.length >= SpellCheckConstants.MIN_COMPLETION_LENGTH) {
                        val completions = getCompletionsForPrefix(normalizedWord, languageCode)
                        val strippedInput =
                            com.urik.keyboard.utils.TextMatchingUtils
                                .stripWordPunctuation(normalizedWord)

                        completions
                            .filter { (word, _) -> !seenWords.contains(word.lowercase()) && !isWordBlacklisted(word) }
                            .take(SpellCheckConstants.MAX_PREFIX_COMPLETIONS)
                            .forEachIndexed { index, (word, frequency) ->
                                val strippedWord =
                                    com.urik.keyboard.utils.TextMatchingUtils
                                        .stripWordPunctuation(word)
                                val isContractionMatch = strippedInput.equals(strippedWord, ignoreCase = true)

                                val confidence =
                                    if (isContractionMatch) {
                                        SpellCheckConstants.CONTRACTION_MATCH_CONFIDENCE
                                    } else {
                                        val lengthRatio = normalizedWord.length.toDouble() / word.length.toDouble()
                                        val frequencyScore = ln(frequency.toDouble() + 1.0) / SpellCheckConstants.FREQUENCY_SCORE_DIVISOR
                                        (
                                            SpellCheckConstants.COMPLETION_LENGTH_WEIGHT * lengthRatio +
                                                SpellCheckConstants.COMPLETION_FREQUENCY_WEIGHT * frequencyScore
                                        ).coerceIn(
                                            SpellCheckConstants.COMPLETION_CONFIDENCE_MIN,
                                            SpellCheckConstants.COMPLETION_CONFIDENCE_MAX,
                                        )
                                    }

                                allSuggestions.add(
                                    SpellingSuggestion(
                                        word = word,
                                        confidence = confidence,
                                        ranking = allSuggestions.size,
                                        source = "completion",
                                    ),
                                )
                                seenWords.add(word.lowercase())
                            }
                    }
                } catch (_: Exception) {
                }

                try {
                    val spellChecker = getSpellCheckerForLanguage(languageCode)
                    if (spellChecker != null) {
                        val symSpellResults = spellChecker.lookup(normalizedWord, Verbosity.All, SpellCheckConstants.MAX_EDIT_DISTANCE)
                        val strippedInput =
                            com.urik.keyboard.utils.TextMatchingUtils
                                .stripWordPunctuation(normalizedWord)

                        val scoredResults =
                            symSpellResults
                                .filter { result ->
                                    !seenWords.contains(result.term.lowercase()) && !isWordBlacklisted(result.term)
                                }.map { result ->
                                    val editDistance = result.distance
                                    val strippedResult =
                                        com.urik.keyboard.utils.TextMatchingUtils
                                            .stripWordPunctuation(result.term)
                                    val isContractionMatch = strippedInput.equals(strippedResult, ignoreCase = true)

                                    val confidence =
                                        if (isContractionMatch) {
                                            SpellCheckConstants.CONTRACTION_MATCH_CONFIDENCE
                                        } else {
                                            val maxDistance = SpellCheckConstants.MAX_EDIT_DISTANCE
                                            val distanceScore = (maxDistance - editDistance) / maxDistance
                                            var baseConfidence = SpellCheckConstants.SYMSPELL_DISTANCE_WEIGHT * distanceScore

                                            if (strippedResult != result.term && editDistance == 1.0) {
                                                baseConfidence += SpellCheckConstants.APOSTROPHE_BOOST
                                            }

                                            baseConfidence.coerceIn(
                                                SpellCheckConstants.SYMSPELL_CONFIDENCE_MIN,
                                                SpellCheckConstants.SYMSPELL_CONFIDENCE_MAX,
                                            )
                                        }

                                    result to confidence
                                }.sortedByDescending { it.second }
                                .take(SpellCheckConstants.MAX_SUGGESTIONS)

                        scoredResults.forEachIndexed { index, (result, confidence) ->
                            allSuggestions.add(
                                SpellingSuggestion(
                                    word = result.term,
                                    confidence = confidence,
                                    ranking = allSuggestions.size,
                                    source = "symspell",
                                ),
                            )
                            seenWords.add(result.term.lowercase())
                        }
                    }
                } catch (_: Exception) {
                }

                return allSuggestions.sortedByDescending { it.confidence }
            } catch (_: Exception) {
                return emptyList()
            }
        }

        private suspend fun getCompletionsForPrefix(
            prefix: String,
            languageCode: String,
        ): List<Pair<String, Int>> {
            if (languageCode != commonWordsCacheLanguage || commonWordsCacheStripped.isEmpty()) {
                try {
                    getCommonWords()
                } catch (_: Exception) {
                    return emptyList()
                }
            }

            val strippedPrefix =
                com.urik.keyboard.utils.TextMatchingUtils
                    .stripWordPunctuation(prefix)

            return commonWordsCacheStripped
                .filter { (word, _, strippedWord) ->
                    strippedWord.startsWith(strippedPrefix, ignoreCase = true) &&
                        (strippedWord.length > strippedPrefix.length || word != strippedWord)
                }.map { (word, freq, _) -> word to freq }
                .take(SpellCheckConstants.MAX_PREFIX_COMPLETION_RESULTS)
        }

        private fun isValidInput(text: String): Boolean {
            if (text.isBlank()) return false

            val hasValidChars =
                text.any { char ->
                    Character.isLetter(char.code) ||
                        Character.isIdeographic(char.code) ||
                        Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                        char == '\'' ||
                        char == '\u2019'
                }

            val codePointCount = text.codePointCount(0, text.length)
            return hasValidChars && codePointCount in 1..SpellCheckConstants.MAX_INPUT_CODEPOINTS
        }

        private fun getCurrentLanguage(): String =
            try {
                val currentLanguage = languageManager.currentLanguage.value
                currentLanguage.split("-").first()
            } catch (_: Exception) {
                "en"
            }

        private fun getLocaleForLanguage(): Locale =
            try {
                val currentLanguage = languageManager.currentLanguage.value
                Locale.forLanguageTag(currentLanguage)
            } catch (_: Exception) {
                Locale.forLanguageTag("en")
            }

        private fun buildCacheKey(
            word: String,
            language: String,
        ): String = "${language}_$word"

        /**
         * Clears all cached suggestions and dictionary lookups.
         *
         * Call when language changed or after bulk word learning.
         */
        fun clearCaches() {
            suggestionCache.invalidateAll()
            dictionaryCache.invalidateAll()
        }

        /**
         * Invalidates cache entries for specific word.
         *
         * CRITICAL: Must be called after word removal from learned words.
         * Otherwise cache marks removed word as valid (stale data).
         */
        fun invalidateWordCache(word: String) {
            try {
                val currentLang = getCurrentLanguage()
                val locale = getLocaleForLanguage()
                val normalizedWord = word.lowercase(locale).trim()
                val cacheKey = buildCacheKey(normalizedWord, currentLang)

                dictionaryCache.invalidate(cacheKey)
                suggestionCache.invalidate(cacheKey)
            } catch (_: Exception) {
            }
        }

        /**
         * Removes suggestion completely (learned word + blacklist).
         *
         * Coordinates removal across WordLearningEngine and SpellCheckManager.
         * Use for long-press suggestion removal.
         */
        suspend fun removeSuggestion(word: String): Result<Boolean> =
            withContext(ioDispatcher) {
                return@withContext try {
                    val result = wordLearningEngine.removeWord(word)
                    blacklistSuggestion(word)
                    result
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        /**
         * Permanently hides word from suggestions (global, all languages).
         *
         * Use for profanity, spam, or unwanted autocorrect.
         */
        fun blacklistSuggestion(word: String) {
            try {
                val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()

                synchronized(blacklistedWords) {
                    blacklistedWords.add(normalizedWord)
                }

                clearCachesForWord(normalizedWord)
            } catch (_: Exception) {
            }
        }

        /**
         * Removes word from blacklist, allowing it in suggestions again.
         */
        fun removeFromBlacklist(word: String) {
            try {
                val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()

                var removed = false
                synchronized(blacklistedWords) {
                    removed = blacklistedWords.remove(normalizedWord)
                }

                if (removed) {
                    clearCachesForWord(normalizedWord)
                }
            } catch (_: Exception) {
            }
        }

        private fun isWordBlacklisted(word: String): Boolean =
            try {
                val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()
                synchronized(blacklistedWords) {
                    normalizedWord in blacklistedWords
                }
            } catch (_: Exception) {
                false
            }

        fun clearBlacklist() {
            synchronized(blacklistedWords) {
                blacklistedWords.clear()
            }
        }

        private fun clearCachesForWord(word: String) {
            val currentLang = getCurrentLanguage()
            val cacheKey = buildCacheKey(word, currentLang)
            dictionaryCache.invalidate(cacheKey)
            suggestionCache.invalidate(cacheKey)
        }

        /**
         * Loads dictionary words sorted by frequency.
         *
         * Cached per language to avoid redundant file I/O.
         * Used for swipe word candidate generation and prefix completions.
         * @return List of (word, frequency) pairs
         */
        suspend fun getCommonWords(): List<Pair<String, Int>> =
            withContext(ioDispatcher) {
                try {
                    if (!ensureInitialized()) {
                        return@withContext emptyList()
                    }

                    val currentLang = getCurrentLanguage()
                    if (currentLang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                        return@withContext emptyList()
                    }

                    if (currentLang == commonWordsCacheLanguage && commonWordsCache.isNotEmpty()) {
                        return@withContext commonWordsCache
                    }

                    val dictionaryFile = "dictionaries/${currentLang}_symspell.txt"
                    val wordFrequencies = mutableListOf<Pair<String, Int>>()

                    try {
                        context.assets.open(dictionaryFile).bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                if (line.isNotBlank()) {
                                    val parts = line.trim().split(" ", limit = 2)
                                    if (parts.size >= 2) {
                                        val word = parts[0].lowercase().trim()
                                        val frequency = parts[1].toIntOrNull() ?: 1

                                        if (word.length in
                                            SpellCheckConstants.COMMON_WORD_MIN_LENGTH..SpellCheckConstants.COMMON_WORD_MAX_LENGTH &&
                                            word.all {
                                                Character.isLetter(it.code) ||
                                                    Character.getType(it.code) == Character.OTHER_LETTER.toInt() ||
                                                    com.urik.keyboard.utils.TextMatchingUtils
                                                        .isValidWordPunctuation(it)
                                            } &&
                                            !isWordBlacklisted(word)
                                        ) {
                                            wordFrequencies.add(word to frequency)
                                        }
                                    } else if (parts.size == 1) {
                                        val word = parts[0].lowercase().trim()
                                        if (word.length in
                                            SpellCheckConstants.COMMON_WORD_MIN_LENGTH..SpellCheckConstants.COMMON_WORD_MAX_LENGTH &&
                                            word.all {
                                                Character.isLetter(it.code) ||
                                                    Character.getType(it.code) == Character.OTHER_LETTER.toInt() ||
                                                    com.urik.keyboard.utils.TextMatchingUtils
                                                        .isValidWordPunctuation(it)
                                            } &&
                                            !isWordBlacklisted(word)
                                        ) {
                                            wordFrequencies.add(word to 1)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: IOException) {
                        return@withContext emptyList()
                    }

                    val sortedWords = wordFrequencies.sortedByDescending { it.second }

                    val sortedWordsWithStripped =
                        sortedWords.map { (word, freq) ->
                            Triple(
                                word,
                                freq,
                                com.urik.keyboard.utils.TextMatchingUtils
                                    .stripWordPunctuation(word),
                            )
                        }

                    commonWordsCache = sortedWords
                    commonWordsCacheStripped = sortedWordsWithStripped
                    commonWordsCacheLanguage = currentLang

                    return@withContext sortedWords
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }
    }
