package com.urik.keyboard.data

import android.content.Context
import com.ibm.icu.util.ULocale
import com.urik.keyboard.KeyboardConstants.AssetLoadingConstants
import com.urik.keyboard.KeyboardConstants.CacheConstants
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

private data class LayoutErrorEntry(
    val count: Int,
    val lastErrorTime: Long,
)

/**
 * Circuit breaker for failed layout loads.
 *
 * Prevents repeated I/O attempts for missing/corrupt layout files.
 * Evicts oldest entries when capacity exceeded (LRU).
 */
private class BoundedLayoutErrorTracker(
    private val maxSize: Int,
) {
    private val entries =
        object : LinkedHashMap<String, LayoutErrorEntry>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LayoutErrorEntry>?): Boolean = size > maxSize
        }

    @Synchronized
    fun recordError(key: String): Int {
        val current = entries[key]
        val newCount = (current?.count ?: 0) + 1
        entries[key] = LayoutErrorEntry(newCount, System.currentTimeMillis())
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
 * Loads keyboard layouts from JSON assets with caching and fallback.
 *
 */
@Singleton
class KeyboardRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        cacheMemoryManager: CacheMemoryManager,
    ) {
        private val layoutCache: ManagedCache<String, KeyboardLayout> =
            cacheMemoryManager.createCache(
                name = "keyboard_layouts",
                maxSize = CacheConstants.LAYOUT_CACHE_SIZE,
            )

        private val failedLocales = mutableSetOf<String>()
        private val errorTracker = BoundedLayoutErrorTracker(maxSize = AssetLoadingConstants.LAYOUT_ERROR_TRACKER_MAX_SIZE)

        private val maxLayoutRetries = AssetLoadingConstants.MAX_LAYOUT_RETRIES
        private val layoutErrorCooldownMs = AssetLoadingConstants.LAYOUT_ERROR_COOLDOWN_MS
        private val errorStateExpiryMs = AssetLoadingConstants.LAYOUT_ERROR_STATE_EXPIRY_MS

        private var lastErrorCleanup = System.currentTimeMillis()

        private fun cleanupExpiredErrors() {
            val now = System.currentTimeMillis()

            if ((now - lastErrorCleanup) > AssetLoadingConstants.LAYOUT_ERROR_CLEANUP_INTERVAL_MS) {
                errorTracker.cleanExpired(errorStateExpiryMs)

                val iterator = failedLocales.iterator()
                while (iterator.hasNext()) {
                    val localeTag = iterator.next()
                    val lastError = errorTracker.getLastErrorTime(localeTag)
                    if (lastError > 0 && (now - lastError) > errorStateExpiryMs) {
                        iterator.remove()
                    }
                }

                lastErrorCleanup = now
            }
        }

        /**
         * Loads keyboard layout for given mode and locale.
         *
         * Fallback cascade on missing assets:
         * 1. Full locale (en-US) → 2. Language only (en) → 3. Hardcoded QWERTY
         *
         * Circuit breaker skips known-broken locales (3 failures, 60s cooldown).
         *
         * @param mode Letters/Numbers/Symbols
         * @param locale Target locale for layout
         * @param currentAction Enter key action type (e.g., ENTER, SEARCH, DONE, GO)
         * @return Layout or failure with exception
         */
        suspend fun getLayoutForMode(
            mode: KeyboardMode,
            locale: ULocale,
            currentAction: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER,
        ): Result<KeyboardLayout> =
            withContext(Dispatchers.IO) {
                val cacheKey = "${locale.toLanguageTag()}_${mode.name}_${currentAction.name}"

                layoutCache.getIfPresent(cacheKey)?.let { cachedLayout ->
                    return@withContext Result.success(cachedLayout)
                }

                return@withContext try {
                    val layout = loadLayoutFromAssets(mode, locale, currentAction)
                    layoutCache.put(cacheKey, layout)
                    Result.success(layout)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private suspend fun loadLayoutFromAssets(
            mode: KeyboardMode,
            locale: ULocale,
            currentAction: KeyboardKey.ActionType,
        ): KeyboardLayout =
            withContext(Dispatchers.IO) {
                val localeTag = locale.toLanguageTag()

                cleanupExpiredErrors()

                if (shouldSkipLocale(localeTag)) {
                    return@withContext getFallbackLayout(mode, currentAction)
                }

                return@withContext try {
                    val layoutData = loadLayoutDataFromAssets(context, localeTag)
                    parseLayoutForMode(layoutData, mode, currentAction).also {
                        errorTracker.remove(localeTag)
                        failedLocales.remove(localeTag)
                    }
                } catch (_: FileNotFoundException) {
                    handleAssetError(localeTag)
                    tryLanguageFallback(context, locale, mode, currentAction)
                } catch (_: Exception) {
                    handleAssetError(localeTag)
                    getFallbackLayout(mode, currentAction)
                }
            }

        private fun loadLayoutDataFromAssets(
            context: Context,
            localeTag: String,
        ): JSONObject {
            val filename = "$localeTag.json"

            return context.assets.open("layouts/$filename").bufferedReader().use { reader ->
                val jsonContent = reader.readText()

                if (jsonContent.isBlank()) {
                    throw IllegalStateException("Layout file $filename is empty")
                }

                JSONObject(jsonContent)
            }
        }

        private fun parseLayoutForMode(
            layoutData: JSONObject,
            mode: KeyboardMode,
            currentAction: KeyboardKey.ActionType,
        ): KeyboardLayout {
            if (!layoutData.has("modes")) {
                throw IllegalStateException("Layout data missing 'modes' section")
            }

            val modes = layoutData.getJSONObject("modes")
            val modeKey = mode.name.lowercase()

            if (!modes.has(modeKey)) {
                throw IllegalStateException("Layout data missing mode: $modeKey")
            }

            val modeData = modes.getJSONObject(modeKey)
            val rowsArray = modeData.getJSONArray("rows")

            val rows = mutableListOf<List<KeyboardKey>>()

            for (i in 0 until rowsArray.length()) {
                val rowArray = rowsArray.getJSONArray(i)
                val row = mutableListOf<KeyboardKey>()

                for (j in 0 until rowArray.length()) {
                    val keyData = rowArray.getJSONObject(j)
                    val key = parseKeyFromJson(keyData, currentAction)
                    row.add(key)
                }

                rows.add(row)
            }

            return KeyboardLayout(mode = mode, rows = rows)
        }

        private fun parseKeyFromJson(
            keyData: JSONObject,
            currentAction: KeyboardKey.ActionType,
        ): KeyboardKey =
            when (val type = keyData.getString("type")) {
                "character" -> {
                    val char = keyData.getString("char")
                    val keyType =
                        when (keyData.optString("keyType", "letter")) {
                            "letter" -> KeyboardKey.KeyType.LETTER
                            "number" -> KeyboardKey.KeyType.NUMBER
                            "symbol" -> KeyboardKey.KeyType.SYMBOL
                            "punctuation" -> KeyboardKey.KeyType.PUNCTUATION
                            else -> KeyboardKey.KeyType.LETTER
                        }
                    KeyboardKey.Character(char, keyType)
                }

                "action" -> {
                    val actionName = keyData.getString("action")
                    val actionType =
                        when (actionName) {
                            "shift" -> KeyboardKey.ActionType.SHIFT
                            "backspace" -> KeyboardKey.ActionType.BACKSPACE
                            "space" -> KeyboardKey.ActionType.SPACE
                            "mode_switch_numbers" -> KeyboardKey.ActionType.MODE_SWITCH_NUMBERS
                            "mode_switch_letters" -> KeyboardKey.ActionType.MODE_SWITCH_LETTERS
                            "mode_switch_symbols" -> KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS
                            "caps_lock" -> KeyboardKey.ActionType.CAPS_LOCK
                            "dynamic_action" -> currentAction
                            else -> KeyboardKey.ActionType.ENTER
                        }
                    KeyboardKey.Action(actionType)
                }

                else -> throw IllegalArgumentException("Unknown key type: $type")
            }

        private suspend fun tryLanguageFallback(
            context: Context,
            locale: ULocale,
            mode: KeyboardMode,
            currentAction: KeyboardKey.ActionType,
        ): KeyboardLayout =
            withContext(Dispatchers.IO) {
                val languageOnly = locale.language

                if (languageOnly != locale.toLanguageTag() && !shouldSkipLocale(languageOnly)) {
                    return@withContext try {
                        val layoutData = loadLayoutDataFromAssets(context, languageOnly)
                        parseLayoutForMode(layoutData, mode, currentAction)
                    } catch (_: Exception) {
                        handleAssetError(languageOnly)
                        getFallbackLayout(mode, currentAction)
                    }
                }

                return@withContext getFallbackLayout(mode, currentAction)
            }

        private fun getFallbackLayout(
            mode: KeyboardMode,
            currentAction: KeyboardKey.ActionType,
        ): KeyboardLayout {
            val rows =
                when (mode) {
                    KeyboardMode.LETTERS -> getFallbackLettersLayout(currentAction)
                    KeyboardMode.NUMBERS -> getFallbackNumbersLayout(currentAction)
                    KeyboardMode.SYMBOLS -> getFallbackSymbolsLayout(currentAction)
                }
            return KeyboardLayout(mode = mode, rows = rows)
        }

        private fun getFallbackLettersLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> =
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
                },
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.LETTER)
                },
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.LETTER)
                },
                listOf(
                    KeyboardKey.Action(KeyboardKey.ActionType.SHIFT),
                    KeyboardKey.Character("z", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Character("x", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Character("c", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Character("v", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Character("b", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Character("n", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Character("m", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE),
                ),
                listOf<KeyboardKey>(
                    KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS),
                    KeyboardKey.Action(KeyboardKey.ActionType.SPACE),
                    KeyboardKey.Action(actionType),
                ),
            )

        private fun getFallbackNumbersLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> =
            listOf(
                listOf("1", "2", "3").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.NUMBER)
                },
                listOf("4", "5", "6").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.NUMBER)
                },
                listOf("7", "8", "9").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.NUMBER)
                },
                listOf(
                    KeyboardKey.Character(".", KeyboardKey.KeyType.PUNCTUATION),
                    KeyboardKey.Character("0", KeyboardKey.KeyType.NUMBER),
                    KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE),
                ),
                listOf<KeyboardKey>(
                    KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_LETTERS),
                    KeyboardKey.Action(KeyboardKey.ActionType.SPACE),
                    KeyboardKey.Action(actionType),
                ),
            )

        private fun getFallbackSymbolsLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> =
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
                },
                listOf("!", "@", "#", "$", "%", "^", "&", "*").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
                },
                listOf("-", "_", "=", "+", "[", "]", "{", "}").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
                },
                listOf(";", ":", "'", "\"", ",", ".", "<", ">").map {
                    KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
                },
                listOf<KeyboardKey>(
                    KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_NUMBERS),
                    KeyboardKey.Action(KeyboardKey.ActionType.SPACE),
                    KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE),
                    KeyboardKey.Action(actionType),
                ),
            )

        private fun shouldSkipLocale(localeTag: String): Boolean {
            val errorCount = errorTracker.getErrorCount(localeTag)
            val lastError = errorTracker.getLastErrorTime(localeTag)
            val now = System.currentTimeMillis()

            return errorCount >= maxLayoutRetries && (now - lastError) < layoutErrorCooldownMs
        }

        private fun handleAssetError(localeTag: String) {
            val errorCount = errorTracker.recordError(localeTag)

            if (errorCount >= maxLayoutRetries) {
                failedLocales.add(localeTag)
            }
        }

        /**
         * Clears all caches and error state.
         *
         * Call when changing keyboard settings or on memory pressure.
         */
        fun cleanup() {
            layoutCache.invalidateAll()
            failedLocales.clear()
            errorTracker.clear()
        }
    }
