package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.utils.CacheMemoryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

private data class VariationErrorEntry(
    val count: Int,
    val lastErrorTime: Long,
)

/**
 * Circuit breaker for failed character variation loads.
 *
 * Prevents repeated I/O attempts for missing/corrupt variation files.
 * Evicts oldest entries when capacity exceeded (LRU).
 */
private class BoundedVariationErrorTracker(
    private val maxSize: Int,
) {
    private val entries =
        object : LinkedHashMap<String, VariationErrorEntry>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VariationErrorEntry>?): Boolean = size > maxSize
        }

    @Synchronized
    fun recordError(key: String): Int {
        val current = entries[key]
        val newCount = (current?.count ?: 0) + 1
        entries[key] = VariationErrorEntry(newCount, System.currentTimeMillis())
        return newCount
    }

    @Synchronized
    fun getErrorCount(key: String): Int = entries[key]?.count ?: 0

    @Synchronized
    fun getLastErrorTime(key: String): Long = entries[key]?.lastErrorTime ?: 0L

    @Synchronized
    fun remove(key: String) = entries.remove(key)

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun cleanExpired(expiryMs: Long) {
        val now = System.currentTimeMillis()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((now - entry.value.lastErrorTime) > expiryMs) {
                iterator.remove()
            }
        }
    }
}

/**
 * Loads character variations (long-press alternatives) from JSON assets.
 *
 */
