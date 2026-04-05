package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONException

class PunctuationLoader(private val context: Context, cacheMemoryManager: CacheMemoryManager) {
    private val punctuationCache: ManagedCache<String, List<String>> =
        cacheMemoryManager.createCache(
            name = "punctuation_cache",
            maxSize = 20
        )

    private val failedPunctuationLanguages = ConcurrentHashMap.newKeySet<String>()
    private val punctuationErrorCounts = ConcurrentHashMap<String, Int>()
    private val lastPunctuationErrors = ConcurrentHashMap<String, Long>()
    private var lastErrorCleanupTime = 0L

    fun loadPunctuation(languageCode: String): List<String> {
        punctuationCache.getIfPresent(languageCode)?.let { cached ->
            return cached
        }

        performPeriodicErrorCleanup()

        if (shouldSkipLoading(languageCode)) {
            return getFallbackPunctuation(languageCode)
        }

        return try {
            loadFromAssets(languageCode).also { punctuation ->
                punctuationCache.put(languageCode, punctuation)
                punctuationErrorCounts.remove(languageCode)
                lastPunctuationErrors.remove(languageCode)
            }
        } catch (_: Exception) {
            recordError(languageCode)
            getFallbackPunctuation(languageCode)
        }
    }

    fun cleanup() {
        punctuationCache.invalidateAll()
    }

    private fun loadFromAssets(languageCode: String): List<String> {
        val filename = "$languageCode.json"

        return context.assets.open("punctuation/$filename").bufferedReader().use { reader ->
            val jsonContent = reader.readText()
            if (jsonContent.isBlank()) {
                error("Punctuation file $filename is empty")
            }
            parseJson(jsonContent)
        }
    }

    private fun parseJson(jsonContent: String): List<String> {
        val json = org.json.JSONObject(jsonContent)
        if (!json.has("punctuation")) {
            throw JSONException("Missing 'punctuation' key in punctuation file")
        }

        val punctuationArray = json.getJSONArray("punctuation")
        val result = mutableListOf<String>()

        for (i in 0 until punctuationArray.length()) {
            val punctuation = punctuationArray.getString(i)
            if (punctuation.isNotBlank()) {
                result.add(punctuation)
            }
        }

        if (result.isEmpty()) {
            error("No valid punctuation marks found in file")
        }

        return result
    }

    private fun getFallbackPunctuation(languageCode: String): List<String> = when {
        languageCode != "en" && !failedPunctuationLanguages.contains("en") -> {
            try {
                loadFromAssets("en").also { punctuation ->
                    punctuationCache.put("en", punctuation)
                }
            } catch (_: Exception) {
                failedPunctuationLanguages.add("en")
                DEFAULT_PUNCTUATION
            }
        }

        else -> {
            DEFAULT_PUNCTUATION
        }
    }

    private fun shouldSkipLoading(languageCode: String): Boolean {
        val errorCount = punctuationErrorCounts.getOrDefault(languageCode, 0)
        val lastError = lastPunctuationErrors.getOrDefault(languageCode, 0)
        val now = System.currentTimeMillis()

        return errorCount >= MAX_RETRIES && now - lastError < ERROR_COOLDOWN_MS
    }

    private fun recordError(languageCode: String) {
        val currentTime = System.currentTimeMillis()
        val currentCount = punctuationErrorCounts.getOrDefault(languageCode, 0)

        punctuationErrorCounts[languageCode] = currentCount + 1
        lastPunctuationErrors[languageCode] = currentTime

        if (currentCount + 1 >= MAX_RETRIES) {
            failedPunctuationLanguages.add(languageCode)
        }

        enforceErrorTrackingBounds()
    }

    private fun enforceErrorTrackingBounds() {
        while (punctuationErrorCounts.size > MAX_ERROR_TRACKING_SIZE) {
            val oldestEntry =
                lastPunctuationErrors.entries
                    .minByOrNull { it.value }
                    ?.key

            if (oldestEntry != null) {
                punctuationErrorCounts.remove(oldestEntry)
                lastPunctuationErrors.remove(oldestEntry)
                failedPunctuationLanguages.remove(oldestEntry)
            } else {
                break
            }
        }
    }

    private fun performPeriodicErrorCleanup() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastErrorCleanupTime < ERROR_CLEANUP_INTERVAL_MS) {
            return
        }

        lastErrorCleanupTime = currentTime

        val cutoffTime = currentTime - ERROR_TRACKING_RETENTION_SECONDS * 1000L

        val expiredLanguages =
            lastPunctuationErrors.entries
                .filter { entry -> entry.value < cutoffTime }
                .map { entry -> entry.key }
                .toList()

        expiredLanguages.forEach { languageCode ->
            punctuationErrorCounts.remove(languageCode)
            lastPunctuationErrors.remove(languageCode)
            failedPunctuationLanguages.remove(languageCode)
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val ERROR_COOLDOWN_MS = 60000L
        private const val MAX_ERROR_TRACKING_SIZE = 20
        private const val ERROR_CLEANUP_INTERVAL_MS = 300000L
        private const val ERROR_TRACKING_RETENTION_SECONDS = 60
        val DEFAULT_PUNCTUATION = listOf(".", ",", "?", "!", "'", "\"", ";", ":")
    }
}
