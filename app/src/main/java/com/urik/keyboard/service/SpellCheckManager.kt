@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.dictionary.LevenshteinAutomaton
import com.urik.keyboard.dictionary.UrikDictionary
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import com.urik.keyboard.utils.MemoryPressureSubscriber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spelling suggestion with confidence score.
 *
 * @property source "learned" (user data), "dictionary" (built-in), or "completion" (predictive)
 */
data class SpellingSuggestion(
    val word: String,
    val confidence: Double,
    val ranking: Int,
    val source: String = "unknown",
    val preserveCase: Boolean = false
)

/**
 * Spell checking and suggestion generation using URIK dictionary format.
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
    private val fatFingerExpander: FatFingerExpander = FatFingerExpander()
) : MemoryPressureSubscriber {
    private val initializationComplete = CompletableDeferred<Boolean>()
    private var initializationJob: Job? = null
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val urikDictionaries = ConcurrentHashMap<String, UrikDictionary>()

    @Volatile private var currentLanguage: String = "en"

    @Volatile private var cachedLocale: Locale = Locale.forLanguageTag("en")

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

    @Volatile
    private var isInitialized = false

    @Volatile
    var isDegradedMode: Boolean = false
        private set

    @Volatile
    private var cachedKeyPositions = emptyMap<Char, android.graphics.PointF>()

    @Volatile
    private var cachedAverageKeySpacing = 0.0

    @Volatile
    private var cachedAdjacentKeyMap = emptyMap<Char, Set<Char>>()

    init {
        cacheMemoryManager.registerPressureSubscriber(this)

        initializationJob =
            initScope.launch {
                val success =
                    try {
                        withContext(ioDispatcher) {
                            initializeUrik()
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
                if (!success) {
                    isDegradedMode = true
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
                val avgSpacing =
                    if (positions.size >= 2) {
                        calculateAverageKeySpacing(positions)
                    } else {
                        0.0
                    }
                cachedAverageKeySpacing = avgSpacing
                cachedAdjacentKeyMap =
                    if (positions.isNotEmpty() && avgSpacing > 0.0) {
                        fatFingerExpander.buildAdjacentKeyMap(positions, avgSpacing)
                    } else {
                        emptyMap()
                    }
            }
        }
    }

    private suspend fun ensureInitialized(): Boolean = withTimeoutOrNull(INITIALIZATION_TIMEOUT_MS) {
        initializationComplete.await()
    } ?: run {
        isDegradedMode = true
        ErrorLogger.logException(
            component = "SpellCheckManager",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = TimeoutException("Initialization timeout after ${INITIALIZATION_TIMEOUT_MS}ms"),
            context = mapOf("phase" to "ensureInitialized")
        )
        false
    }

    private fun initializeUrik() {
        currentLanguage = getCurrentLanguage()
        val dict = loadUrikDictionary(currentLanguage)
        if (dict != null) {
            urikDictionaries[currentLanguage] = dict
            isInitialized = true
        } else {
            error("Failed to load URIK dictionary for $currentLanguage")
        }
    }

    private fun preloadLanguage(languageCode: String) {
        if (languageCode !in KeyboardSettings.SUPPORTED_LANGUAGES) return
        if (urikDictionaries[languageCode] == null) {
            loadUrikDictionary(languageCode)?.let { urikDictionaries[languageCode] = it }
        }
    }

    private fun loadUrikDictionary(languageCode: String): UrikDictionary? = try {
        val stream = context.assets.open("dictionaries/$languageCode.urik")
        UrikDictionary(stream)
    } catch (_: Exception) {
        null
    }

    private fun getUrikDictionary(languageCode: String): UrikDictionary? =
        urikDictionaries[languageCode] ?: loadUrikDictionary(languageCode)?.also {
            urikDictionaries[languageCode] = it
        }

    fun getDictFrequency(word: String, languageCode: String? = null): Long {
        val lang = languageCode ?: currentLanguage
        return getUrikDictionary(lang)?.getFrequency(word.lowercase().trim()) ?: 0L
    }

    suspend fun getUserFrequency(word: String, languageCode: String? = null): Int {
        val lang = languageCode ?: currentLanguage
        val normalized = word.lowercase().trim()
        return try {
            wordFrequencyRepository.getFrequency(normalized, lang)
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Lookup order: learned words → cache → URIK dictionary (edit distance = 0).
     * Word is normalized (lowercase, trimmed) before checking.
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "isWordInDictionary")
            )
            return@withContext false
        }
    }

    private suspend fun isWordInDictionary(normalizedWord: String, languageCode: String): Boolean {
        val isLearned = wordLearningEngine.isWordLearned(normalizedWord)
        if (isLearned) {
            dictionaryCache.put(buildCacheKey(normalizedWord, languageCode), true)
            return true
        }

        val cacheKey = buildCacheKey(normalizedWord, languageCode)
        dictionaryCache.getIfPresent(cacheKey)?.let { return it }

        val dict = getUrikDictionary(languageCode)
        if (dict != null && dict.lookup(normalizedWord)) {
            dictionaryCache.put(cacheKey, true)
            return true
        }

        val clitic = isCliticFormValid(normalizedWord, languageCode)
        dictionaryCache.put(cacheKey, clitic)
        return clitic
    }

    private suspend fun isCliticFormValid(normalizedWord: String, languageCode: String): Boolean {
        val apostropheIndex = normalizedWord.indexOf('\'')
        if (apostropheIndex <= 0 || apostropheIndex >= normalizedWord.length - 1) {
            return false
        }

        val prefix = normalizedWord.substring(0, apostropheIndex + 1)
        val suffix = normalizedWord.substring(apostropheIndex + 1)

        if (suffix.isBlank()) return false

        val dict = getUrikDictionary(languageCode)
        val prefixValid =
            wordLearningEngine.isWordLearned(prefix) ||
                dict != null &&
                dict.lookup(prefix)

        if (!prefixValid) return false

        val suffixValid =
            wordLearningEngine.isWordLearned(suffix) ||
                dict != null &&
                dict.lookup(suffix)

        if (suffixValid) {
            val cacheKey = buildCacheKey(normalizedWord, languageCode)
            dictionaryCache.put(cacheKey, true)
        }

        return suffixValid
    }

    fun getDominantContractionForm(normalizedWord: String, languageCode: String = currentLanguage): String? {
        val canonical = wordNormalizer.canonicalizeApostrophes(normalizedWord)
        if (canonical.contains('\'')) return null

        val dict = getUrikDictionary(languageCode) ?: return null
        val wordFreq = dict.getFrequency(canonical)
        if (wordFreq == 0L) return null

        for (i in 1 until canonical.length) {
            val candidate = canonical.substring(0, i) + "'" + canonical.substring(i)
            val contractionFreq = dict.getFrequency(candidate)
            if (contractionFreq > 0L && contractionFreq >= wordFreq * CONTRACTION_DOMINANCE_RATIO) {
                return candidate
            }
        }
        return null
    }

    suspend fun hasDominantContractionForm(normalizedWord: String, languageCode: String? = null): Boolean {
        val lang = languageCode ?: getCurrentLanguage()
        val canonical = wordNormalizer.canonicalizeApostrophes(normalizedWord)
        if (canonical.contains('\'')) return false

        val dict = getUrikDictionary(lang) ?: return false
        val dictFreq = dict.getFrequency(canonical)
        if (dictFreq == 0L) return false

        val userFreq = try {
            wordFrequencyRepository.getFrequency(canonical, lang)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "hasDominantContractionForm")
            )
            0
        }

        val effectiveBaseFreq = dictFreq + userFreq * USER_FREQ_CONTRACTION_WEIGHT

        for (i in 1 until canonical.length) {
            val candidate = canonical.substring(0, i) + "'" + canonical.substring(i)
            val contractionFreq = dict.getFrequency(candidate)
            if (contractionFreq > 0L && contractionFreq >= effectiveBaseFreq * CONTRACTION_DOMINANCE_RATIO) {
                return true
            }
        }
        return false
    }

    /** Uses single batch query to WordLearningEngine for learned words. */
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
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SpellCheckManager",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "areWordsInDictionary_learnedCheck")
                    )
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
                val dict = getUrikDictionary(lang)
                if (dict != null) {
                    val isInDictionary = dict.lookup(normalizedWord)
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

    /** Simpler API wrapping [getSpellingSuggestionsWithConfidence]. */
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "generateSuggestions")
            )
            emptyList()
        }
    }

    /**
     * Combines learned words + dictionary corrections + prefix completions, ranked by confidence.
     * Learned words boosted by frequency, corrections penalized by edit distance, completions by prefix match.
     * Cached (500 entries, LRU).
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
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SpellCheckManager",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "getSpellingSuggestionsWithConfidence")
                )
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
            val effectiveLanguage = getCurrentLanguage()
            val allLanguageSuggestions =
                activeLanguages
                    .map { lang ->
                        async(Dispatchers.Default) {
                            querySingleLanguage(normalizedWord, lang)
                        }
                    }.awaitAll()
                    .flatten()

            mergeAndRankSuggestions(allLanguageSuggestions, MAX_SUGGESTIONS, effectiveLanguage)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "requestCombinedSuggestions")
            )
            emptyList()
        }
    }

    private suspend fun querySingleLanguage(normalizedWord: String, languageCode: String): List<SpellingSuggestion> {
        try {
            val seenWords = mutableSetOf<String>()
            val allSuggestions = mutableListOf<SpellingSuggestion>()
            allSuggestions += queryLearnedSuggestions(normalizedWord, languageCode, seenWords)
            allSuggestions += queryCompletionSuggestions(normalizedWord, languageCode, seenWords)
            allSuggestions += queryUrikSuggestions(normalizedWord, languageCode, seenWords)
            return allSuggestions.sortedByDescending { it.confidence }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "querySingleLanguage")
            )
            return emptyList()
        }
    }

    private suspend fun queryLearnedSuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        try {
            val result = mutableListOf<SpellingSuggestion>()
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
                            val hasSpatialData = languageCode != "ja" && cachedKeyPositions.isNotEmpty()
                            val effectiveBase = if (hasSpatialData && spatialScore < SPATIAL_GATE_THRESHOLD) {
                                LEARNED_WORD_BASE_CONFIDENCE * (spatialScore / SPATIAL_GATE_THRESHOLD)
                            } else {
                                LEARNED_WORD_BASE_CONFIDENCE
                            }
                            val baseConfidence = effectiveBase - index * 0.02
                            (baseConfidence + spatialScore * LEARNED_SPATIAL_WEIGHT + frequencyBoost).coerceIn(
                                LEARNED_WORD_CONFIDENCE_MIN,
                                LEARNED_WORD_CONFIDENCE_MAX
                            )
                        }
                    result.add(
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
            return result
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "querySingleLanguage_learnedWords")
            )
            return emptyList()
        }
    }

    private suspend fun queryCompletionSuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        if (normalizedWord.length < MIN_COMPLETION_LENGTH) return emptyList()
        try {
            val result = mutableListOf<SpellingSuggestion>()
            val completions = getCompletionsForPrefix(normalizedWord, languageCode)
            val inputFreq = getUrikDictionary(languageCode)?.getFrequency(normalizedWord) ?: 0L
            val filteredCompletions =
                completions
                    .filter { (word, freq) ->
                        if (seenWords.contains(word.lowercase()) || isWordBlacklisted(word)) return@filter false
                        if (inputFreq > 0 &&
                            freq > 0 &&
                            inputFreq > freq * SUGGESTION_MAX_FREQUENCY_GAP
                        ) {
                            return@filter false
                        }
                        true
                    }
                    .take(MAX_PREFIX_COMPLETIONS)
            val userFrequencies =
                try {
                    wordFrequencyRepository.getFrequencies(
                        filteredCompletions.map { it.first },
                        languageCode
                    )
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SpellCheckManager",
                        severity = ErrorLogger.Severity.LOW,
                        exception = e,
                        context = mapOf("operation" to "querySingleLanguage_completionFrequencies")
                    )
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
                            val frequencyScore = ln(frequency.toDouble() + 1.0) / ln(MAX_DICT_FREQUENCY)
                            var baseConfidence =
                                COMPLETION_LENGTH_WEIGHT * lengthRatio +
                                    COMPLETION_FREQUENCY_WEIGHT * frequencyScore
                            val spatialScore = if (languageCode == "ja") {
                                0.0
                            } else {
                                calculateFullWordSpatialScore(normalizedWord, word.lowercase())
                            }
                            baseConfidence += spatialScore * COMPLETION_SPATIAL_WEIGHT
                            if (userFrequency > 0) {
                                val userFreqBoost = calculateFrequencyBoost(userFrequency)
                                baseConfidence += userFreqBoost
                            }
                            baseConfidence.coerceIn(COMPLETION_CONFIDENCE_MIN, 0.99)
                        }
                    result.add(
                        SpellingSuggestion(
                            word = word,
                            confidence = confidence,
                            ranking = index,
                            source = "completion"
                        )
                    )
                    seenWords.add(word.lowercase())
                }
            return result
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "querySingleLanguage_completions")
            )
            return emptyList()
        }
    }

    private suspend fun queryUrikSuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        val dict = getUrikDictionary(languageCode) ?: return emptyList()
        val adjacentKeyMap = cachedAdjacentKeyMap
        val inputAccentStripped = wordNormalizer.stripDiacritics(normalizedWord)

        val rawCandidates = dict.getCandidates(normalizedWord, MAX_EDIT_DISTANCE).toMutableList()

        if (adjacentKeyMap.isNotEmpty() &&
            languageCode != "ja" &&
            normalizedWord.length >= FAT_FINGER_MIN_WORD_LENGTH
        ) {
            val variants = fatFingerExpander.generateVariants(normalizedWord, adjacentKeyMap)
            for (variant in variants) {
                rawCandidates.addAll(dict.getCandidates(variant, MAX_EDIT_DISTANCE - 1))
            }
        }

        val auto = LevenshteinAutomaton(normalizedWord, MAX_EDIT_DISTANCE)
        val deduped = rawCandidates
            .groupBy { it.first.lowercase() }
            .mapNotNull { (_, dupes) ->
                val rep = dupes.minBy { it.second }
                val trueDistance = auto.accept(rep.first.lowercase())
                if (trueDistance < 0) null else rep.first to trueDistance
            }

        val terms = deduped.map { it.first }
        val userFrequencies = try {
            wordFrequencyRepository.getFrequencies(terms, languageCode)
        } catch (_: Exception) {
            emptyMap()
        }

        val inputFreq = dict.getFrequency(normalizedWord)

        val scored = deduped
            .filter { (term, distance) ->
                if (seenWords.contains(term.lowercase()) || isWordBlacklisted(term)) return@filter false
                if (distance == 0) return@filter false
                if (inputFreq > 0) {
                    val termFreq = dict.getFrequency(term)
                    if (termFreq > 0 && inputFreq > termFreq * SUGGESTION_MAX_FREQUENCY_GAP) return@filter false
                }
                true
            }
            .mapIndexed { index, (term, distance) ->
                val freq = dict.getFrequency(term)
                val confidence = scoreDictionaryCandidate(
                    input = normalizedWord,
                    term = term,
                    distance = distance,
                    frequency = freq,
                    userFrequency = userFrequencies[term] ?: 0,
                    languageCode = languageCode,
                    inputAccentStripped = inputAccentStripped
                )
                SpellingSuggestion(
                    word = term,
                    confidence = confidence,
                    ranking = index,
                    source = "dictionary"
                )
            }
        return scored.also { results -> seenWords.addAll(results.map { it.word.lowercase() }) }
    }

    private fun scoreDictionaryCandidate(
        input: String,
        term: String,
        distance: Int,
        frequency: Long,
        userFrequency: Int,
        languageCode: String,
        inputAccentStripped: String
    ): Double {
        if (distance == 0) return EXACT_MATCH_CONFIDENCE

        val isContraction = com.urik.keyboard.utils.TextMatchingUtils
            .isContractionSuggestion(input, term)
        if (isContraction) return CONTRACTION_GUARANTEED_CONFIDENCE

        val distanceScore = (MAX_EDIT_DISTANCE - distance).toDouble() / MAX_EDIT_DISTANCE.toDouble()
        val freqScore = ln(frequency.toDouble() + 1.0) / ln(MAX_DICT_FREQUENCY)
        var base = DICT_DISTANCE_WEIGHT * distanceScore + DICT_FREQUENCY_WEIGHT * freqScore

        if (languageCode != "ja") {
            base += calculateFullWordSpatialScore(input, term.lowercase()) * SPATIAL_PROXIMITY_WEIGHT
        }

        val strippedTerm = com.urik.keyboard.utils.TextMatchingUtils.stripWordPunctuation(term)
        if (strippedTerm != term && distance == 1) {
            base += APOSTROPHE_BOOST
        }

        val termAccentStripped = wordNormalizer.stripDiacritics(term.lowercase())
        if (inputAccentStripped == termAccentStripped && input != term.lowercase()) {
            base += DIACRITIC_PROMOTION_BOOST
        }

        if (strippedTerm.length < input.length) {
            val ratio = strippedTerm.length.toDouble() / input.length
            if (ratio < MINIMUM_LENGTH_RATIO) base *= ratio
        }

        if (userFrequency > 0) base += calculateFrequencyBoost(userFrequency)

        return base.coerceIn(DICT_CONFIDENCE_MIN, 0.99)
    }

    private fun mergeAndRankSuggestions(
        suggestions: List<SpellingSuggestion>,
        maxResults: Int,
        languageCode: String
    ): List<SpellingSuggestion> {
        val promoted = promoteContractionForms(suggestions, languageCode)

        return promoted
            .groupBy { it.word }
            .mapValues { (_, dupes) -> dupes.maxBy { it.confidence } }
            .values
            .sortedByDescending { it.confidence }
            .take(maxResults)
    }

    private fun promoteContractionForms(
        suggestions: List<SpellingSuggestion>,
        languageCode: String
    ): List<SpellingSuggestion> {
        var anyPromoted = false
        val result = suggestions.map { suggestion ->
            val contraction = getDominantContractionForm(suggestion.word, languageCode)
            if (contraction != null) {
                anyPromoted = true
                suggestion.copy(word = contraction)
            } else {
                suggestion
            }
        }
        return if (anyPromoted) result else suggestions
    }

    private fun getCompletionsForPrefix(prefix: String, languageCode: String): List<Pair<String, Long>> {
        val dict = getUrikDictionary(languageCode) ?: return emptyList()
        return dict.getWordsWithPrefix(prefix, MAX_PREFIX_COMPLETION_RESULTS)
            .filter { (word, _) -> word.length > prefix.length }
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
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SpellCheckManager",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to "getCurrentLanguage")
        )
        "en"
    }

    private fun getLocaleForLanguage(): Locale {
        val lang = languageManager.currentLanguage.value
        if (lang != currentLanguage) {
            currentLanguage = lang
            cachedLocale = try {
                Locale.forLanguageTag(lang)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SpellCheckManager",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "getLocaleForLanguage")
                )
                Locale.forLanguageTag("en")
            }
        }
        return cachedLocale
    }

    private fun buildCacheKey(word: String, language: String): String = "${language}_$word"

    /** Call when language changed or after bulk word learning. */
    fun clearCaches() {
        suggestionCache.invalidateAll()
        dictionaryCache.invalidateAll()
    }

    /**
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "invalidateWordCache")
            )
        }
    }

    /** Removes from WordLearningEngine and adds to blacklist in one operation. */
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
     * Global across all languages. Clears entire suggestion cache since cached
     * prefix lists may contain this word.
     */
    fun blacklistSuggestion(word: String) {
        try {
            val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()

            blacklistedWords.add(normalizedWord)

            val currentLang = getCurrentLanguage()
            val cacheKey = buildCacheKey(normalizedWord, currentLang)
            dictionaryCache.invalidate(cacheKey)
            suggestionCache.invalidateAll()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "blacklistSuggestion")
            )
        }
    }

    /** Clears entire suggestion cache since cached prefix lists excluded this word. */
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "removeFromBlacklist")
            )
        }
    }

    fun isWordBlacklisted(word: String): Boolean = try {
        val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()
        normalizedWord in blacklistedWords
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SpellCheckManager",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to "isWordBlacklisted")
        )
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
                clearCaches()
                val keepLanguage = currentLanguage
                val toEvict = urikDictionaries.keys.filter { it != keepLanguage }
                toEvict.forEach { lang -> urikDictionaries.remove(lang) }
            }

            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
            -> {
                val activeLangs = languageManager.activeLanguages.value.toSet()
                val toEvict = urikDictionaries.keys.filter { it !in activeLangs }
                toEvict.forEach { lang -> urikDictionaries.remove(lang) }
            }
        }
    }

    suspend fun getCommonWords(languageCode: String? = null): List<Pair<String, Long>> = withContext(ioDispatcher) {
        try {
            if (!ensureInitialized()) {
                return@withContext emptyList()
            }

            val targetLang = languageCode ?: getCurrentLanguage()
            if (targetLang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                return@withContext emptyList()
            }

            val dict = getUrikDictionary(targetLang) ?: return@withContext emptyList()
            return@withContext dict.getWordsWithPrefix("", dict.wordCount)
                .filter { !isWordBlacklisted(it.first) }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "getCommonWords")
            )
            return@withContext emptyList()
        }
    }

    suspend fun getCommonWordsForLanguages(languages: List<String>): Map<String, Long> = withContext(ioDispatcher) {
        try {
            if (!ensureInitialized() || languages.isEmpty()) {
                return@withContext emptyMap()
            }

            val mergedWords = HashMap<String, Long>(INITIAL_WORD_LIST_CAPACITY)

            languages.forEach { lang ->
                if (lang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                    return@forEach
                }

                val dict = getUrikDictionary(lang) ?: return@forEach
                dict.getWordsWithPrefix("", dict.wordCount).forEach { (word, frequency) ->
                    if (!isWordBlacklisted(word)) {
                        mergedWords[word] = maxOf(mergedWords[word] ?: 0L, frequency)
                    }
                }
            }

            return@withContext mergedWords
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "getCommonWordsForLanguages")
            )
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

    internal companion object {
        const val SUGGESTION_CACHE_SIZE = 500
        const val DICTIONARY_CACHE_SIZE = 1000

        const val MAX_EDIT_DISTANCE = 2
        const val MAX_SUGGESTIONS = 5
        const val MIN_COMPLETION_LENGTH = 4
        const val FAT_FINGER_MIN_WORD_LENGTH = 4
        const val APOSTROPHE_BOOST = 0.30
        const val DIACRITIC_PROMOTION_BOOST = 0.08
        const val EXACT_MATCH_CONFIDENCE = 0.999
        const val CONTRACTION_GUARANTEED_CONFIDENCE = 0.995
        const val CONTRACTION_DOMINANCE_RATIO = 20L
        const val USER_FREQ_CONTRACTION_WEIGHT = 300L

        const val INITIAL_WORD_LIST_CAPACITY = 50000
        const val INITIALIZATION_TIMEOUT_MS = 5000L

        const val FREQUENCY_BOOST_MULTIPLIER = 0.02
        const val LEARNED_WORD_BASE_CONFIDENCE = 0.60

        /** Spatial score below which learned word base confidence is multiplicatively reduced. */
        const val SPATIAL_GATE_THRESHOLD = 0.4
        const val LEARNED_WORD_CONFIDENCE_MIN = 0.30
        const val LEARNED_SPATIAL_WEIGHT = 0.35
        const val LEARNED_WORD_CONFIDENCE_MAX = 0.99

        const val MAX_PREFIX_COMPLETIONS = 5
        const val COMPLETION_LENGTH_WEIGHT = 0.70
        const val COMPLETION_FREQUENCY_WEIGHT = 0.30
        const val COMPLETION_CONFIDENCE_MIN = 0.50

        const val DICT_DISTANCE_WEIGHT = 0.45
        const val DICT_FREQUENCY_WEIGHT = 0.05
        const val DICT_CONFIDENCE_MIN = 0.0
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
        const val SUGGESTION_MAX_FREQUENCY_GAP = 500L

        const val HIGH_FREQUENCY_THRESHOLD = 10
        const val MEDIUM_FREQUENCY_THRESHOLD = 3
        const val HIGH_FREQUENCY_BASE_BOOST = 0.15
        const val HIGH_FREQUENCY_LOG_MULTIPLIER = 0.04
        const val MEDIUM_FREQUENCY_BASE_BOOST = 0.05
        const val MEDIUM_FREQUENCY_LOG_MULTIPLIER = 0.03
    }
}
