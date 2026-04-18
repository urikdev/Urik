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
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
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
    val preserveCase: Boolean = false
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MemoryPressureSubscriber {
    private val initializationComplete = CompletableDeferred<Boolean>()
    private var initializationJob: Job? = null
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val spellCheckers = ConcurrentHashMap<String, SpellChecker>()
    private val spellCheckerAccessOrder = ConcurrentHashMap<String, Long>()
    private var currentLanguage: String = "en"

    @Volatile private var cachedLocale: Locale = Locale.forLanguageTag("en")

    private val wordFrequencies = ConcurrentHashMap<String, Long>()

    private val parsedDictionaryWords = ConcurrentHashMap<String, List<Pair<String, Int>>>()
    private val indexedDictionaryWords = ConcurrentHashMap<String, List<CachedWord>>()

    private val suggestionCache: ManagedCache<String, List<SpellingSuggestion>> =
        cacheMemoryManager.createCache(
            name = "spell_suggestions",
            maxSize = SUGGESTION_CACHE_SIZE
        )

    private val dictionaryCache: ManagedCache<String, Boolean> =
        cacheMemoryManager.createCache(
            name = "dictionary_cache",
            maxSize = DICTIONARY_CACHE_SIZE
        )

    private val blacklistedWords = ConcurrentHashMap.newKeySet<String>()

    private data class CachedWord(
        val word: String,
        val frequency: Int,
        val strippedWord: String,
        val accentStrippedWord: String
    )

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
                            context = mapOf("phase" to "initialization")
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

    private suspend fun ensureInitialized(): Boolean = withTimeoutOrNull(INITIALIZATION_TIMEOUT_MS) {
        initializationComplete.await()
    } ?: run {
        ErrorLogger.logException(
            component = "SpellCheckManager",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = TimeoutException("Initialization timeout after ${INITIALIZATION_TIMEOUT_MS}ms"),
            context = mapOf("phase" to "ensureInitialized")
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
                error("Failed to create spell checker")
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
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("phase" to "language_preload", "language" to languageCode)
            )
        }
    }

    private suspend fun checkSymSpellDictionary(normalizedWord: String, languageCode: String): Boolean {
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

    suspend fun isWordInSymSpellDictionary(word: String): Boolean = withContext(Dispatchers.Default) {
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

    private suspend fun ensureDictionaryParsed(languageCode: String) {
        if (parsedDictionaryWords.containsKey(languageCode)) return
        getSpellCheckerForLanguage(languageCode)
    }

    private suspend fun createSpellChecker(context: Context, languageCode: String): SpellChecker? = coroutineScope {
        try {
            val settings =
                SpellCheckSettings(
                    maxEditDistance = MAX_EDIT_DISTANCE,
                    prefixLength = PREFIX_LENGTH,
                    countThreshold = COUNT_THRESHOLD,
                    topK = TOP_K
                )

            val symSpell = SymSpell(settings)
            val dictionaryFile = "dictionaries/${languageCode}_symspell.txt"
            val inputStream = context.assets.open(dictionaryFile)
            val collectedWords = ArrayList<Pair<String, Int>>(INITIAL_WORD_LIST_CAPACITY)

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
                                collectedWords.add(word.lowercase() to frequency.toInt())
                            } else if (parts.size == 1) {
                                wordFrequencies[parts[0].lowercase()] = 1L
                                symSpell.createDictionaryEntry(parts[0], 1)
                                collectedWords.add(parts[0].lowercase() to 1)
                            }
                        }
                    }

                    yield()
                }

                val sorted = collectedWords.sortedByDescending { it.second }
                parsedDictionaryWords[languageCode] = sorted
                indexedDictionaryWords[languageCode] =
                    sorted.map { (word, freq) ->
                        CachedWord(
                            word = word,
                            frequency = freq,
                            strippedWord =
                            com.urik.keyboard.utils.TextMatchingUtils
                                .stripWordPunctuation(word),
                            accentStrippedWord = wordNormalizer.stripDiacritics(word).lowercase()
                        )
                    }

                evictExcessSpellCheckers(languageCode)
                spellCheckerAccessOrder[languageCode] = System.nanoTime()

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
                    "language" to languageCode
                )
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
                    "language" to languageCode
                )
            )
            null
        }
    }

    private fun evictExcessSpellCheckers(preserveLanguage: String) {
        if (spellCheckers.size < MAX_CACHED_SPELL_CHECKERS) return

        val evictionTarget =
            spellCheckerAccessOrder.entries
                .filter { it.key != preserveLanguage && it.key != currentLanguage }
                .minByOrNull { it.value }
                ?.key ?: return

        spellCheckers.remove(evictionTarget)
        spellCheckerAccessOrder.remove(evictionTarget)
        parsedDictionaryWords.remove(evictionTarget)
        indexedDictionaryWords.remove(evictionTarget)
    }

    private suspend fun getSpellCheckerForLanguage(languageCode: String): SpellChecker? {
        spellCheckers[languageCode]?.let {
            spellCheckerAccessOrder[languageCode] = System.nanoTime()
            return it
        }

        if (!isInitialized || languageCode !in KeyboardSettings.SUPPORTED_LANGUAGES) {
            return null
        }

        return createSpellChecker(context, languageCode)?.also { newChecker ->
            spellCheckers.putIfAbsent(languageCode, newChecker)
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
    suspend fun isWordInDictionary(word: String): Boolean = withContext(Dispatchers.Default) {
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

    private suspend fun isWordInDictionary(normalizedWord: String, languageCode: String): Boolean {
        val isLearned = wordLearningEngine.isWordLearned(normalizedWord)
        if (isLearned) {
            return true
        }

        if (checkSymSpellDictionary(normalizedWord, languageCode)) {
            return true
        }

        return isCliticFormValid(normalizedWord, languageCode)
    }

    private suspend fun isCliticFormValid(normalizedWord: String, languageCode: String): Boolean {
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

    fun getDominantContractionForm(normalizedWord: String): String? {
        val canonical = wordNormalizer.canonicalizeApostrophes(normalizedWord)
        if (canonical.contains('\'')) return null

        val wordFreq = wordFrequencies[canonical] ?: return null

        for (i in 1 until canonical.length) {
            val candidate = canonical.substring(0, i) + "'" + canonical.substring(i)
            val contractionFreq = wordFrequencies[candidate] ?: continue
            if (contractionFreq >= wordFreq * CONTRACTION_DOMINANCE_RATIO) {
                return candidate
            }
        }
        return null
    }

    suspend fun hasDominantContractionForm(normalizedWord: String): Boolean {
        val canonical = wordNormalizer.canonicalizeApostrophes(normalizedWord)
        if (canonical.contains('\'')) return false

        val dictFreq = wordFrequencies[canonical] ?: return false

        val currentLang = getCurrentLanguage()
        val userFreq = try {
            wordFrequencyRepository.getFrequency(canonical, currentLang)
        } catch (_: Exception) {
            0
        }

        val effectiveBaseFreq = dictFreq + userFreq * USER_FREQ_CONTRACTION_WEIGHT

        for (i in 1 until canonical.length) {
            val candidate = canonical.substring(0, i) + "'" + canonical.substring(i)
            val contractionFreq = wordFrequencies[candidate] ?: continue
            if (contractionFreq >= effectiveBaseFreq * CONTRACTION_DOMINANCE_RATIO) {
                return true
            }
        }
        return false
    }

    /**
     * Batch dictionary check for multiple words.
     *
     * Uses single batch query to WordLearningEngine for learned words.
     *
     * @return Map of original word → validity
     */
    suspend fun areWordsInDictionary(words: List<String>): Map<String, Boolean> = withContext(Dispatchers.Default) {
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
    suspend fun generateSuggestions(word: String, maxSuggestions: Int = 3): List<String> {
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
        activeLanguages: List<String>
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
        activeLanguages: List<String>
    ): List<SpellingSuggestion> = coroutineScope {
        try {
            val allLanguageSuggestions =
                activeLanguages
                    .map { lang ->
                        async(Dispatchers.Default) {
                            querySingleLanguage(normalizedWord, lang)
                        }
                    }.awaitAll()
                    .flatten()

            mergeAndRankSuggestions(allLanguageSuggestions, MAX_SUGGESTIONS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun querySingleLanguage(normalizedWord: String, languageCode: String): List<SpellingSuggestion> {
        try {
            val allSuggestions = mutableListOf<SpellingSuggestion>()
            val seenWords = mutableSetOf<String>()

            try {
                val learnedSuggestions =
                    wordLearningEngine.getSimilarLearnedWordsWithFrequency(
                        normalizedWord,
                        languageCode,
                        maxResults = 5
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
                                val spatialScore = if (languageCode == "ja") {
                                    0.0
                                } else {
                                    calculateFullWordSpatialScore(normalizedWord, word.lowercase())
                                }
                                val baseConfidence = LEARNED_WORD_BASE_CONFIDENCE - index * 0.02
                                (baseConfidence + spatialScore * LEARNED_SPATIAL_WEIGHT + frequencyBoost).coerceIn(
                                    LEARNED_WORD_CONFIDENCE_MIN,
                                    LEARNED_WORD_CONFIDENCE_MAX
                                )
                            }

                        allSuggestions.add(
                            SpellingSuggestion(
                                word = word,
                                confidence = confidence,
                                ranking = index,
                                source = "learned",
                                preserveCase = true
                            )
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
                            .filter { (word, _) ->
                                !seenWords.contains(word.lowercase()) && !isWordBlacklisted(word)
                            }
                            .take(MAX_PREFIX_COMPLETIONS)

                    val userFrequencies =
                        try {
                            wordFrequencyRepository.getFrequencies(
                                filteredCompletions.map { it.first },
                                languageCode
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

                                    val spatialScore = if (languageCode == "ja") {
                                        0.0
                                    } else {
                                        calculateFullWordSpatialScore(
                                            normalizedWord,
                                            word.lowercase()
                                        )
                                    }
                                    baseConfidence += spatialScore * COMPLETION_SPATIAL_WEIGHT

                                    if (userFrequency > 0) {
                                        val userFreqBoost = calculateFrequencyBoost(userFrequency)
                                        baseConfidence += userFreqBoost
                                    }

                                    baseConfidence.coerceIn(
                                        COMPLETION_CONFIDENCE_MIN,
                                        0.99
                                    )
                                }

                            allSuggestions.add(
                                SpellingSuggestion(
                                    word = word,
                                    confidence = confidence,
                                    ranking = allSuggestions.size,
                                    source = "completion"
                                )
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
                                languageCode
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
                                        val strippedResult =
                                            com.urik.keyboard.utils.TextMatchingUtils
                                                .stripWordPunctuation(result.term)
                                        val isContractionCandidate = strippedResult != result.term

                                        val effectiveDistance =
                                            if (isContractionCandidate && editDistance >= 2.0) {
                                                (editDistance - 1.0).coerceAtLeast(0.0)
                                            } else {
                                                editDistance
                                            }

                                        val distanceScore = (maxDistance - effectiveDistance) / maxDistance
                                        val freqScore = ln(frequency.toDouble() + 1.0) / ln(MAX_DICT_FREQUENCY)
                                        var baseConfidence =
                                            SYMSPELL_DISTANCE_WEIGHT * distanceScore +
                                                SYMSPELL_FREQUENCY_WEIGHT * freqScore

                                        val spatialScore =
                                            if (languageCode == "ja") {
                                                0.0
                                            } else {
                                                calculateFullWordSpatialScore(
                                                    normalizedWord,
                                                    result.term.lowercase()
                                                )
                                            }
                                        baseConfidence += spatialScore * SPATIAL_PROXIMITY_WEIGHT

                                        if (isContractionCandidate && editDistance == 1.0) {
                                            baseConfidence += APOSTROPHE_BOOST
                                        }

                                        val termStripped = wordNormalizer.stripDiacritics(result.term.lowercase())
                                        if (inputAccentStripped == termStripped &&
                                            normalizedWord != result.term.lowercase()
                                        ) {
                                            baseConfidence += DIACRITIC_PROMOTION_BOOST
                                        }

                                        if (userFrequency > 0) {
                                            val userFreqBoost = calculateFrequencyBoost(userFrequency)
                                            baseConfidence += userFreqBoost
                                        }

                                        if (strippedResult.length < normalizedWord.length) {
                                            val lengthRatio =
                                                strippedResult.length.toDouble() / normalizedWord.length
                                            if (lengthRatio < MINIMUM_LENGTH_RATIO) {
                                                baseConfidence *= lengthRatio
                                            }
                                        }

                                        baseConfidence.coerceIn(
                                            SYMSPELL_CONFIDENCE_MIN,
                                            0.99
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
                                source = "symspell"
                            )
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
        maxResults: Int
    ): List<SpellingSuggestion> {
        val promoted = promoteContractionForms(suggestions)

        if (promoted.size <= maxResults) {
            val hasDuplicates = promoted.map { it.word }.toSet().size < promoted.size
            if (!hasDuplicates) return promoted
        }
        return promoted
            .groupBy { it.word }
            .mapValues { (_, dupes) -> dupes.maxBy { it.confidence } }
            .values
            .sortedByDescending { it.confidence }
            .take(maxResults)
    }

    private fun promoteContractionForms(suggestions: List<SpellingSuggestion>): List<SpellingSuggestion> {
        var anyPromoted = false
        val result = suggestions.map { suggestion ->
            val contraction = getDominantContractionForm(suggestion.word)
            if (contraction != null) {
                anyPromoted = true
                suggestion.copy(word = contraction)
            } else {
                suggestion
            }
        }
        return if (anyPromoted) result else suggestions
    }

    private suspend fun getCompletionsForPrefix(prefix: String, languageCode: String): List<Pair<String, Int>> {
        ensureDictionaryParsed(languageCode)
        val indexed = indexedDictionaryWords[languageCode] ?: return emptyList()

        val hasApostrophe = prefix.contains('\'')

        val apostropheMatches =
            if (hasApostrophe) {
                indexed
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
            indexed
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
            indexed
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

    private fun getCurrentLanguage(): String = try {
        val currentLanguage = languageManager.currentLanguage.value
        currentLanguage.split("-").first()
    } catch (_: Exception) {
        "en"
    }

    private fun getLocaleForLanguage(): Locale {
        val lang = languageManager.currentLanguage.value
        if (lang != currentLanguage) {
            currentLanguage = lang
            cachedLocale = try {
                Locale.forLanguageTag(lang)
            } catch (_: Exception) {
                Locale.forLanguageTag("en")
            }
        }
        return cachedLocale
    }

    private fun buildCacheKey(word: String, language: String): String = "${language}_$word"

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
    suspend fun removeSuggestion(word: String): Result<Boolean> = withContext(ioDispatcher) {
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

            blacklistedWords.add(normalizedWord)

            val currentLang = getCurrentLanguage()
            val cacheKey = buildCacheKey(normalizedWord, currentLang)
            dictionaryCache.invalidate(cacheKey)
            suggestionCache.invalidateAll()
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

            val removed = blacklistedWords.remove(normalizedWord)

            if (removed) {
                val currentLang = getCurrentLanguage()
                val cacheKey = buildCacheKey(normalizedWord, currentLang)
                dictionaryCache.invalidate(cacheKey)
                suggestionCache.invalidateAll()
            }
        } catch (_: Exception) {
        }
    }

    fun isWordBlacklisted(word: String): Boolean = try {
        val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()
        normalizedWord in blacklistedWords
    } catch (_: Exception) {
        false
    }

    fun clearBlacklist() {
        blacklistedWords.clear()
    }

    override fun onMemoryPressure(level: Int) {
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            -> {
                wordFrequencies.clear()
                clearCaches()

                val keepLanguage = currentLanguage
                val toEvict = spellCheckers.keys.filter { it != keepLanguage }
                toEvict.forEach { lang ->
                    spellCheckers.remove(lang)
                    spellCheckerAccessOrder.remove(lang)
                    parsedDictionaryWords.remove(lang)
                    indexedDictionaryWords.remove(lang)
                }
            }

            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
            -> {
                val activeLangs = languageManager.activeLanguages.value.toSet()
                val toEvict = parsedDictionaryWords.keys.filter { it !in activeLangs }
                toEvict.forEach { lang ->
                    parsedDictionaryWords.remove(lang)
                    indexedDictionaryWords.remove(lang)
                    spellCheckers.remove(lang)
                    spellCheckerAccessOrder.remove(lang)
                }
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
    suspend fun getCommonWords(languageCode: String? = null): List<Pair<String, Int>> = withContext(ioDispatcher) {
        try {
            if (!ensureInitialized()) {
                return@withContext emptyList()
            }

            val targetLang = languageCode ?: getCurrentLanguage()
            if (targetLang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                return@withContext emptyList()
            }

            if (parsedDictionaryWords[targetLang] == null) {
                ensureDictionaryParsed(targetLang)
            }

            return@withContext parsedDictionaryWords[targetLang]
                ?.filter { !isWordBlacklisted(it.first) }
                ?: emptyList()
        } catch (_: Exception) {
            return@withContext emptyList()
        }
    }

    suspend fun getCommonWordsForLanguages(languages: List<String>): Map<String, Int> = withContext(ioDispatcher) {
        try {
            if (!ensureInitialized() || languages.isEmpty()) {
                return@withContext emptyMap()
            }

            val mergedWords = HashMap<String, Int>(INITIAL_WORD_LIST_CAPACITY)

            languages.forEach { lang ->
                if (lang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                    return@forEach
                }

                ensureDictionaryParsed(lang)
                parsedDictionaryWords[lang]?.forEach { (word, frequency) ->
                    if (!isWordBlacklisted(word)) {
                        val currentFreq = mergedWords[word] ?: 0
                        mergedWords[word] = maxOf(currentFreq, frequency)
                    }
                }
            }

            return@withContext mergedWords
        } catch (_: Exception) {
            return@withContext emptyMap()
        }
    }

    private fun calculateFullWordSpatialScore(input: String, candidate: String): Double {
        val keyPositions = cachedKeyPositions
        if (keyPositions.isEmpty()) return 0.0

        val avgKeySpacing = cachedAverageKeySpacing
        if (avgKeySpacing <= 0.0) return 0.0

        val sigma = avgKeySpacing * PROXIMITY_SIGMA_MULTIPLIER
        val twoSigmaSquared = 2.0 * sigma * sigma

        if (input.length == candidate.length) {
            return scoreSameLengthAlignment(input, candidate, keyPositions, twoSigmaSquared)
        }

        if (input.length > candidate.length) {
            return scoreDifferentLengthAlignment(input, candidate, keyPositions, twoSigmaSquared)
        }

        return scorePrefixWithLookahead(input, candidate, keyPositions, twoSigmaSquared, avgKeySpacing)
    }

    private fun scorePrefixWithLookahead(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double,
        avgKeySpacing: Double
    ): Double {
        if (input.isEmpty()) return 0.0

        val lengthDiff = candidate.length - input.length
        val typedScore = if (lengthDiff <= 2) {
            maxOf(
                scoreDifferentLengthAlignment(input, candidate, keyPositions, twoSigmaSquared),
                scoreDirectPrefix(input, candidate, keyPositions, twoSigmaSquared)
            )
        } else {
            scoreDirectPrefix(input, candidate, keyPositions, twoSigmaSquared)
        }

        val lookaheadSigma = avgKeySpacing * LOOKAHEAD_SIGMA_MULTIPLIER
        val lookaheadTwoSigmaSquared = 2.0 * lookaheadSigma * lookaheadSigma

        var lookaheadScoreSum = 0.0
        var lookaheadWeightSum = 0.0
        for (i in input.length until candidate.length) {
            val positionsAhead = i - input.length
            val decayIndex = minOf(positionsAhead, LOOKAHEAD_DECAY_TABLE.lastIndex)
            val weight = LOOKAHEAD_BASE_WEIGHT * LOOKAHEAD_DECAY_TABLE[decayIndex]
            val transitionScore = scoreCharPair(
                candidate[i - 1],
                candidate[i],
                keyPositions,
                lookaheadTwoSigmaSquared
            )
            lookaheadScoreSum += transitionScore * weight
            lookaheadWeightSum += weight
        }

        val typedWeight = input.length.toDouble()
        val totalWeight = typedWeight + lookaheadWeightSum
        return (typedScore * typedWeight + lookaheadScoreSum) / totalWeight
    }

    private fun scoreDirectPrefix(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        var totalScore = 0.0
        for (i in input.indices) {
            totalScore += scoreCharPair(input[i], candidate[i], keyPositions, twoSigmaSquared)
        }
        return if (input.isNotEmpty()) totalScore / input.length else 0.0
    }

    private fun scoreSameLengthAlignment(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        var totalScore = 0.0
        for (i in input.indices) {
            totalScore += scoreCharPair(input[i], candidate[i], keyPositions, twoSigmaSquared)
        }
        return totalScore / input.length
    }

    private fun scoreDifferentLengthAlignment(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        val shorter: String
        val longer: String
        if (input.length < candidate.length) {
            shorter = input
            longer = candidate
        } else {
            shorter = candidate
            longer = input
        }

        val skipBudget = longer.length - shorter.length
        var bestScore = 0.0

        if (skipBudget == 1) {
            for (skipPos in 0 until longer.length) {
                val score = scoreWithSkips(shorter, longer, keyPositions, twoSigmaSquared, skipPos, -1)
                if (score > bestScore) bestScore = score
            }
        } else if (skipBudget == 2) {
            val totalLength = shorter.length + skipBudget
            for (i in 0 until totalLength) {
                for (j in i + 1 until totalLength) {
                    val score = scoreWithSkips(shorter, longer, keyPositions, twoSigmaSquared, i, j)
                    if (score > bestScore) bestScore = score
                }
            }
        }

        return bestScore
    }

    private fun scoreWithSkips(
        shorter: String,
        longer: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double,
        skip1: Int,
        skip2: Int
    ): Double {
        var totalScore = 0.0
        var shortIdx = 0
        for (longIdx in longer.indices) {
            if (longIdx == skip1 || longIdx == skip2) continue
            if (shortIdx >= shorter.length) break
            totalScore += scoreCharPair(shorter[shortIdx], longer[longIdx], keyPositions, twoSigmaSquared)
            shortIdx++
        }
        return if (shorter.isNotEmpty()) totalScore / shorter.length else 0.0
    }

    private fun scoreCharPair(
        inputChar: Char,
        candidateChar: Char,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        val ic = inputChar.lowercaseChar()
        val cc = candidateChar.lowercaseChar()

        if (ic == cc) return 1.0

        if (ic.isDigit() || cc.isDigit()) return 0.0

        val pos1 = keyPositions[ic] ?: return 0.0
        val pos2 = keyPositions[cc] ?: return 0.0

        val dx = (pos1.x - pos2.x).toDouble()
        val dy = (pos1.y - pos2.y).toDouble()
        val distanceSquared = dx * dx + dy * dy

        return kotlin.math.exp(-distanceSquared / twoSigmaSquared)
    }

    private fun calculateAverageKeySpacing(keyPositions: Map<Char, android.graphics.PointF>): Double {
        if (keyPositions.size < 2) return 0.0

        val posArray = keyPositions.values.toTypedArray()
        var totalMinDistance = 0.0

        for (i in posArray.indices) {
            var minDistSq = Double.MAX_VALUE
            for (j in posArray.indices) {
                if (i == j) continue
                val dx = (posArray[i].x - posArray[j].x).toDouble()
                val dy = (posArray[i].y - posArray[j].y).toDouble()
                val distSq = dx * dx + dy * dy
                if (distSq < minDistSq) minDistSq = distSq
            }
            if (minDistSq != Double.MAX_VALUE) totalMinDistance += kotlin.math.sqrt(minDistSq)
        }

        return totalMinDistance / posArray.size
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
        const val CONTRACTION_DOMINANCE_RATIO = 20L
        const val USER_FREQ_CONTRACTION_WEIGHT = 300L

        const val DICTIONARY_BATCH_SIZE = 2000
        const val INITIAL_WORD_LIST_CAPACITY = 50000
        const val MAX_CACHED_SPELL_CHECKERS = 4
        const val INITIALIZATION_TIMEOUT_MS = 5000L

        const val FREQUENCY_BOOST_MULTIPLIER = 0.02
        const val LEARNED_WORD_BASE_CONFIDENCE = 0.60
        const val LEARNED_WORD_CONFIDENCE_MIN = 0.30
        const val LEARNED_SPATIAL_WEIGHT = 0.35
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

        const val SPATIAL_PROXIMITY_WEIGHT = 0.35
        const val PROXIMITY_SIGMA_MULTIPLIER = 2.0
        const val MINIMUM_LENGTH_RATIO = 0.6

        const val LOOKAHEAD_BASE_WEIGHT = 0.5
        const val LOOKAHEAD_DECAY = 0.7
        const val LOOKAHEAD_SIGMA_MULTIPLIER = 4.0
        const val COMPLETION_SPATIAL_WEIGHT = 0.15
        private const val MAX_LOOKAHEAD_POSITIONS = 20

        @JvmField
        val LOOKAHEAD_DECAY_TABLE = DoubleArray(MAX_LOOKAHEAD_POSITIONS) { i ->
            var result = 1.0
            repeat(i) { result *= LOOKAHEAD_DECAY }
            result
        }

        const val MAX_PREFIX_COMPLETION_RESULTS = 10
        const val MAX_INPUT_CODEPOINTS = 100

        const val HIGH_FREQUENCY_THRESHOLD = 10
        const val MEDIUM_FREQUENCY_THRESHOLD = 3
        const val HIGH_FREQUENCY_BASE_BOOST = 0.15
        const val HIGH_FREQUENCY_LOG_MULTIPLIER = 0.04
        const val MEDIUM_FREQUENCY_BASE_BOOST = 0.05
        const val MEDIUM_FREQUENCY_LOG_MULTIPLIER = 0.03
    }
}
