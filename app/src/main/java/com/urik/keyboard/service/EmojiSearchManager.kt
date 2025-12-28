package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.settings.KeyboardSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class EmojiAnnotations(
    val language: String,
    val emojis: Map<String, List<String>>,
)

@Singleton
class EmojiSearchManager
    @Inject
    constructor(
        private val context: Context,
        private val languageManager: LanguageManager,
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        private val mutex = Mutex()
        private val annotationCache = mutableMapOf<String, EmojiAnnotations>()
        private var keywordIndex: Map<String, List<String>> = emptyMap()
        private var loadedLanguages: Set<String> = emptySet()

        suspend fun ensureLoaded(): Result<Unit> =
            withContext(dispatcher) {
                try {
                    val activeLanguages = languageManager.activeLanguages.value

                    val needsReload =
                        mutex.withLock {
                            activeLanguages.toSet() != loadedLanguages || keywordIndex.isEmpty()
                        }

                    if (!needsReload) {
                        return@withContext Result.success(Unit)
                    }

                    mutex.withLock {
                        loadedLanguages = activeLanguages.toSet()
                        annotationCache.clear()

                        for (lang in activeLanguages) {
                            if (lang !in KeyboardSettings.SUPPORTED_LANGUAGES) continue

                            val annotations = loadAnnotationsForLanguage(lang)
                            if (annotations != null) {
                                annotationCache[lang] = annotations
                            }
                        }

                        keywordIndex = buildKeywordIndex()
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private fun loadAnnotationsForLanguage(languageCode: String): EmojiAnnotations? =
            try {
                val assetPath = "emoji/$languageCode.json"
                val inputStream = context.assets.open(assetPath)
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                val jsonContent = reader.use { it.readText() }

                val jsonObject = JSONObject(jsonContent)
                val language = jsonObject.getString("language")
                val emojisObject = jsonObject.getJSONObject("emojis")

                val emojisMap = mutableMapOf<String, List<String>>()
                val keys = emojisObject.keys()
                while (keys.hasNext()) {
                    val emoji = keys.next()
                    val keywordsArray = emojisObject.getJSONArray(emoji)
                    val keywords = mutableListOf<String>()
                    for (i in 0 until keywordsArray.length()) {
                        keywords.add(keywordsArray.getString(i))
                    }
                    emojisMap[emoji] = keywords
                }

                EmojiAnnotations(language, emojisMap)
            } catch (e: Exception) {
                null
            }

        private fun buildKeywordIndex(): Map<String, List<String>> {
            val index = mutableMapOf<String, MutableList<String>>()

            for (annotations in annotationCache.values) {
                for ((emoji, keywords) in annotations.emojis) {
                    for (keyword in keywords) {
                        val normalizedKeyword = keyword.lowercase()
                        index.getOrPut(normalizedKeyword) { mutableListOf() }.add(emoji)
                    }
                }
            }

            return index.mapValues { (_, emojis) -> emojis.distinct() }
        }

        suspend fun search(query: String): List<String> =
            withContext(dispatcher) {
                if (query.isBlank()) return@withContext emptyList()

                val index =
                    mutex.withLock {
                        keywordIndex
                    }

                val normalizedQuery = query.trim().lowercase()
                val emojiScores = mutableMapOf<String, Int>()

                for ((keyword, emojis) in index) {
                    val score =
                        when {
                            keyword == normalizedQuery -> 3
                            keyword.startsWith(normalizedQuery) -> 2
                            keyword.contains(normalizedQuery) -> 1
                            else -> 0
                        }

                    if (score > 0) {
                        for (emoji in emojis) {
                            emojiScores[emoji] = maxOf(emojiScores[emoji] ?: 0, score)
                        }
                    }
                }

                emojiScores.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(MAX_SEARCH_RESULTS)
                    .map { it.key }
            }

        companion object {
            private const val MAX_SEARCH_RESULTS = 30
        }
    }
