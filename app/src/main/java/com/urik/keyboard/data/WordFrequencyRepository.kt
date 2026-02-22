package com.urik.keyboard.data

import com.urik.keyboard.KeyboardConstants.BigramConstants
import com.urik.keyboard.KeyboardConstants.CacheConstants
import com.urik.keyboard.KeyboardConstants.DatabaseConstants
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.service.WordNormalizer
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private data class PendingFrequencyUpdate(
    val languageTag: String,
    val wordNormalized: String,
    var incrementCount: Int = 1,
)

private data class PendingBigramUpdate(
    val languageTag: String,
    val wordANormalized: String,
    val wordBNormalized: String,
    var incrementCount: Int = 1,
)

@Singleton
class WordFrequencyRepository
    @Inject
    constructor(
        private val userWordFrequencyDao: UserWordFrequencyDao,
        private val userWordBigramDao: UserWordBigramDao,
        private val wordNormalizer: WordNormalizer,
        cacheMemoryManager: CacheMemoryManager,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        private val frequencyCache: ManagedCache<String, Int> =
            cacheMemoryManager.createCache(
                name = "user_word_frequency_cache",
                maxSize = CacheConstants.USER_FREQUENCY_CACHE_SIZE,
            )

        private val bigramCache: ManagedCache<String, List<String>> =
            cacheMemoryManager.createCache(
                name = "user_bigram_cache",
                maxSize = BigramConstants.BIGRAM_CACHE_SIZE,
            )

        @Volatile
        private var topBigramsCache = ConcurrentHashMap<String, Map<String, List<String>>>()

        private val writeScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private var frequencyFlushJob: Job? = null
        private var bigramFlushJob: Job? = null
        private val frequencyWriteMutex = Mutex()
        private val bigramWriteMutex = Mutex()
        private val pendingFrequencyUpdates = ConcurrentHashMap<String, PendingFrequencyUpdate>()
        private val pendingBigramUpdates = ConcurrentHashMap<String, PendingBigramUpdate>()
        private val flushCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        private companion object {
            const val WRITE_DEBOUNCE_MS = 300L
        }

        private fun normalizeWord(
            word: String,
            languageTag: String,
        ): String = wordNormalizer.normalize(word, languageTag)

        private fun buildCacheKey(
            languageTag: String,
            normalizedWord: String,
        ): String = "${languageTag}_$normalizedWord"

        fun incrementFrequency(
            word: String,
            languageTag: String,
        ): Result<Unit> {
            if (word.isBlank()) {
                return Result.success(Unit)
            }

            val normalized = normalizeWord(word, languageTag)
            val cacheKey = buildCacheKey(languageTag, normalized)

            frequencyCache.invalidate(cacheKey)

            pendingFrequencyUpdates.compute(cacheKey) { _, existing ->
                existing?.apply { incrementCount++ }
                    ?: PendingFrequencyUpdate(languageTag, normalized)
            }

            scheduleFrequencyFlush()

            return Result.success(Unit)
        }

        private fun scheduleFrequencyFlush() {
            frequencyFlushJob?.cancel()
            frequencyFlushJob =
                writeScope.launch {
                    delay(WRITE_DEBOUNCE_MS)
                    flushPendingFrequencyUpdates()
                }
        }

        private suspend fun flushPendingFrequencyUpdates() {
            frequencyWriteMutex.withLock {
                if (pendingFrequencyUpdates.isEmpty()) return

                val updates = pendingFrequencyUpdates.toMap()
                pendingFrequencyUpdates.clear()

                val timestamp = System.currentTimeMillis()

                updates.values.forEach { update ->
                    try {
                        userWordFrequencyDao.incrementFrequencyBy(
                            languageTag = update.languageTag,
                            wordNormalized = update.wordNormalized,
                            amount = update.incrementCount,
                            lastUsed = timestamp,
                        )
                    } catch (e: Exception) {
                        ErrorLogger.logException(
                            component = "WordFrequencyRepository",
                            severity = ErrorLogger.Severity.HIGH,
                            exception = e,
                            context = mapOf("operation" to "flushPendingFrequencyUpdates"),
                        )
                    }
                }
            }
            pruneIfNeeded()
        }

        suspend fun getFrequency(
            word: String,
            languageTag: String,
        ): Int =
            withContext(defaultDispatcher) {
                try {
                    if (word.isBlank()) {
                        return@withContext 0
                    }

                    val normalized = normalizeWord(word, languageTag)
                    val cacheKey = buildCacheKey(languageTag, normalized)

                    frequencyCache.getIfPresent(cacheKey)?.let {
                        return@withContext it
                    }

                    val frequency =
                        withContext(ioDispatcher) {
                            userWordFrequencyDao.findWord(languageTag, normalized)?.frequency ?: 0
                        }

                    frequencyCache.put(cacheKey, frequency)

                    return@withContext frequency
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "getFrequency"),
                    )
                    return@withContext 0
                }
            }

        suspend fun getFrequencies(
            words: List<String>,
            languageTag: String,
        ): Map<String, Int> =
            withContext(defaultDispatcher) {
                try {
                    if (words.isEmpty()) {
                        return@withContext emptyMap()
                    }

                    val results = mutableMapOf<String, Int>()
                    val wordsToFetch = mutableListOf<String>()

                    for (word in words) {
                        if (word.isBlank()) {
                            results[word] = 0
                            continue
                        }

                        val normalized = normalizeWord(word, languageTag)
                        val cacheKey = buildCacheKey(languageTag, normalized)

                        frequencyCache.getIfPresent(cacheKey)?.let { cached ->
                            results[word] = cached
                        } ?: run {
                            wordsToFetch.add(normalized)
                        }
                    }

                    if (wordsToFetch.isNotEmpty()) {
                        val fetched =
                            withContext(ioDispatcher) {
                                userWordFrequencyDao.findWords(languageTag, wordsToFetch)
                            }

                        val fetchedMap = fetched.associate { it.wordNormalized to it.frequency }

                        for (word in words) {
                            if (word in results) continue

                            val normalized = normalizeWord(word, languageTag)
                            val frequency = fetchedMap[normalized] ?: 0
                            val cacheKey = buildCacheKey(languageTag, normalized)

                            frequencyCache.put(cacheKey, frequency)
                            results[word] = frequency
                        }
                    }

                    return@withContext results
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "getFrequencies"),
                    )
                    return@withContext words.associateWith { 0 }
                }
            }

        fun clearCache() {
            frequencyFlushJob?.cancel()
            bigramFlushJob?.cancel()

            writeScope.launch {
                flushPendingFrequencyUpdates()
                flushPendingBigramUpdates()
            }

            frequencyCache.invalidateAll()
            bigramCache.invalidateAll()
            topBigramsCache.clear()
        }

        fun recordBigram(
            wordA: String,
            wordB: String,
            languageTag: String,
        ): Result<Unit> {
            if (wordA.isBlank() || wordB.isBlank()) {
                return Result.success(Unit)
            }

            val normalizedA = normalizeWord(wordA, languageTag)
            val normalizedB = normalizeWord(wordB, languageTag)
            val cacheKey = buildBigramCacheKey(languageTag, normalizedA)
            val bigramKey = "${languageTag}_${normalizedA}_$normalizedB"

            bigramCache.invalidate(cacheKey)
            topBigramsCache.remove(languageTag)

            pendingBigramUpdates.compute(bigramKey) { _, existing ->
                existing?.apply { incrementCount++ }
                    ?: PendingBigramUpdate(languageTag, normalizedA, normalizedB)
            }

            scheduleBigramFlush()

            return Result.success(Unit)
        }

        private fun scheduleBigramFlush() {
            bigramFlushJob?.cancel()
            bigramFlushJob =
                writeScope.launch {
                    delay(WRITE_DEBOUNCE_MS)
                    flushPendingBigramUpdates()
                }
        }

        private suspend fun flushPendingBigramUpdates() {
            bigramWriteMutex.withLock {
                if (pendingBigramUpdates.isEmpty()) return

                val updates = pendingBigramUpdates.toMap()
                pendingBigramUpdates.clear()

                val timestamp = System.currentTimeMillis()

                updates.values.forEach { update ->
                    try {
                        userWordBigramDao.incrementBigramBy(
                            languageTag = update.languageTag,
                            wordANormalized = update.wordANormalized,
                            wordBNormalized = update.wordBNormalized,
                            amount = update.incrementCount,
                            lastUsed = timestamp,
                        )
                    } catch (e: Exception) {
                        ErrorLogger.logException(
                            component = "WordFrequencyRepository",
                            severity = ErrorLogger.Severity.HIGH,
                            exception = e,
                            context = mapOf("operation" to "flushPendingBigramUpdates"),
                        )
                    }
                }
            }
        }

        suspend fun getBigramPredictions(
            wordA: String,
            languageTag: String,
            limit: Int = BigramConstants.MAX_BIGRAM_PREDICTIONS,
        ): List<String> =
            withContext(defaultDispatcher) {
                try {
                    if (wordA.isBlank()) {
                        return@withContext emptyList()
                    }

                    val normalizedA = normalizeWord(wordA, languageTag)
                    val cacheKey = buildBigramCacheKey(languageTag, normalizedA)

                    bigramCache.getIfPresent(cacheKey)?.let { cached ->
                        return@withContext cached.take(limit)
                    }

                    topBigramsCache[languageTag]?.get(normalizedA)?.let { cached ->
                        return@withContext cached.take(limit)
                    }

                    val predictions =
                        withContext(ioDispatcher) {
                            userWordBigramDao.getPredictions(languageTag, normalizedA, limit)
                        }

                    bigramCache.put(cacheKey, predictions)

                    return@withContext predictions
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "getBigramPredictions"),
                    )
                    return@withContext emptyList()
                }
            }

        suspend fun preloadTopBigrams(languageTag: String): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    val topBigrams = userWordBigramDao.getTopBigrams(languageTag, BigramConstants.BIGRAM_CACHE_SIZE)

                    val bigramMap =
                        topBigrams
                            .groupBy { it.wordANormalized }
                            .mapValues { (_, bigrams) ->
                                bigrams
                                    .sortedByDescending { it.frequency }
                                    .map { it.wordBNormalized }
                            }

                    topBigramsCache[languageTag] = bigramMap

                    Result.success(Unit)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "preloadTopBigrams"),
                    )
                    Result.failure(e)
                }
            }

        private suspend fun pruneIfNeeded() {
            val count = flushCount.incrementAndGet()
            if (count % DatabaseConstants.PRUNING_INTERVAL_FLUSHES != 0) return

            withContext(ioDispatcher) {
                try {
                    val cutoff = System.currentTimeMillis() - DatabaseConstants.FREQUENCY_PRUNING_CUTOFF_MS
                    userWordFrequencyDao.pruneStaleEntries(cutoff)
                    userWordFrequencyDao.enforceMaxRows(DatabaseConstants.MAX_FREQUENCY_ROWS)
                    userWordBigramDao.pruneStaleEntries(cutoff)
                    userWordBigramDao.enforceMaxRows(DatabaseConstants.MAX_BIGRAM_ROWS)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "pruneIfNeeded"),
                    )
                }
            }
        }

        private fun buildBigramCacheKey(
            languageTag: String,
            normalizedWordA: String,
        ): String = "bigram_${languageTag}_$normalizedWordA"
    }
