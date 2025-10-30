@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import com.darkrockstudios.symspellkt.api.SpellChecker
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.impl.SymSpell
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
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
                maxSize = 500,
                onEvict = { _, _ -> },
            )

        private val dictionaryCache: ManagedCache<String, Boolean> =
            cacheMemoryManager.createCache(
                name = "dictionary_cache",
                maxSize = 1000,
                onEvict = { _, _ -> },
            )

        private val blacklistedWords = mutableSetOf<String>()

        @Volatile
        private var commonWordsCache = emptyList<Pair<String, Int>>()

        @Volatile
        private var commonWordsCacheLanguage = ""

        @Volatile
        private var isDestroyed = false

        @Volatile
        private var isInitialized = false

        companion object {
            private const val MAX_EDIT_DISTANCE = 2.0
            private const val PREFIX_LENGTH = 7
            private const val COUNT_THRESHOLD = 1L
            private const val MAX_SUGGESTIONS = 5
            private const val MIN_COMPLETION_LENGTH = 4
            private const val APOSTROPHE_BOOST = 0.30
            private val SUPPORTED_LANGUAGES = setOf("en", "sv")
        }

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
        }

        private suspend fun ensureInitialized(): Boolean = initializationComplete.await()

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

        private suspend fun checkSymSpellDictionary(
            normalizedWord: String,
            languageCode: String,
        ): Boolean {
            val cacheKey = buildCacheKey(normalizedWord, languageCode)
            dictionaryCache.getIfPresent(cacheKey)?.let { return it }

            val spellChecker = getSpellCheckerForLanguage(languageCode)
            if (spellChecker != null) {
                val suggestions = spellChecker.lookup(normalizedWord, Verbosity.All, MAX_EDIT_DISTANCE)
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
                    if (isDestroyed || !isValidInput(word)) {
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
                            maxEditDistance = MAX_EDIT_DISTANCE,
                            prefixLength = PREFIX_LENGTH,
                            countThreshold = COUNT_THRESHOLD,
                        )

                    val symSpell = SymSpell(settings)
                    val dictionaryFile = "dictionaries/${languageCode}_symspell.txt"
                    val inputStream = context.assets.open(dictionaryFile)

                    inputStream.bufferedReader().use { reader ->
                        val lines = reader.readLines()

                        lines.chunked(500).forEach { batch ->
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

            if (!isInitialized || languageCode !in SUPPORTED_LANGUAGES) {
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
                    if (isDestroyed || !isValidInput(word)) {
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
                if (isDestroyed || words.isEmpty()) {
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
                        val suggestions = spellChecker.lookup(normalizedWord, Verbosity.All, MAX_EDIT_DISTANCE)
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
                    if (isDestroyed || !isValidInput(word)) {
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
                            val frequencyBoost = ln(frequency.toDouble() + 1.0) * 0.02
                            val baseConfidence = 0.95 - (index * 0.02)

                            allSuggestions.add(
                                SpellingSuggestion(
                                    word = word,
                                    confidence = (baseConfidence + frequencyBoost).coerceIn(0.85, 0.99),
                                    ranking = index,
                                    source = "learned",
                                ),
                            )
                            seenWords.add(word.lowercase())
                        }
                } catch (_: Exception) {
                }

                try {
                    if (normalizedWord.length >= MIN_COMPLETION_LENGTH) {
                        val completions = getCompletionsForPrefix(normalizedWord, languageCode)
                        completions
                            .filter { (word, _) -> !seenWords.contains(word.lowercase()) && !isWordBlacklisted(word) }
                            .take(5)
                            .forEachIndexed { index, (word, frequency) ->
                                val lengthRatio = normalizedWord.length.toDouble() / word.length.toDouble()
                                val frequencyScore = ln(frequency.toDouble() + 1.0) / 15.0
                                val confidence = (0.70 * lengthRatio + 0.30 * frequencyScore).coerceIn(0.50, 0.84)

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
                        val symSpellResults = spellChecker.lookup(normalizedWord, Verbosity.All, MAX_EDIT_DISTANCE)

                        val scoredResults =
                            symSpellResults
                                .filter { result ->
                                    !seenWords.contains(result.term.lowercase()) && !isWordBlacklisted(result.term)
                                }.map { result ->
                                    val editDistance = result.distance
                                    val maxDistance = MAX_EDIT_DISTANCE
                                    val distanceScore = (maxDistance - editDistance) / maxDistance
                                    var confidence = 0.45 * distanceScore

                                    if (result.term.contains('\'') && editDistance == 1.0) {
                                        val withoutApostrophe = result.term.replace("'", "").replace("'", "")
                                        if (withoutApostrophe.equals(normalizedWord, ignoreCase = true)) {
                                            confidence += APOSTROPHE_BOOST
                                        }
                                    }

                                    result to confidence.coerceIn(0.0, 0.49)
                                }.sortedByDescending { it.second }
                                .take(MAX_SUGGESTIONS)

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
            if (languageCode != commonWordsCacheLanguage || commonWordsCache.isEmpty()) {
                try {
                    val words = getCommonWords()
                    commonWordsCache = words
                    commonWordsCacheLanguage = languageCode
                } catch (_: Exception) {
                    return emptyList()
                }
            }

            return commonWordsCache
                .filter { (word, _) ->
                    word.length > prefix.length && word.startsWith(prefix, ignoreCase = true)
                }.take(10)
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
            return hasValidChars && codePointCount in 1..100
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
         * Used for swipe word candidate generation and prefix completions.
         * @return List of (word, frequency) pairs
         */
        suspend fun getCommonWords(): List<Pair<String, Int>> =
            withContext(ioDispatcher) {
                try {
                    if (isDestroyed) {
                        return@withContext emptyList()
                    }

                    if (!ensureInitialized()) {
                        return@withContext emptyList()
                    }

                    val currentLang = getCurrentLanguage()
                    if (currentLang !in SUPPORTED_LANGUAGES) {
                        return@withContext emptyList()
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

                                        if (word.length in 2..15 &&
                                            word.all {
                                                Character.isLetter(it.code) ||
                                                    Character.getType(it.code) == Character.OTHER_LETTER.toInt() ||
                                                    it == '\'' ||
                                                    it == '\u2019'
                                            } &&
                                            !isWordBlacklisted(word)
                                        ) {
                                            wordFrequencies.add(word to frequency)
                                        }
                                    } else if (parts.size == 1) {
                                        val word = parts[0].lowercase().trim()
                                        if (word.length in 2..15 &&
                                            word.all {
                                                Character.isLetter(it.code) ||
                                                    Character.getType(it.code) == Character.OTHER_LETTER.toInt() ||
                                                    it == '\'' ||
                                                    it == '\u2019'
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

                    return@withContext sortedWords
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }

        /**
         * Clears all state and cancels background initialization.
         *
         * Call when keyboard service destroyed.
         */
        fun cleanup() {
            if (isDestroyed) return
            isDestroyed = true

            initializationJob?.cancel()
            initScope.cancel()
            spellCheckers.clear()

            suggestionCache.invalidateAll()
            dictionaryCache.invalidateAll()
            synchronized(blacklistedWords) {
                blacklistedWords.clear()
            }

            commonWordsCache = emptyList()
            commonWordsCacheLanguage = ""
        }
    }