@Singleton
class CharacterVariationService
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val cacheMemoryManager: CacheMemoryManager,
        private val languageManager: LanguageManager,
    ) {
        private var contextRef: WeakReference<Context> = WeakReference(context)

        private val variationCache =
            cacheMemoryManager.createCache<String, Map<String, List<String>>>(
                name = "character_variations",
                maxSize = CHARACTER_VARIATIONS_CACHE_SIZE,
            )

        private val failedLanguages = mutableSetOf<String>()
        private val errorTracker = BoundedVariationErrorTracker(maxSize = ERROR_TRACKER_MAX_SIZE)

        private val maxAssetRetries = MAX_ASSET_RETRIES
        private val assetErrorCooldownMs = ASSET_ERROR_COOLDOWN_MS
        private val errorStateExpiryMs = ERROR_STATE_EXPIRY_MS

        private var isDestroyed = false
        private var lastErrorCleanup = System.currentTimeMillis()

        private fun cleanupExpiredErrors() {
            val now = System.currentTimeMillis()

            if ((now - lastErrorCleanup) > ERROR_CLEANUP_INTERVAL_MS) {
                errorTracker.cleanExpired(errorStateExpiryMs)

                val iterator = failedLanguages.iterator()
                while (iterator.hasNext()) {
                    val languageCode = iterator.next()
                    val lastError = errorTracker.getLastErrorTime(languageCode)
                    if (lastError > 0 && (now - lastError) > errorStateExpiryMs) {
                        iterator.remove()
                    }
                }

                lastErrorCleanup = now
            }
        }

        /**
         * Gets character variations for long-press menu.
         *
         * Fallback cascade on missing assets:
         * 1. Language-specific (languageCode) → 2. English (en) → 3. Hardcoded Latin
         *
         * Circuit breaker skips known-broken languages (3 failures, 60s cooldown).
         *
         * @param baseChar Character to get variations for (e.g., "a")
         * @param languageCode Target language code (e.g., "sv", "en")
         * @return List of variation characters, empty if none available
         */
        suspend fun getVariations(
            baseChar: String,
            languageCode: String,
        ): List<String> =
            withContext(Dispatchers.IO) {
                if (isDestroyed || baseChar.isEmpty()) return@withContext emptyList()

                return@withContext try {
                    cleanupExpiredErrors()

                    val variations = getVariationsForLanguage(languageCode)
                    variations[baseChar.lowercase()] ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

        private suspend fun getVariationsForLanguage(languageCode: String): Map<String, List<String>> =
            withContext(Dispatchers.IO) {
                if (isDestroyed) return@withContext emptyMap()

                return@withContext variationCache.getIfPresent(languageCode) ?: run {
                    val variations = loadVariationsWithErrorHandling(languageCode)
                    if (variations.isNotEmpty()) {
                        variationCache.put(languageCode, variations)
                    }
                    variations
                }
            }

        private fun loadVariationsWithErrorHandling(languageCode: String): Map<String, List<String>> {
            if (shouldSkipAssetLoading(languageCode)) {
                return getFallbackVariations(languageCode)
            }

            val context = contextRef.get() ?: return emptyMap()

            return try {
                loadVariationsFromAssets(context, languageCode).also {
                    errorTracker.remove(languageCode)
                    failedLanguages.remove(languageCode)
                }
            } catch (_: FileNotFoundException) {
                handleAssetError(languageCode)
                getFallbackVariations(languageCode)
            } catch (_: IOException) {
                handleAssetError(languageCode)
                getFallbackVariations(languageCode)
            } catch (_: JSONException) {
                handleAssetError(languageCode)
                getFallbackVariations(languageCode)
            } catch (_: SecurityException) {
                handleAssetError(languageCode)
                getFallbackVariations(languageCode)
            } catch (_: OutOfMemoryError) {
                handleAssetError(languageCode)
                clearNonEssentialCaches()
                emptyMap()
            } catch (_: Exception) {
                handleAssetError(languageCode)
                getFallbackVariations(languageCode)
            }
        }

        private fun loadVariationsFromAssets(
            context: Context,
            languageCode: String,
        ): Map<String, List<String>> {
            val filename = "$languageCode.json"

            return context.assets.open("characters/$filename").bufferedReader().use { reader ->
                val jsonContent = reader.readText()

                if (jsonContent.isBlank()) {
                    throw IllegalStateException("Character variations file $filename is empty")
                }

                parseVariationsJson(jsonContent)
            }
        }

        private fun parseVariationsJson(jsonContent: String): Map<String, List<String>> {
            val json = JSONObject(jsonContent)

            if (!json.has("variations")) {
                throw JSONException("Missing 'variations' key in character variations file")
            }

            val variationsJson = json.getJSONObject("variations")
            val result = mutableMapOf<String, List<String>>()

            variationsJson.keys().forEach { key ->
                if (key.isBlank()) {
                    return@forEach
                }

                val variationArray = variationsJson.getJSONArray(key)
                val variations = mutableListOf<String>()

                for (i in 0 until variationArray.length()) {
                    val variation = variationArray.getString(i)
                    if (variation.isNotBlank()) {
                        variations.add(variation)
                    }
                }

                if (variations.isNotEmpty()) {
                    result[key.lowercase()] = variations
                }
            }

            if (result.isEmpty()) {
                throw IllegalStateException("No valid character variations found in file")
            }

            return result
        }

        private fun getFallbackVariations(languageCode: String): Map<String, List<String>> {
            return when {
                languageCode != "en" && !failedLanguages.contains("en") -> {
                    try {
                        val context = contextRef.get() ?: return getMinimalFallbackVariations()
                        loadVariationsFromAssets(context, "en")
                    } catch (_: Exception) {
                        failedLanguages.add("en")
                        getMinimalFallbackVariations()
                    }
                }
                else -> {
                    getMinimalFallbackVariations()
                }
            }
        }

        private fun getMinimalFallbackVariations(): Map<String, List<String>> =
            mapOf(
                "a" to listOf("á", "à", "â", "ä", "ã", "å"),
                "e" to listOf("é", "è", "ê", "ë"),
                "i" to listOf("í", "ì", "î", "ï"),
                "o" to listOf("ó", "ò", "ô", "ö", "õ"),
                "u" to listOf("ú", "ù", "û", "ü"),
                "n" to listOf("ñ"),
                "c" to listOf("ç"),
                "s" to listOf("ß"),
            )

        private fun handleAssetError(languageCode: String) {
            val errorCount = errorTracker.recordError(languageCode)

            if (errorCount >= maxAssetRetries) {
                failedLanguages.add(languageCode)
            }
        }

        private fun shouldSkipAssetLoading(languageCode: String): Boolean {
            val errorCount = errorTracker.getErrorCount(languageCode)
            val lastError = errorTracker.getLastErrorTime(languageCode)
            val now = System.currentTimeMillis()

            return errorCount >= maxAssetRetries && (now - lastError) < assetErrorCooldownMs
        }

        @Suppress("DEPRECATION")
        private fun clearNonEssentialCaches() {
            val currentLayoutLang = languageManager.currentLayoutLanguage.value
            val languagesToKeep =
                setOf(currentLayoutLang, "en")

            val allCached = variationCache.asMap()
            allCached.keys.forEach { languageCode ->
                if (languageCode !in languagesToKeep) {
                    variationCache.invalidate(languageCode)
                }
            }

            cacheMemoryManager.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }

        /**
         * Clears all caches and error state.
         *
         * Call when changing keyboard settings or on memory pressure.
         */
        fun cleanup() {
            if (isDestroyed) return
            isDestroyed = true

            variationCache.invalidateAll()
            failedLanguages.clear()
            errorTracker.clear()
            contextRef.clear()
        }

        private companion object {
            const val CHARACTER_VARIATIONS_CACHE_SIZE = 8
            const val MAX_ASSET_RETRIES = 3
            const val ASSET_ERROR_COOLDOWN_MS = 60000L
            const val ERROR_STATE_EXPIRY_MS = 3600000L
            const val ERROR_CLEANUP_INTERVAL_MS = 600000L
            const val ERROR_TRACKER_MAX_SIZE = 15
        }
    }
