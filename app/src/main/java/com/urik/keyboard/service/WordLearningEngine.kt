package com.urik.keyboard.service

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.urik.keyboard.KeyboardConstants.CacheConstants
import com.urik.keyboard.KeyboardConstants.WordLearningConstants
import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.WordSource
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Word learning configuration.
 */
data class LearningConfig(
    val minWordLength: Int = WordLearningConstants.MIN_WORD_LENGTH,
    val maxWordLength: Int = WordLearningConstants.MAX_WORD_LENGTH,
    val minFrequencyThreshold: Int = WordLearningConstants.MIN_FREQUENCY_THRESHOLD,
    val maxConsecutiveErrors: Int = WordLearningConstants.MAX_CONSECUTIVE_ERRORS,
    val errorCooldownMs: Long = WordLearningConstants.ERROR_COOLDOWN_MS,
)

/**
 * Learning statistics for analytics.
 */
data class LearningStats(
    val totalWordsLearned: Int,
    val wordsInCurrentLanguage: Int,
    val averageWordFrequency: Double,
    val wordsByLanguage: Map<String, Int>,
    val wordsByInputMethod: Map<InputMethod, Int>,
    val currentLanguage: String,
)

/**
 * Learns and retrieves user-typed words with frequency tracking.
 *
 */
