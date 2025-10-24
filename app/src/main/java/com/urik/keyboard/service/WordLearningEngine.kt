package com.urik.keyboard.service

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.ibm.icu.text.Normalizer2
import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.WordSource
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Word learning configuration.
 */
data class LearningConfig(
    val minWordLength: Int = 1,
    val maxWordLength: Int = 100,
    val minFrequencyThreshold: Int = 2,
    val maxConsecutiveErrors: Int = 5,
    val errorCooldownMs: Long = 1500L,
)

/**
 * Normalized word variants.
 *
 * @property standard NFC normalization (preserves case)
 * @property userSpecific Language-aware normalization (lowercase for Latin, etc.)
 */
private data class NormalizedWord(
    val standard: String,
    val userSpecific: String,
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
        settingsRepository: SettingsRepository,
        cacheMemoryManager: CacheMemoryManager,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) {
        private val config = LearningConfig()
        private val normalizer = Normalizer2.getNFCInstance()

        private val lastDatabaseError = AtomicLong(0L)
        private val consecutiveErrors = AtomicInteger(0)
        private val errorLock = Any()

        private val learnedWordsCache: ManagedCache<String, MutableSet<String>> =
            cacheMemoryManager.createCache(
                name = "learned_words_cache",
                maxSize = 100,
                onEvict = { _, _ -> },
            )

        private val cacheLock = Any()

        private val learnWordMutex = Mutex()

        private val isDestroyed = AtomicBoolean(false)

        private var currentSettings = KeyboardSettings()

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
                    if (isDestroyed.get()) return@withContext Result.failure(IllegalStateException("Engine destroyed"))

                    val learnedWords = learnedWordDao.getAllLearnedWordsForLanguage(languageTag)

                    synchronized(cacheLock) {
                        val normalizedSet = ConcurrentHashMap.newKeySet<String>()
                        learnedWords.forEach { normalizedSet.add(it.wordNormalized) }
                        learnedWordsCache.put(languageTag, normalizedSet)
                    }

                    Result.success(Unit)
                } catch (_: SQLiteException) {
                    handleDatabaseError()
                    Result.failure(Exception("Database error initializing cache"))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private fun normalizeWord(word: String): NormalizedWord {
            val standardNormalized = normalizer.normalize(word.trim())
            val currentLanguage = languageManager.currentLanguage.value
            val locale =
                try {
                    com.ibm.icu.util.ULocale
                        .forLanguageTag(currentLanguage)
                } catch (_: Exception) {
                    com.ibm.icu.util.ULocale.ENGLISH
                }
            val userNormalized =
                com.ibm.icu.lang.UCharacter
                    .toLowerCase(locale, standardNormalized)
                    .trim()
            return NormalizedWord(standardNormalized, userNormalized)
        }

        private fun isValidWordForLearning(word: String): Boolean {
            if (isDestroyed.get() || word.isBlank()) return false

            val normalizedWord = normalizer.normalize(word)
            val graphemeCount = normalizedWord.codePointCount(0, normalizedWord.length)

            if (graphemeCount < config.minWordLength || graphemeCount > config.maxWordLength) {
                return false
            }

            return normalizedWord.any { char ->
                Character.isLetter(char.code) ||
                    Character.isIdeographic(char.code) ||
                    Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                    isValidWordPunctuation(char)
            }
        }

        private fun isValidWordPunctuation(char: Char): Boolean =
            when (char.code) {
                0x0027 -> true
                0x002D -> true
                0x2019 -> true
                0x060C -> true
                0x061F -> true
                0x0640 -> true
                0x3001 -> true
                0x3002 -> true
                0xFF0C -> true
                0xFF0E -> true
                else ->
                    Character.getType(char.code) == Character.DASH_PUNCTUATION.toInt() ||
                        Character.getType(char.code) == Character.OTHER_PUNCTUATION.toInt()
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
                if (isDestroyed.get()) return@withContext Result.success(null)

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
                            word = normalized.userSpecific,
                            wordNormalized = normalized.userSpecific,
                            languageTag = currentLanguage,
                            frequency = 1,
                            source = wordSource,
                        )

                    learnWordMutex.withLock {
                        if (isDestroyed.get()) return@withContext Result.success(null)

                        learnedWordDao.learnWord(learnedWord)

                        synchronized(cacheLock) {
                            if (!isDestroyed.get()) {
                                val cacheSet =
                                    learnedWordsCache.getIfPresent(currentLanguage)
                                        ?: ConcurrentHashMap.newKeySet()
                                cacheSet.add(normalized.userSpecific)
                                learnedWordsCache.put(currentLanguage, cacheSet)
                            }
                        }
                    }

                    onSuccessfulOperation()

                    Result.success(learnedWord)
                } catch (_: SQLiteDatabaseCorruptException) {
                    handleDatabaseError()
                    Result.success(null)
                } catch (_: SQLiteFullException) {
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
                if (isDestroyed.get()) return@withContext false

                val currentLanguage = languageManager.currentLanguage.value

                return@withContext try {
                    val cleanWord = word.trim()
                    if (!isValidWordForLearning(cleanWord)) {
                        return@withContext false
                    }

                    val normalized = normalizeWord(cleanWord)

                    val cachedWords =
                        synchronized(cacheLock) {
                            learnedWordsCache.getIfPresent(currentLanguage)
                        }

                    if (cachedWords != null) {
                        return@withContext cachedWords.contains(normalized.userSpecific)
                    }

                    val learnedWord =
                        withContext(ioDispatcher) {
                            learnedWordDao.findExactWord(
                                languageTag = currentLanguage,
                                normalizedWord = normalized.userSpecific,
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
            maxResults: Int = 3,
        ): List<Pair<String, Int>> =
            withContext(ioDispatcher) {
                if (isDestroyed.get()) return@withContext emptyList()

                val currentLanguage = languageManager.currentLanguage.value

                return@withContext try {
                    val cleanWord = word.trim()
                    if (cleanWord.isBlank() || cleanWord.length > 50) {
                        return@withContext emptyList()
                    }

                    val normalized = normalizeWord(cleanWord)
                    if (normalized.userSpecific.length > 50) {
                        return@withContext emptyList()
                    }

                    if (isInErrorCooldown()) {
                        return@withContext emptyList()
                    }

                    val results = mutableMapOf<String, Int>()

                    try {
                        val exactMatch =
                            learnedWordDao.findExactWord(
                                languageTag = currentLanguage,
                                normalizedWord = normalized.userSpecific,
                            )
                        if (exactMatch != null) {
                            results[exactMatch.word] = exactMatch.frequency
                        }
                    } catch (_: Exception) {
                    }

                    if (results.size < maxResults && normalized.userSpecific.length >= 2) {
                        try {
                            val prefixMatches =
                                learnedWordDao.findWordsWithPrefix(
                                    languageTag = currentLanguage,
                                    prefix = normalized.userSpecific,
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

                    if (results.size < maxResults && normalized.userSpecific.length >= 4) {
                        try {
                            val candidates =
                                learnedWordDao.getMostFrequentWords(
                                    languageTag = currentLanguage,
                                    limit = 30,
                                )

                            val viableCandidates =
                                candidates.filter { candidate ->
                                    val lengthDiff = kotlin.math.abs(candidate.wordNormalized.length - normalized.userSpecific.length)
                                    lengthDiff <= 2 && candidate.wordNormalized.length <= 50
                                }

                            viableCandidates
                                .map { candidate ->
                                    candidate to calculateEditDistance(candidate.wordNormalized, normalized.userSpecific)
                                }.filter { (candidate, distance) ->
                                    distance in 1..2 &&
                                        !results.containsKey(candidate.word) &&
                                        candidate.word != normalized.userSpecific
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
                if (isDestroyed.get()) return@withContext Result.success(false)

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
                                normalizedWord = normalized.userSpecific,
                            )

                        if (removed > 0) {
                            synchronized(cacheLock) {
                                if (!isDestroyed.get()) {
                                    learnedWordsCache.getIfPresent(currentLanguage)?.remove(normalized.userSpecific)
                                }
                            }

                            consecutiveErrors.set(0)

                            Result.success(true)
                        } else {
                            Result.success(false)
                        }
                    }
                } catch (_: SQLiteDatabaseCorruptException) {
                    handleDatabaseError()
                    Result.success(false)
                } catch (_: SQLiteFullException) {
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

            val maxStringLength = 50
            if (s1.length > maxStringLength || s2.length > maxStringLength) {
                return Int.MAX_VALUE
            }

            val lengthDiff = kotlin.math.abs(s1.length - s2.length)
            if (lengthDiff > 2) {
                return Int.MAX_VALUE
            }

            val maxArraySize = 51
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

                if (minInRow > 2) {
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
        suspend fun areWordsLearned(words: List<String>): Map<String, Boolean> =
            withContext(ioDispatcher) {
                if (isDestroyed.get() || words.isEmpty()) {
                    return@withContext emptyMap()
                }

                val currentLanguage = languageManager.currentLanguage.value

                return@withContext try {
                    val results = mutableMapOf<String, Boolean>()
                    val validWords = mutableListOf<String>()
                    val originalToNormalized = mutableMapOf<String, String>()

                    for (word in words) {
                        val cleanWord = word.trim()
                        if (!isValidWordForLearning(cleanWord)) {
                            results[word] = false
                            continue
                        }

                        val normalized = normalizeWord(cleanWord)

                        validWords.add(normalized.userSpecific)
                        originalToNormalized[word] = normalized.userSpecific
                    }

                    val existingWords =
                        if (validWords.isNotEmpty()) {
                            learnedWordDao
                                .findExistingWords(
                                    languageTag = currentLanguage,
                                    normalizedWords = validWords,
                                ).toSet()
                        } else {
                            emptySet()
                        }

                    for (word in words) {
                        if (results.containsKey(word)) {
                            continue
                        }

                        val normalizedWord = originalToNormalized[word]
                        results[word] = normalizedWord != null && existingWords.contains(normalizedWord)
                    }

                    if (existingWords.isNotEmpty()) {
                        synchronized(cacheLock) {
                            if (!isDestroyed.get()) {
                                val cacheSet =
                                    learnedWordsCache.getIfPresent(currentLanguage)
                                        ?: ConcurrentHashMap.newKeySet()
                                existingWords.forEach { cacheSet.add(it) }
                                learnedWordsCache.put(currentLanguage, cacheSet)
                            }
                        }
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
                if (isDestroyed.get()) {
                    return@withContext Result.success(
                        LearningStats(
                            totalWordsLearned = 0,
                            wordsInCurrentLanguage = 0,
                            averageWordFrequency = 0.0,
                            wordsByLanguage = emptyMap(),
                            wordsByInputMethod = emptyMap(),
                            currentLanguage = "Unknown",
                        ),
                    )
                }

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
                val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
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
         * Cleans up resources and cancels settings observer.
         *
         */
        fun cleanup() {
            if (!isDestroyed.compareAndSet(false, true)) return

            engineJob.cancel()

            synchronized(cacheLock) {
                learnedWordsCache.invalidateAll()
            }

            synchronized(errorLock) {
                lastDatabaseError.set(0L)
                consecutiveErrors.set(0)
            }
        }
    }
