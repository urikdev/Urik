package com.urik.keyboard.data

import com.urik.keyboard.KeyboardConstants.BigramConstants
import com.urik.keyboard.KeyboardConstants.CacheConstants
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.service.WordNormalizer
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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

        private fun normalizeWord(
            word: String,
            languageTag: String,
        ): String = wordNormalizer.normalize(word, languageTag)

        private fun buildCacheKey(
            languageTag: String,
            normalizedWord: String,
        ): String = "${languageTag}_$normalizedWord"

        suspend fun incrementFrequency(
            word: String,
            languageTag: String,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    if (word.isBlank()) {
                        return@withContext Result.success(Unit)
                    }

                    val normalized = normalizeWord(word, languageTag)
                    val cacheKey = buildCacheKey(languageTag, normalized)

                    userWordFrequencyDao.incrementFrequency(
                        languageTag = languageTag,
                        wordNormalized = normalized,
                        lastUsed = System.currentTimeMillis(),
                    )

                    frequencyCache.invalidate(cacheKey)

                    Result.success(Unit)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "incrementFrequency"),
                    )
                    Result.failure(e)
                }
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

                    frequencyCache.getIfPresent(cacheKey)?.let { return@withContext it }

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

        suspend fun clearAllFrequencies(): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    userWordFrequencyDao.clearAll()
                    frequencyCache.invalidateAll()
                    Result.success(Unit)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "clearAllFrequencies"),
                    )
                    Result.failure(e)
                }
            }

        fun clearCache() {
            frequencyCache.invalidateAll()
            bigramCache.invalidateAll()
            topBigramsCache.clear()
        }

        suspend fun recordBigram(
            wordA: String,
            wordB: String,
            languageTag: String,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    if (wordA.isBlank() || wordB.isBlank()) {
                        return@withContext Result.success(Unit)
                    }

                    val normalizedA = normalizeWord(wordA, languageTag)
                    val normalizedB = normalizeWord(wordB, languageTag)
                    val cacheKey = buildBigramCacheKey(languageTag, normalizedA)

                    userWordBigramDao.incrementBigram(
                        languageTag = languageTag,
                        wordANormalized = normalizedA,
                        wordBNormalized = normalizedB,
                        lastUsed = System.currentTimeMillis(),
                    )

                    bigramCache.invalidate(cacheKey)
                    topBigramsCache.remove(languageTag)

                    Result.success(Unit)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "recordBigram"),
                    )
                    Result.failure(e)
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

        suspend fun clearAllBigrams(): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    userWordBigramDao.clearAll()
                    bigramCache.invalidateAll()
                    topBigramsCache.clear()
                    Result.success(Unit)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "WordFrequencyRepository",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "clearAllBigrams"),
                    )
                    Result.failure(e)
                }
            }

        private fun buildBigramCacheKey(
            languageTag: String,
            normalizedWordA: String,
        ): String = "bigram_${languageTag}_$normalizedWordA"
    }