@Singleton
class WordLearningEngine
    @Inject
    constructor(
        private val learnedWordDao: LearnedWordDao,
        private val languageManager: LanguageManager,
        private val wordNormalizer: WordNormalizer,
        settingsRepository: SettingsRepository,
        cacheMemoryManager: CacheMemoryManager,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) {
        private val config = LearningConfig()

        private val lastDatabaseError = AtomicLong(0L)
        private val consecutiveErrors = AtomicInteger(0)
        private val errorLock = Any()

        private val learnedWordsCache: ManagedCache<String, MutableSet<String>> =
            cacheMemoryManager.createCache(
                name = "learned_words_cache",
                maxSize = CacheConstants.LEARNED_WORDS_CACHE_SIZE,
            )

        private val cacheLock = Any()

        private val learnWordMutex = Mutex()

        private var currentSettings = KeyboardSettings()

        @Volatile
        private var swipeWordsCache = emptyList<Pair<String, Int>>()

        @Volatile
        private var swipeWordsCacheLanguage = ""

        private val engineJob = SupervisorJob()
        private val engineScope = CoroutineScope(engineJob + mainDispatcher)

        init {
            settingsRepository.settings
                .onEach { newSettings ->
                    currentSettings = newSettings
                }.launchIn(engineScope)
        }

        /**
         * Initializes cache for specific language from database.
         *
         * Call when language switches to populate cache with learned words.
         * Safe to call multiple times (overwrites existing cache).
         */
        suspend fun initializeLearnedWordsCache(languageTag: String): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    val learnedWords = learnedWordDao.getAllLearnedWordsForLanguage(languageTag)

                    synchronized(cacheLock) {
                        val normalizedSet = ConcurrentHashMap.newKeySet<String>()
                        learnedWords.forEach { normalizedSet.add(it.wordNormalized) }
                        learnedWordsCache.put(languageTag, normalizedSet)
                    }

                    Result.success(Unit)
                } catch (e: SQLiteException) {
                    ErrorLogger.logException(
                        component = "WordLearningEngine",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "initializeLearnedWordsCache", "language" to languageTag),
                    )
                    handleDatabaseError()
                    Result.failure(Exception("Database error initializing cache"))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private fun normalizeWord(word: String): String {
            val currentLanguage = languageManager.currentLanguage.value
            return wordNormalizer.normalize(word, currentLanguage)
        }

        private fun isValidWordForLearning(word: String): Boolean {
            if (word.isBlank()) return false

            val currentLanguage = languageManager.currentLanguage.value
            val normalizedWord = wordNormalizer.normalize(word, currentLanguage)
            val graphemeCount = normalizedWord.codePointCount(0, normalizedWord.length)

            if (graphemeCount < config.minWordLength || graphemeCount > config.maxWordLength) {
                return false
            }

            return normalizedWord.any { char ->
                Character.isLetter(char.code) ||
                    Character.isIdeographic(char.code) ||
                    Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                    com.urik.keyboard.utils.TextMatchingUtils
                        .isValidWordPunctuation(char)
            }
        }

        /**
         * Learns word from user input.
         *
         * Behavior:
         * - Validates word (length, unicode, settings)
         * - Normalizes (NFC + language-aware)
         * - Upserts to DB (increments frequency if exists)
         * - Updates cache
         * - Returns null if learning disabled or validation fails
         *
         * @return LearnedWord if successful, null if skipped/failed
         */
        suspend fun learnWord(
            word: String,
            inputMethod: InputMethod,
        ): Result<LearnedWord?> =
            withContext(ioDispatcher) {
                if (!currentSettings.isWordLearningEnabled) {
                    return@withContext Result.success(null)
                }

                val currentLanguage =
                    languageManager.currentLanguage.value

                return@withContext try {
                    val cleanWord = word.trim()
                    if (!isValidWordForLearning(cleanWord)) {
                        return@withContext Result.success(null)
                    }

                    val normalized = normalizeWord(cleanWord)

                    val wordSource =
                        when (inputMethod) {
                            InputMethod.TYPED -> WordSource.USER_TYPED
                            InputMethod.SWIPED -> WordSource.SWIPE_LEARNED
                            InputMethod.SELECTED_FROM_SUGGESTION -> WordSource.USER_SELECTED
                        }

                    val learnedWord =
                        LearnedWord.create(
                            word = cleanWord,
                            wordNormalized = normalized,
                            languageTag = currentLanguage,
                            frequency = 1,
                            source = wordSource,
                        )

                    learnWordMutex.withLock {
                        learnedWordDao.learnWord(learnedWord)

                        synchronized(cacheLock) {
                            val cacheSet =
                                learnedWordsCache.getIfPresent(currentLanguage)
                                    ?: ConcurrentHashMap.newKeySet()
                            cacheSet.add(normalized)
                            learnedWordsCache.put(currentLanguage, cacheSet)

                            if (currentLanguage == swipeWordsCacheLanguage) {
                                swipeWordsCache = emptyList()
                                swipeWordsCacheLanguage = ""
                            }
                        }
                    }

                    onSuccessfulOperation()

                    Result.success(learnedWord)
                } catch (e: SQLiteDatabaseCorruptException) {
                    ErrorLogger.logException(
                        component = "WordLearningEngine",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf("operation" to "learnWord", "word_length" to word.length.toString()),
                    )
                    handleDatabaseError()
                    Result.success(null)
                } catch (e: SQLiteFullException) {
                    ErrorLogger.logException(
                        component = "WordLearningEngine",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "learnWord", "action" to "attempting_cleanup"),
                    )
                    handleDatabaseError()
                    tryCleanupOldWords()
                    Result.success(null)
                } catch (_: SQLiteDatabaseLockedException) {
                    handleDatabaseError(isLock = true)
                    Result.success(null)
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    Result.success(null)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        /**
         * Checks if word is learned.
         *
         * Lookup order:
         * 1. Cache (O(1) if language cached)
         * 2. Database (indexed query)
         *
         * @return true if word exists in current language's learned words
         */
        suspend fun isWordLearned(word: String): Boolean =
            withContext(defaultDispatcher) {
                val currentLanguage = languageManager.currentLanguage.value

                return@withContext try {
                    val cleanWord = word.trim()
                    if (!isValidWordForLearning(cleanWord)) {
                        return@withContext false
                    }

                    val normalized = normalizeWord(cleanWord)

                    val cachedWords = learnedWordsCache.getIfPresent(currentLanguage)

                    if (cachedWords != null) {
                        return@withContext cachedWords.contains(normalized)
                    }

                    val learnedWord =
                        withContext(ioDispatcher) {
                            learnedWordDao.findExactWord(
                                languageTag = currentLanguage,
                                normalizedWord = normalized,
                            )
                        }

                    onSuccessfulOperation()
                    learnedWord != null
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    false
                } catch (_: Exception) {
                    false
                }
            }

        suspend fun getLearnedWordOriginalCase(
            word: String,
            languageCode: String,
        ): String? =
            withContext(ioDispatcher) {
                try {
                    val cleanWord = word.trim()
                    if (cleanWord.isBlank()) {
                        return@withContext null
                    }

                    val normalized = normalizeWord(cleanWord)

                    val cachedWords = learnedWordsCache.getIfPresent(languageCode)
                    if (cachedWords != null && !cachedWords.contains(normalized)) {
                        return@withContext null
                    }

                    val learnedWord =
                        learnedWordDao.findExactWord(
                            languageTag = languageCode,
                            normalizedWord = normalized,
                        )

                    onSuccessfulOperation()
                    learnedWord?.word
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    null
                } catch (_: Exception) {
                    null
                }
            }

        /**
         * Gets similar learned words for autocomplete/correction.
         *
         * Search strategy (in order, up to maxResults):
         * 1. Exact match
         * 2. Prefix matches (FTS4 query)
         * 3. Edit distance ≤2 (fuzzy search on top 30 frequent words)
         *
         * Results sorted by frequency descending.
         *
         * @return List of (word, frequency) pairs
         */
        suspend fun getSimilarLearnedWordsWithFrequency(
            word: String,
            languageCode: String,
            maxResults: Int = 3,
        ): List<Pair<String, Int>> =
            withContext(ioDispatcher) {
                return@withContext try {
                    val cleanWord = word.trim()
                    if (cleanWord.isBlank() || cleanWord.length > WordLearningConstants.MAX_SIMILAR_WORD_LENGTH) {
                        return@withContext emptyList()
                    }

                    val normalized = normalizeWord(cleanWord)
                    if (normalized.length > WordLearningConstants.MAX_NORMALIZED_WORD_LENGTH) {
                        return@withContext emptyList()
                    }

                    if (isInErrorCooldown()) {
                        return@withContext emptyList()
                    }

                    val results = mutableMapOf<String, Int>()
                    val strippedInput =
                        com.urik.keyboard.utils.TextMatchingUtils
                            .stripWordPunctuation(normalized)

                    try {
                        val exactMatch =
                            learnedWordDao.findExactWord(
                                languageTag = languageCode,
                                normalizedWord = normalized,
                            )
                        if (exactMatch != null) {
                            results[exactMatch.word] = exactMatch.frequency
                        }
                    } catch (_: Exception) {
                    }

                    if (results.size < maxResults && normalized.length >= WordLearningConstants.MIN_PREFIX_MATCH_LENGTH) {
                        try {
                            val prefixMatches =
                                learnedWordDao.findWordsWithPrefix(
                                    languageTag = languageCode,
                                    prefix = normalized,
                                    limit = maxResults - results.size,
                                )
                            prefixMatches.forEach { learnedWord ->
                                if (!results.containsKey(learnedWord.word)) {
                                    results[learnedWord.word] = learnedWord.frequency
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    if (results.size < maxResults && strippedInput.length >= WordLearningConstants.MIN_PREFIX_MATCH_LENGTH) {
                        try {
                            val searchLimit =
                                if (strippedInput.length <= 3) {
                                    WordLearningConstants.STRIPPED_MATCH_LIMIT_SHORT
                                } else {
                                    WordLearningConstants.STRIPPED_MATCH_LIMIT_MEDIUM
                                }

                            val frequentWords =
                                learnedWordDao.getMostFrequentWords(
                                    languageTag = languageCode,
                                    limit = searchLimit,
                                )
                            frequentWords
                                .filter { candidate ->
                                    val strippedCandidate =
                                        com.urik.keyboard.utils.TextMatchingUtils.stripWordPunctuation(
                                            candidate.wordNormalized,
                                        )
                                    strippedCandidate != candidate.wordNormalized &&
                                        strippedCandidate.startsWith(strippedInput, ignoreCase = true) &&
                                        !results.containsKey(candidate.word)
                                }.sortedByDescending { it.frequency }
                                .take(maxResults - results.size)
                                .forEach { candidate ->
                                    results[candidate.word] = candidate.frequency
                                }
                        } catch (_: Exception) {
                        }
                    }

                    if (results.size < maxResults && normalized.length >= WordLearningConstants.MIN_FUZZY_SEARCH_LENGTH) {
                        val runtime = Runtime.getRuntime()
                        val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                        val memoryThresholdBytes = com.urik.keyboard.KeyboardConstants.MemoryConstants.LOW_MEMORY_THRESHOLD_MB * 1024 * 1024

                        if (availableMemory < memoryThresholdBytes) {
                            return@withContext results.toList().sortedByDescending { it.second }.take(maxResults)
                        }

                        try {
                            val candidates =
                                learnedWordDao.getMostFrequentWords(
                                    languageTag = languageCode,
                                    limit = WordLearningConstants.FUZZY_SEARCH_CANDIDATE_LIMIT,
                                )

                            val viableCandidates =
                                candidates.filter { candidate ->
                                    val lengthDiff = kotlin.math.abs(candidate.wordNormalized.length - normalized.length)
                                    lengthDiff <= WordLearningConstants.MAX_LENGTH_DIFFERENCE_FUZZY &&
                                        candidate.wordNormalized.length <= WordLearningConstants.MAX_SIMILAR_WORD_LENGTH
                                }

                            viableCandidates
                                .map { candidate ->
                                    candidate to calculateEditDistance(candidate.wordNormalized, normalized)
                                }.filter { (candidate, distance) ->
                                    distance in WordLearningConstants.MIN_EDIT_DISTANCE..WordLearningConstants.MAX_EDIT_DISTANCE_FUZZY &&
                                        !results.containsKey(candidate.word) &&
                                        candidate.word != normalized
                                }.sortedBy { it.second }
                                .take(maxResults - results.size)
                                .forEach { (candidate, _) ->
                                    results[candidate.word] = candidate.frequency
                                }
                        } catch (_: Exception) {
                        }
                    }

                    onSuccessfulOperation()

                    results.toList().sortedByDescending { it.second }.take(maxResults)
                } catch (_: SQLiteDatabaseCorruptException) {
                    handleDatabaseError()
                    emptyList()
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

        /**
         * Removes learned word from database and cache.
         *
         * Thread safety: Uses same mutex as learnWord to prevent race conditions.
         *
         * @return true if word removed, false if not found or error
         */
        suspend fun removeWord(word: String): Result<Boolean> =
            withContext(ioDispatcher) {
                val currentLanguage = languageManager.currentLanguage.value

                return@withContext try {
                    val cleanWord = word.trim()
                    if (cleanWord.isBlank()) {
                        return@withContext Result.success(false)
                    }

                    val normalized = normalizeWord(cleanWord)

                    if (isInErrorCooldown()) {
                        return@withContext Result.success(false)
                    }

                    learnWordMutex.withLock {
                        val removed =
                            learnedWordDao.removeWordComplete(
                                languageTag = currentLanguage,
                                normalizedWord = normalized,
                            )

                        if (removed > 0) {
                            learnedWordsCache.getIfPresent(currentLanguage)?.remove(normalized)

                            if (currentLanguage == swipeWordsCacheLanguage) {
                                swipeWordsCache = emptyList()
                                swipeWordsCacheLanguage = ""
                            }

                            consecutiveErrors.set(0)

                            Result.success(true)
                        } else {
                            Result.success(false)
                        }
                    }
                } catch (e: SQLiteDatabaseCorruptException) {
                    ErrorLogger.logException(
                        component = "WordLearningEngine",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf("operation" to "removeWord"),
                    )
                    handleDatabaseError()
                    Result.success(false)
                } catch (e: SQLiteFullException) {
                    ErrorLogger.logException(
                        component = "WordLearningEngine",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "removeWord"),
                    )
                    handleDatabaseError()
                    Result.success(false)
                } catch (_: SQLiteDatabaseLockedException) {
                    handleDatabaseError(isLock = true)
                    Result.success(false)
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    Result.success(false)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private fun calculateEditDistance(
            s1: String,
            s2: String,
        ): Int {
            if (s1 == s2) return 0
            if (s1.isEmpty()) return s2.length
            if (s2.isEmpty()) return s1.length

            val maxStringLength = WordLearningConstants.MAX_EDIT_DISTANCE_STRING_LENGTH
            if (s1.length > maxStringLength || s2.length > maxStringLength) {
                return Int.MAX_VALUE
            }

            val lengthDiff = kotlin.math.abs(s1.length - s2.length)
            if (lengthDiff > WordLearningConstants.MAX_LENGTH_DIFFERENCE_FUZZY) {
                return Int.MAX_VALUE
            }

            val maxArraySize = WordLearningConstants.MAX_EDIT_DISTANCE_ARRAY_SIZE
            val rows = minOf(s1.length + 1, maxArraySize)
            val cols = minOf(s2.length + 1, maxArraySize)

            val dp = Array(rows) { IntArray(cols) }

            for (i in 0 until rows) dp[i][0] = i
            for (j in 0 until cols) dp[0][j] = j

            for (i in 1 until rows) {
                var minInRow = Int.MAX_VALUE

                for (j in 1 until cols) {
                    dp[i][j] =
                        if (s1[i - 1] == s2[j - 1]) {
                            dp[i - 1][j - 1]
                        } else {
                            1 +
                                minOf(
                                    dp[i - 1][j],
                                    dp[i][j - 1],
                                    dp[i - 1][j - 1],
                                )
                        }

                    minInRow = minOf(minInRow, dp[i][j])
                }

                if (minInRow > WordLearningConstants.EDIT_DISTANCE_ROW_THRESHOLD) {
                    return Int.MAX_VALUE
                }
            }

            return dp[rows - 1][cols - 1]
        }

        /**
         * Batch checks if multiple words are learned.
         *
         * More efficient than calling isWordLearned() repeatedly.
         * Updates cache with results to improve future lookups.
         *
         * @return Map of original word → learned status
         */
        private suspend fun ensureCacheLoaded(languageTag: String) {
            synchronized(cacheLock) {
                if (learnedWordsCache.getIfPresent(languageTag) != null) {
                    return
                }
            }

            val allWords = learnedWordDao.getAllLearnedWordsForLanguage(languageTag).map { it.wordNormalized }.toSet()
            val cacheSet = ConcurrentHashMap.newKeySet<String>()
            cacheSet.addAll(allWords)

            synchronized(cacheLock) {
                learnedWordsCache.put(languageTag, cacheSet)
            }
        }

        suspend fun areWordsLearned(words: List<String>): Map<String, Boolean> =
            withContext(ioDispatcher) {
                if (words.isEmpty()) {
                    return@withContext emptyMap()
                }

                val currentLanguage = languageManager.currentLanguage.value

                return@withContext try {
                    ensureCacheLoaded(currentLanguage)

                    val results = mutableMapOf<String, Boolean>()
                    val originalToNormalized = mutableMapOf<String, String>()

                    for (word in words) {
                        val cleanWord = word.trim()
                        if (!isValidWordForLearning(cleanWord)) {
                            results[word] = false
                            continue
                        }

                        val normalized = normalizeWord(cleanWord)
                        originalToNormalized[word] = normalized
                    }

                    val cachedWords =
                        synchronized(cacheLock) {
                            learnedWordsCache.getIfPresent(currentLanguage) ?: emptySet()
                        }

                    for (word in words) {
                        if (results.containsKey(word)) {
                            continue
                        }

                        val normalizedWord = originalToNormalized[word]
                        results[word] = normalizedWord != null && cachedWords.contains(normalizedWord)
                    }

                    consecutiveErrors.set(0)
                    results
                } catch (_: SQLiteDatabaseCorruptException) {
                    handleDatabaseError()
                    words.associateWith { false }
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    words.associateWith { false }
                } catch (_: Exception) {
                    words.associateWith { false }
                }
            }

        /**
         * Gets learning statistics for current language.
         *
         * Returns safe defaults if in error cooldown or destroyed.
         */
        suspend fun getLearningStats(): Result<LearningStats> =
            withContext(ioDispatcher) {
                return@withContext try {
                    val currentLanguage = languageManager.currentLanguage.value

                    if (isInErrorCooldown()) {
                        val defaultStats =
                            LearningStats(
                                totalWordsLearned = 0,
                                wordsInCurrentLanguage = 0,
                                averageWordFrequency = 0.0,
                                wordsByLanguage = emptyMap(),
                                wordsByInputMethod = emptyMap(),
                                currentLanguage = currentLanguage,
                            )
                        return@withContext Result.success(defaultStats)
                    }

                    val totalWords =
                        try {
                            learnedWordDao.getTotalWordCount()
                        } catch (_: Exception) {
                            0
                        }

                    val averageFrequency =
                        try {
                            learnedWordDao.getAverageFrequency()
                        } catch (_: Exception) {
                            0.0
                        }

                    val wordCountByLanguage = mapOf(currentLanguage to totalWords)

                    val sourceCounts =
                        try {
                            learnedWordDao.getWordCountsBySource()
                        } catch (_: Exception) {
                            emptyList()
                        }

                    val inputMethodStats =
                        sourceCounts
                            .associate { sourceCount ->
                                getInputMethodFromSource(sourceCount.source) to sourceCount.count
                            }.let { methodCounts ->
                                val combinedCounts = mutableMapOf<InputMethod, Int>()
                                methodCounts.forEach { (method, count) ->
                                    combinedCounts[method] = (combinedCounts[method] ?: 0) + count
                                }
                                combinedCounts
                            }.ifEmpty {
                                emptyMap()
                            }

                    val stats =
                        LearningStats(
                            totalWordsLearned = totalWords,
                            wordsInCurrentLanguage = totalWords,
                            averageWordFrequency = averageFrequency,
                            wordsByLanguage = wordCountByLanguage,
                            wordsByInputMethod = inputMethodStats,
                            currentLanguage = currentLanguage,
                        )

                    consecutiveErrors.set(0)

                    Result.success(stats)
                } catch (_: SQLiteDatabaseCorruptException) {
                    handleDatabaseError()
                    Result.failure(Exception("Database corrupted"))
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    Result.failure(Exception("Database error"))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private fun handleDatabaseError(isLock: Boolean = false) {
            synchronized(errorLock) {
                val currentErrors = consecutiveErrors.incrementAndGet()
                lastDatabaseError.set(System.currentTimeMillis())

                if (isLock && currentErrors > 1) {
                    consecutiveErrors.decrementAndGet()
                }
            }
        }

        private fun isInErrorCooldown(): Boolean {
            val now = System.currentTimeMillis()
            val errorCount = consecutiveErrors.get()
            val lastError = lastDatabaseError.get()

            if (errorCount < config.maxConsecutiveErrors) {
                return false
            }

            val backoffMultiplier = errorCount - config.maxConsecutiveErrors + 1
            val backoffMs =
                minOf(
                    500L * backoffMultiplier,
                    config.errorCooldownMs,
                )

            return (now - lastError) < backoffMs
        }

        private suspend fun tryCleanupOldWords() {
            try {
                val cutoff = System.currentTimeMillis() - WordLearningConstants.CLEANUP_CUTOFF_MS
                learnedWordDao.cleanupLowFrequencyWords(cutoff)
            } catch (_: Exception) {
            }
        }

        private fun onSuccessfulOperation() {
            synchronized(errorLock) {
                val currentErrors = consecutiveErrors.get()
                if (currentErrors > 0) {
                    consecutiveErrors.decrementAndGet()
                }
            }
        }

        private fun getInputMethodFromSource(source: WordSource): InputMethod =
            when (source) {
                WordSource.USER_TYPED -> InputMethod.TYPED
                WordSource.SWIPE_LEARNED -> InputMethod.SWIPED
                WordSource.USER_SELECTED -> InputMethod.SELECTED_FROM_SUGGESTION
                WordSource.AUTO_CORRECTED -> InputMethod.TYPED
                WordSource.IMPORTED -> InputMethod.TYPED
                WordSource.SYSTEM_DEFAULT -> InputMethod.TYPED
            }

        /**
         * Clears learned words cache for current language.
         *
         * Call when entering secure fields to prevent cache leaks.
         */
        fun clearCurrentLanguageCache() {
            val currentLanguage = languageManager.currentLanguage.value
            learnedWordsCache.invalidate(currentLanguage)

            if (currentLanguage == swipeWordsCacheLanguage) {
                swipeWordsCache = emptyList()
                swipeWordsCacheLanguage = ""
            }
        }

        suspend fun getLearnedWordsForSwipeAllLanguages(
            languages: List<String>,
            minLength: Int,
            maxLength: Int,
        ): Map<String, Int> =
            withContext(ioDispatcher) {
                try {
                    if (isInErrorCooldown() || languages.isEmpty()) {
                        return@withContext emptyMap()
                    }

                    val mergedWords = mutableMapOf<String, Int>()

                    coroutineScope {
                        val allLanguageWords =
                            languages
                                .map { lang ->
                                    async {
                                        try {
                                            learnedWordDao.getAllLearnedWordsForLanguage(lang)
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                    }
                                }.awaitAll()

                        allLanguageWords.forEach { words ->
                            words
                                .filter { it.frequency >= 1 && it.word.length in minLength..maxLength }
                                .forEach { learnedWord ->
                                    val word = learnedWord.word.lowercase()
                                    val currentFreq = mergedWords[word] ?: 0
                                    mergedWords[word] = maxOf(currentFreq, learnedWord.frequency)
                                }
                        }
                    }

                    onSuccessfulOperation()
                    return@withContext mergedWords
                } catch (_: SQLiteDatabaseCorruptException) {
                    handleDatabaseError()
                    emptyMap()
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            }
    }
