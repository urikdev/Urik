@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import com.darkrockstudios.symspellkt.api.SpellChecker
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.impl.SymSpell
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import com.urik.keyboard.utils.MemoryPressureSubscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
    val preserveCase: Boolean = false,
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
        private val wordFrequencyRepository: com.urik.keyboard.data.WordFrequencyRepository,
        private val wordNormalizer: WordNormalizer,
        cacheMemoryManager: CacheMemoryManager,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : MemoryPressureSubscriber {
        private val initializationComplete = CompletableDeferred<Boolean>()
        private var initializationJob: Job? = null
        private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private val spellCheckers = ConcurrentHashMap<String, SpellChecker>()
        private var currentLanguage: String = "en"

        private val wordFrequencies = ConcurrentHashMap<String, Long>()

        private val suggestionCache: ManagedCache<String, List<SpellingSuggestion>> =
            cacheMemoryManager.createCache(
                name = "spell_suggestions",
                maxSize = SUGGESTION_CACHE_SIZE,
            )

        private val dictionaryCache: ManagedCache<String, Boolean> =
            cacheMemoryManager.createCache(
                name = "dictionary_cache",
                maxSize = DICTIONARY_CACHE_SIZE,
            )

        private val blacklistedWords = mutableSetOf<String>()

        private data class CachedWord(
            val word: String,
            val frequency: Int,
            val strippedWord: String,
            val accentStrippedWord: String,
        )

        @Volatile
        private var commonWordsCache = emptyList<Pair<String, Int>>()

        @Volatile
        private var commonWordsCacheIndexed = emptyList<CachedWord>()

        @Volatile
        private var commonWordsCacheLanguage = ""

        @Volatile
        private var isInitialized = false

        @Volatile
        private var cachedKeyPositions = emptyMap<Char, android.graphics.PointF>()

        @Volatile
        private var cachedAverageKeySpacing = 0.0

        init {
            cacheMemoryManager.registerPressureSubscriber(this)

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
                languageManager.activeLanguages.collect { newLanguages ->
                    if (isInitialized) {
                        newLanguages.forEach { languageCode ->
                            withContext(ioDispatcher) {
                                preloadLanguage(languageCode)
                            }
                        }
                    }
                }
            }

            initScope.launch {
                languageManager.effectiveDictionaryLanguages.collect {
                    if (isInitialized) {
                        suggestionCache.invalidateAll()
                        dictionaryCache.invalidateAll()
                    }
                }
            }

            initScope.launch {
                languageManager.keyPositions.collect { positions ->
                    cachedKeyPositions = positions
                    cachedAverageKeySpacing =
                        if (positions.size >= 2) {
                            calculateAverageKeySpacing(positions)
                        } else {
                            0.0
                        }
                }
            }
        }

        private suspend fun ensureInitialized(): Boolean =
            withTimeoutOrNull(INITIALIZATION_TIMEOUT_MS) {
                initializationComplete.await()
            } ?: run {
                ErrorLogger.logException(
                    component = "SpellCheckManager",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = TimeoutException("Initialization timeout after ${INITIALIZATION_TIMEOUT_MS}ms"),
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
            dictionaryCache.getIfPresent(cacheKey)?.let {
                return it
            }

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
                val words = getCommonWords(languageCode)
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
                            topK = TOP_K,
                        )

                    val symSpell = SymSpell(settings)
                    val dictionaryFile = "dictionaries/${languageCode}_symspell.txt"
                    val inputStream = context.assets.open(dictionaryFile)

                    inputStream.bufferedReader().use { reader ->
                        val lines = reader.readLines()

                        lines.chunked(DICTIONARY_BATCH_SIZE).forEach { batch ->
                            ensureActive()

                            batch.forEach { line ->
                                if (line.isNotBlank()) {
                                    val parts = line.trim().split(" ", limit = 2)
                                    if (parts.size >= 2) {
                                        val word = parts[0]
                                        val frequency = parts[1].toLongOrNull() ?: 1L
                                        wordFrequencies[word.lowercase()] = frequency
                                        symSpell.createDictionaryEntry(word, frequency.toInt())
                                    } else if (parts.size == 1) {
                                        wordFrequencies[parts[0].lowercase()] = 1L
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

                    val effectiveLanguages = languageManager.effectiveDictionaryLanguages.value
                    val locale = getLocaleForLanguage()
                    val normalizedWord = word.lowercase(locale).trim()

                    for (lang in effectiveLanguages) {
                        if (isWordInDictionary(normalizedWord, lang)) {
                            return@withContext true
                        }
                    }

                    return@withContext false
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

            if (checkSymSpellDictionary(normalizedWord, languageCode)) {
                return true
            }

            return isCliticFormValid(normalizedWord, languageCode)
        }

        private suspend fun isCliticFormValid(
            normalizedWord: String,
            languageCode: String,
        ): Boolean {
            val apostropheIndex = normalizedWord.indexOf('\'')
            if (apostropheIndex <= 0 || apostropheIndex >= normalizedWord.length - 1) {
                return false
            }

            val prefix = normalizedWord.substring(0, apostropheIndex + 1)
            val suffix = normalizedWord.substring(apostropheIndex + 1)

            if (suffix.isBlank()) return false

            val prefixValid =
                wordLearningEngine.isWordLearned(prefix) ||
                    checkSymSpellDictionary(prefix, languageCode) ||
                    wordFrequencies.containsKey(prefix)

            if (!prefixValid) return false

            val suffixValid =
                wordLearningEngine.isWordLearned(suffix) ||
                    checkSymSpellDictionary(suffix, languageCode) ||
                    wordFrequencies.containsKey(suffix)

            if (suffixValid) {
                val cacheKey = buildCacheKey(normalizedWord, languageCode)
                dictionaryCache.put(cacheKey, true)
            }

            return suffixValid
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
                val effectiveLanguages = languageManager.effectiveDictionaryLanguages.value
                val locale = getLocaleForLanguage()

                val wordsToProcess = mutableListOf<Pair<String, String>>()

                for (word in words) {
                    if (!isValidInput(word)) {
                        results[word] = false
                        continue
                    }

                    val normalizedWord = word.lowercase(locale).trim()

                    var foundInCache = false
                    for (lang in effectiveLanguages) {
                        val cacheKey = buildCacheKey(normalizedWord, lang)
                        dictionaryCache.getIfPresent(cacheKey)?.let { cached ->
                            if (cached) {
                                results[word] = true
                                foundInCache = true
                            }
                        }
                        if (foundInCache) break
                    }
                    if (foundInCache) continue

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
                    val isLearned = learnedStatus[normalizedWord] ?: false
                    if (isLearned) {
                        for (lang in effectiveLanguages) {
                            dictionaryCache.put(buildCacheKey(normalizedWord, lang), true)
                        }
                        results[originalWord] = true
                        continue
                    }

                    var foundInDict = false
                    for (lang in effectiveLanguages) {
                        val spellChecker = getSpellCheckerForLanguage(lang)
                        if (spellChecker != null) {
                            val suggestions = spellChecker.lookup(normalizedWord, Verbosity.All, MAX_EDIT_DISTANCE)
                            val isInDictionary =
                                suggestions.any {
                                    it.term.equals(normalizedWord, ignoreCase = true) && it.distance == 0.0
                                }

                            dictionaryCache.put(buildCacheKey(normalizedWord, lang), isInDictionary)
                            if (isInDictionary) {
                                foundInDict = true
                                break
                            }
                        }
                    }
                    results[originalWord] = foundInDict
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

                    val effectiveLanguages = languageManager.effectiveDictionaryLanguages.value
                    val locale = getLocaleForLanguage()
                    val normalizedWord = word.lowercase(locale).trim()

                    return@withContext getSpellingSuggestions(normalizedWord, effectiveLanguages)
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }

        private suspend fun getSpellingSuggestions(
            normalizedWord: String,
            activeLanguages: List<String>,
        ): List<SpellingSuggestion> {
            val cacheKey = buildCacheKey(normalizedWord, activeLanguages.sorted().joinToString(","))

            suggestionCache.getIfPresent(cacheKey)?.let { cached ->
                return cached
            }

            val allSuggestions = requestCombinedSuggestions(normalizedWord, activeLanguages)

            if (allSuggestions.isNotEmpty()) {
                suggestionCache.put(cacheKey, allSuggestions)
            }

            return allSuggestions
        }

        private suspend fun requestCombinedSuggestions(
            normalizedWord: String,
            activeLanguages: List<String>,
        ): List<SpellingSuggestion> =
            coroutineScope {
                try {
                    val allLanguageSuggestions =
                        activeLanguages
                            .map { lang ->
                                async(ioDispatcher) {
                                    querySingleLanguage(normalizedWord, lang)
                                }
                            }.awaitAll()
                            .flatten()

                    mergeAndRankSuggestions(allLanguageSuggestions, MAX_SUGGESTIONS)
                } catch (_: Exception) {
                    emptyList()
                }
            }

        private suspend fun querySingleLanguage(
            normalizedWord: String,
            languageCode: String,
        ): List<SpellingSuggestion> {
            try {
                val allSuggestions = mutableListOf<SpellingSuggestion>()
                val seenWords = mutableSetOf<String>()

                try {
                    val learnedSuggestions =
                        wordLearningEngine.getSimilarLearnedWordsWithFrequency(
                            normalizedWord,
                            languageCode,
                            maxResults = 5,
                        )

                    learnedSuggestions
                        .filter { (word, _) -> !isWordBlacklisted(word) }
                        .forEachIndexed { index, (word, frequency) ->
                            val isContraction =
                                com.urik.keyboard.utils.TextMatchingUtils
                                    .isContractionSuggestion(normalizedWord, word)

                            val confidence =
                                if (isContraction) {
                                    CONTRACTION_GUARANTEED_CONFIDENCE
                                } else {
                                    val frequencyBoost = calculateFrequencyBoost(frequency)
                                    val baseConfidence = LEARNED_WORD_BASE_CONFIDENCE - (index * 0.02)
                                    (baseConfidence + frequencyBoost).coerceIn(
                                        LEARNED_WORD_CONFIDENCE_MIN,
                                        LEARNED_WORD_CONFIDENCE_MAX,
                                    )
                                }

                            allSuggestions.add(
                                SpellingSuggestion(
                                    word = word,
                                    confidence = confidence,
                                    ranking = index,
                                    source = "learned",
                                    preserveCase = true,
                                ),
                            )
                            seenWords.add(word.lowercase())
                        }
                } catch (_: Exception) {
                }

                try {
                    if (normalizedWord.length >= MIN_COMPLETION_LENGTH) {
                        val completions = getCompletionsForPrefix(normalizedWord, languageCode)

                        val filteredCompletions =
                            completions
                                .filter { (word, _) -> !seenWords.contains(word.lowercase()) && !isWordBlacklisted(word) }
                                .take(MAX_PREFIX_COMPLETIONS)

                        val userFrequencies =
                            try {
                                wordFrequencyRepository.getFrequencies(
                                    filteredCompletions.map { it.first },
                                    languageCode,
                                )
                            } catch (_: Exception) {
                                emptyMap()
                            }

                        filteredCompletions
                            .forEachIndexed { index, (word, frequency) ->
                                val isContraction =
                                    com.urik.keyboard.utils.TextMatchingUtils
                                        .isContractionSuggestion(normalizedWord, word)

                                val userFrequency = userFrequencies[word] ?: 0

                                val confidence =
                                    if (isContraction) {
                                        CONTRACTION_GUARANTEED_CONFIDENCE
                                    } else {
                                        val lengthRatio = normalizedWord.length.toDouble() / word.length.toDouble()
                                        val frequencyScore = ln(frequency.toDouble() + 1.0) / FREQUENCY_SCORE_DIVISOR
                                        var baseConfidence =
                                            COMPLETION_LENGTH_WEIGHT * lengthRatio +
                                                COMPLETION_FREQUENCY_WEIGHT * frequencyScore

                                        if (userFrequency > 0) {
                                            val userFreqBoost = calculateFrequencyBoost(userFrequency)
                                            baseConfidence += userFreqBoost
                                        }

                                        baseConfidence.coerceIn(
                                            COMPLETION_CONFIDENCE_MIN,
                                            0.99,
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
                        val symSpellResults = spellChecker.lookup(normalizedWord, Verbosity.All, MAX_EDIT_DISTANCE)
                        val inputAccentStripped = wordNormalizer.stripDiacritics(normalizedWord)

                        val filteredResults =
                            symSpellResults.filter { result ->
                                !seenWords.contains(result.term.lowercase()) && !isWordBlacklisted(result.term)
                            }

                        val userFrequencies =
                            try {
                                wordFrequencyRepository.getFrequencies(
                                    filteredResults.map { it.term },
                                    languageCode,
                                )
                            } catch (_: Exception) {
                                emptyMap()
                            }

                        val scoredResults =
                            filteredResults
                                .map { result ->
                                    val editDistance = result.distance
                                    val isContraction =
                                        com.urik.keyboard.utils.TextMatchingUtils
                                            .isContractionSuggestion(normalizedWord, result.term)

                                    val frequency = wordFrequencies[result.term.lowercase()] ?: 1L
                                    val userFrequency = userFrequencies[result.term] ?: 0

                                    val confidence =
                                        if (isContraction) {
                                            CONTRACTION_GUARANTEED_CONFIDENCE
                                        } else {
                                            val maxDistance = MAX_EDIT_DISTANCE
                                            val distanceScore = (maxDistance - editDistance) / maxDistance
                                            val freqScore = ln(frequency.toDouble() + 1.0) / ln(MAX_DICT_FREQUENCY)
                                            var baseConfidence =
                                                (SYMSPELL_DISTANCE_WEIGHT * distanceScore) +
                                                    (SYMSPELL_FREQUENCY_WEIGHT * freqScore)

                                            if (editDistance == 1.0 && normalizedWord.length == result.term.length) {
                                                baseConfidence += SAME_LENGTH_BONUS

                                                if (normalizedWord.firstOrNull() == result.term.firstOrNull()) {
                                                    baseConfidence += SAME_FIRST_LETTER_BONUS
                                                }

                                                if (normalizedWord.length > 1 && normalizedWord.lastOrNull() == result.term.lastOrNull()) {
                                                    baseConfidence += SAME_LAST_LETTER_BONUS
                                                }

                                                for (i in normalizedWord.indices) {
                                                    if (normalizedWord[i] != result.term[i]) {
                                                        val proximityBonus = calculateProximityBonus(normalizedWord[i], result.term[i])
                                                        baseConfidence += proximityBonus
                                                        break
                                                    }
                                                }
                                            }

                                            val strippedResult =
                                                com.urik.keyboard.utils.TextMatchingUtils
                                                    .stripWordPunctuation(result.term)
                                            if (strippedResult != result.term && editDistance == 1.0) {
                                                baseConfidence += APOSTROPHE_BOOST
                                            }

                                            val termStripped = wordNormalizer.stripDiacritics(result.term.lowercase())
                                            if (inputAccentStripped == termStripped && normalizedWord != result.term.lowercase()) {
                                                baseConfidence += DIACRITIC_PROMOTION_BOOST
                                            }

                                            if (userFrequency > 0) {
                                                val userFreqBoost = calculateFrequencyBoost(userFrequency)
                                                baseConfidence += userFreqBoost
                                            }

                                            baseConfidence.coerceIn(
                                                SYMSPELL_CONFIDENCE_MIN,
                                                0.99,
                                            )
                                        }

                                    result to confidence
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

        private fun mergeAndRankSuggestions(
            suggestions: List<SpellingSuggestion>,
            maxResults: Int,
        ): List<SpellingSuggestion> =
            suggestions
                .groupBy { it.word }
                .mapValues { (_, dupes) -> dupes.maxBy { it.confidence } }
                .values
                .sortedByDescending { it.confidence }
                .take(maxResults)

        private suspend fun getCompletionsForPrefix(
            prefix: String,
            languageCode: String,
        ): List<Pair<String, Int>> {
            if (languageCode != commonWordsCacheLanguage || commonWordsCacheIndexed.isEmpty()) {
                try {
                    getCommonWords(languageCode)
                } catch (_: Exception) {
                    return emptyList()
                }
            }

            val hasApostrophe = prefix.contains('\'')

            val apostropheMatches =
                if (hasApostrophe) {
                    commonWordsCacheIndexed
                        .filter { cached ->
                            cached.word.startsWith(prefix, ignoreCase = true) &&
                                cached.word.length > prefix.length
                        }.map { it.word to it.frequency }
                } else {
                    emptyList()
                }

            if (apostropheMatches.size >= MAX_PREFIX_COMPLETION_RESULTS) {
                return apostropheMatches.take(MAX_PREFIX_COMPLETION_RESULTS)
            }

            val strippedPrefix =
                com.urik.keyboard.utils.TextMatchingUtils
                    .stripWordPunctuation(prefix)
            val accentStrippedPrefix = wordNormalizer.stripDiacritics(prefix).lowercase()

            val apostropheWords = apostropheMatches.map { it.first }.toSet()
            val exactPrefixMatches =
                commonWordsCacheIndexed
                    .filter { cached ->
                        cached.word !in apostropheWords &&
                            cached.strippedWord.startsWith(strippedPrefix, ignoreCase = true) &&
                            (cached.strippedWord.length > strippedPrefix.length || cached.word != cached.strippedWord)
                    }.map { it.word to it.frequency }

            val combined = apostropheMatches + exactPrefixMatches
            if (combined.size >= MAX_PREFIX_COMPLETION_RESULTS) {
                return combined.take(MAX_PREFIX_COMPLETION_RESULTS)
            }

            val seenWords = combined.map { it.first }.toSet()
            val accentFallbackMatches =
                commonWordsCacheIndexed
                    .filter { cached ->
                        cached.word !in seenWords &&
                            cached.accentStrippedWord.startsWith(accentStrippedPrefix) &&
                            cached.accentStrippedWord.length > accentStrippedPrefix.length
                    }.map { it.word to it.frequency }

            return (combined + accentFallbackMatches)
                .take(MAX_PREFIX_COMPLETION_RESULTS)
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
            return hasValidChars && codePointCount in 1..MAX_INPUT_CODEPOINTS
        }

        private fun parseDictionaryLine(line: String): Pair<String, Int>? {
            if (line.isBlank()) return null

            val parts = line.trim().split(" ", limit = 2)
            val word = parts[0].lowercase().trim()
            val frequency =
                if (parts.size >= 2) {
                    parts[1].toIntOrNull() ?: 1
                } else {
                    1
                }

            val isValid =
                word.length in COMMON_WORD_MIN_LENGTH..COMMON_WORD_MAX_LENGTH &&
                    word.all {
                        Character.isLetter(it.code) ||
                            Character.getType(it.code) == Character.OTHER_LETTER.toInt() ||
                            com.urik.keyboard.utils.TextMatchingUtils
                                .isValidWordPunctuation(it)
                    } &&
                    !isWordBlacklisted(word)

            return if (isValid) word to frequency else null
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
         *
         * Clears entire suggestion cache since cached prefix lists may contain this word.
         */
        fun invalidateWordCache(word: String) {
            try {
                val currentLang = getCurrentLanguage()
                val locale = getLocaleForLanguage()
                val normalizedWord = word.lowercase(locale).trim()
                val cacheKey = buildCacheKey(normalizedWord, currentLang)

                dictionaryCache.invalidate(cacheKey)
                suggestionCache.invalidateAll()
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
         * Clears entire suggestion cache since cached prefix lists may contain this word.
         */
        fun blacklistSuggestion(word: String) {
            try {
                val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()

                synchronized(blacklistedWords) {
                    blacklistedWords.add(normalizedWord)
                }

                val currentLang = getCurrentLanguage()
                val cacheKey = buildCacheKey(normalizedWord, currentLang)
                dictionaryCache.invalidate(cacheKey)
                suggestionCache.invalidateAll()
                commonWordsCache = emptyList()
                commonWordsCacheIndexed = emptyList()
            } catch (_: Exception) {
            }
        }

        /**
         * Removes word from blacklist, allowing it in suggestions again.
         * Clears entire suggestion cache since cached prefix lists excluded this word.
         */
        fun removeFromBlacklist(word: String) {
            try {
                val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()

                var removed = false
                synchronized(blacklistedWords) {
                    removed = blacklistedWords.remove(normalizedWord)
                }

                if (removed) {
                    val currentLang = getCurrentLanguage()
                    val cacheKey = buildCacheKey(normalizedWord, currentLang)
                    dictionaryCache.invalidate(cacheKey)
                    suggestionCache.invalidateAll()
                    commonWordsCache = emptyList()
                    commonWordsCacheIndexed = emptyList()
                }
            } catch (_: Exception) {
            }
        }

        fun isWordBlacklisted(word: String): Boolean =
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

        override fun onMemoryPressure(level: Int) {
            when (level) {
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                -> {
                    wordFrequencies.clear()
                    commonWordsCache = emptyList()
                    commonWordsCacheIndexed = emptyList()
                    commonWordsCacheLanguage = ""
                    clearCaches()
                }

                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
                android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE,
                -> {
                    commonWordsCache = emptyList()
                    commonWordsCacheIndexed = emptyList()
                    commonWordsCacheLanguage = ""
                }
            }
        }

        /**
         * Loads dictionary words sorted by frequency.
         *
         * Cached per language to avoid redundant file I/O.
         * Used for swipe word candidate generation and prefix completions.
         * @return List of (word, frequency) pairs
         */
        suspend fun getCommonWords(languageCode: String? = null): List<Pair<String, Int>> =
            withContext(ioDispatcher) {
                try {
                    if (!ensureInitialized()) {
                        return@withContext emptyList()
                    }

                    val targetLang = languageCode ?: getCurrentLanguage()
                    if (targetLang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                        return@withContext emptyList()
                    }

                    if (targetLang == commonWordsCacheLanguage && commonWordsCache.isNotEmpty()) {
                        return@withContext commonWordsCache
                    }

                    val dictionaryFile = "dictionaries/${targetLang}_symspell.txt"
                    val wordFrequencies = mutableListOf<Pair<String, Int>>()

                    try {
                        context.assets.open(dictionaryFile).bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                parseDictionaryLine(line)?.let { wordFrequency ->
                                    wordFrequencies.add(wordFrequency)
                                }
                            }
                        }
                    } catch (_: IOException) {
                        return@withContext emptyList()
                    }

                    val sortedWords = wordFrequencies.sortedByDescending { it.second }

                    val sortedWordsWithStripped =
                        sortedWords.map { (word, freq) ->
                            CachedWord(
                                word = word,
                                frequency = freq,
                                strippedWord =
                                    com.urik.keyboard.utils.TextMatchingUtils
                                        .stripWordPunctuation(word),
                                accentStrippedWord = wordNormalizer.stripDiacritics(word).lowercase(),
                            )
                        }

                    commonWordsCache = sortedWords
                    commonWordsCacheIndexed = sortedWordsWithStripped
                    commonWordsCacheLanguage = targetLang

                    return@withContext sortedWords
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }

        suspend fun getCommonWordsForLanguages(languages: List<String>): Map<String, Int> =
            withContext(ioDispatcher) {
                try {
                    if (!ensureInitialized() || languages.isEmpty()) {
                        return@withContext emptyMap()
                    }

                    val mergedWords = mutableMapOf<String, Int>()

                    languages.forEach { lang ->
                        if (lang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                            return@forEach
                        }

                        val dictionaryFile = "dictionaries/${lang}_symspell.txt"

                        try {
                            context.assets.open(dictionaryFile).bufferedReader().use { reader ->
                                reader.forEachLine { line ->
                                    parseDictionaryLine(line)?.let { (word, frequency) ->
                                        val currentFreq = mergedWords[word] ?: 0
                                        mergedWords[word] = maxOf(currentFreq, frequency)
                                    }
                                }
                            }
                        } catch (_: IOException) {
                        }
                    }

                    return@withContext mergedWords
                } catch (_: Exception) {
                    return@withContext emptyMap()
                }
            }

        suspend fun getWordsByPrefix(
            prefix: String,
            languages: List<String>,
            maxResults: Int,
        ): List<Pair<String, Int>> {
            if (prefix.length < 2) return emptyList()

            val dictionary = getCommonWordsForLanguages(languages)
            val lowerPrefix = prefix.lowercase()

            return dictionary.entries
                .asSequence()
                .filter { (word, _) ->
                    word.startsWith(lowerPrefix) && word.length > prefix.length
                }.sortedByDescending { it.value }
                .take(maxResults)
                .map { it.key to it.value }
                .toList()
        }

        private fun calculateProximityBonus(
            char1: Char,
            char2: Char,
        ): Double {
            if (char1 == char2) return 0.0

            val keyPositions = cachedKeyPositions
            if (keyPositions.isEmpty()) return 0.0

            val avgKeySpacing = cachedAverageKeySpacing
            if (avgKeySpacing <= 0.0) return 0.0

            val pos1 = keyPositions[char1.lowercaseChar()] ?: return 0.0
            val pos2 = keyPositions[char2.lowercaseChar()] ?: return 0.0

            val dx = pos1.x - pos2.x
            val dy = pos1.y - pos2.y
            val distanceSquared = dx * dx + dy * dy

            val sigma = avgKeySpacing * PROXIMITY_SIGMA_MULTIPLIER
            val proximityScore = kotlin.math.exp(-distanceSquared / (2 * sigma * sigma))

            return proximityScore * PROXIMITY_MAX_BONUS
        }

        private fun calculateAverageKeySpacing(keyPositions: Map<Char, android.graphics.PointF>): Double {
            if (keyPositions.size < 2) return 0.0

            val positions = keyPositions.values
            var totalDistanceSquared = 0.0
            var count = 0

            val posArray = positions.toTypedArray()
            for (i in posArray.indices) {
                val end = minOf(i + 4, posArray.size)
                for (j in i + 1 until end) {
                    val dx = posArray[i].x - posArray[j].x
                    val dy = posArray[i].y - posArray[j].y
                    totalDistanceSquared += (dx * dx + dy * dy).toDouble()
                    count++
                }
            }

            return if (count > 0) kotlin.math.sqrt(totalDistanceSquared / count) else 0.0
        }

        private fun calculateFrequencyBoost(frequency: Int): Double {
            if (frequency <= 0) return 0.0

            return when {
                frequency >= HIGH_FREQUENCY_THRESHOLD -> {
                    ln(frequency.toDouble()) * HIGH_FREQUENCY_LOG_MULTIPLIER +
                        HIGH_FREQUENCY_BASE_BOOST
                }

                frequency >= MEDIUM_FREQUENCY_THRESHOLD -> {
                    ln(frequency.toDouble()) * MEDIUM_FREQUENCY_LOG_MULTIPLIER +
                        MEDIUM_FREQUENCY_BASE_BOOST
                }

                else -> {
                    ln(frequency.toDouble() + 1.0) * FREQUENCY_BOOST_MULTIPLIER
                }
            }
        }

        private companion object {
            const val SUGGESTION_CACHE_SIZE = 500
            const val DICTIONARY_CACHE_SIZE = 1000

            const val MAX_EDIT_DISTANCE = 2.0
            const val PREFIX_LENGTH = 7
            const val COUNT_THRESHOLD = 1L
            const val TOP_K = 100
            const val MAX_SUGGESTIONS = 5
            const val MIN_COMPLETION_LENGTH = 4
            const val APOSTROPHE_BOOST = 0.30
            const val DIACRITIC_PROMOTION_BOOST = 0.08
            const val CONTRACTION_GUARANTEED_CONFIDENCE = 0.995

            const val DICTIONARY_BATCH_SIZE = 2000
            const val INITIALIZATION_TIMEOUT_MS = 5000L

            const val FREQUENCY_BOOST_MULTIPLIER = 0.02
            const val LEARNED_WORD_BASE_CONFIDENCE = 0.95
            const val LEARNED_WORD_CONFIDENCE_MIN = 0.85
            const val LEARNED_WORD_CONFIDENCE_MAX = 0.99

            const val MAX_PREFIX_COMPLETIONS = 5
            const val FREQUENCY_SCORE_DIVISOR = 15.0
            const val COMPLETION_LENGTH_WEIGHT = 0.70
            const val COMPLETION_FREQUENCY_WEIGHT = 0.30
            const val COMPLETION_CONFIDENCE_MIN = 0.50

            const val SYMSPELL_DISTANCE_WEIGHT = 0.45
            const val SYMSPELL_FREQUENCY_WEIGHT = 0.05
            const val SYMSPELL_CONFIDENCE_MIN = 0.0
            const val MAX_DICT_FREQUENCY = 30_000_000.0

            const val SAME_LENGTH_BONUS = 0.10
            const val SAME_FIRST_LETTER_BONUS = 0.15
            const val SAME_LAST_LETTER_BONUS = 0.10

            const val PROXIMITY_MAX_BONUS = 0.20
            const val PROXIMITY_SIGMA_MULTIPLIER = 2.0

            const val MAX_PREFIX_COMPLETION_RESULTS = 10
            const val MAX_INPUT_CODEPOINTS = 100

            const val COMMON_WORD_MIN_LENGTH = 2
            const val COMMON_WORD_MAX_LENGTH = 15

            const val HIGH_FREQUENCY_THRESHOLD = 10
            const val MEDIUM_FREQUENCY_THRESHOLD = 3
            const val HIGH_FREQUENCY_BASE_BOOST = 0.15
            const val HIGH_FREQUENCY_LOG_MULTIPLIER = 0.04
            const val MEDIUM_FREQUENCY_BASE_BOOST = 0.05
            const val MEDIUM_FREQUENCY_LOG_MULTIPLIER = 0.03
        }
    }
