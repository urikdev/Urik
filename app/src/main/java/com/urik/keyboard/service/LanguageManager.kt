package com.urik.keyboard.service

import com.ibm.icu.lang.UScript
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.ULocale
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Language metadata for keyboard input.
 */
data class LanguageInfo(
    val languageTag: String,
    val displayName: String,
    val nativeName: String,
    val isActive: Boolean,
    val isPrimary: Boolean,
)

/**
 * Manages active keyboard languages and text normalization.
 *
 * Responsibilities:
 * - Tracks current input language from settings
 * - Normalizes text for language-agnostic comparison (NFD + lowercase for applicable scripts)
 * - Caches normalization results (100 entries, 30min TTL)
 *
 * Normalization strategy:
 * - Latin/Cyrillic/Greek/Armenian → NFD + lowercase
 * - Other scripts (CJK, Arabic, etc) → NFD only
 */
@Singleton
class LanguageManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        scopeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        companion object {
            private const val MAX_NORMALIZATION_CACHE_SIZE = 100
            private const val CACHE_CLEANUP_INTERVAL_MS = 600000L
            private const val CACHE_ENTRY_TTL_MS = 1800000L
        }

        private val scope = CoroutineScope(scopeDispatcher + SupervisorJob())

        private val _currentLanguage = MutableStateFlow<LanguageInfo?>(null)
        val currentLanguage: StateFlow<LanguageInfo?> = _currentLanguage.asStateFlow()

        private val availableLanguages = MutableStateFlow<List<LanguageInfo>>(emptyList())
        private val isInitialized = MutableStateFlow(false)

        private val normalizer = Normalizer2.getNFDInstance()

        private val normalizationCache = ConcurrentHashMap<String, CachedNormalizationResult>()
        private var lastCacheCleanupTime = 0L

        private data class CachedNormalizationResult(
            val normalizedText: String,
            val timestamp: Long,
        )

        /**
         * Initializes language manager from settings.
         *
         * Loads active languages and starts observing settings changes.
         * Idempotent - safe to call multiple times.
         *
         * @return Success or failure with exception
         */
        suspend fun initialize(): Result<Unit> =
            withContext(Dispatchers.IO) {
                if (isInitialized.value) return@withContext Result.success(Unit)

                return@withContext try {
                    val currentSettings = settingsRepository.settings.first()
                    val languages = createLanguageInfoList(currentSettings)
                    availableLanguages.value = languages
                    _currentLanguage.value = languages.find { it.languageTag == currentSettings.primaryLanguage }

                    settingsRepository.settings
                        .onEach { settings ->
                            val langs = createLanguageInfoList(settings)
                            availableLanguages.value = langs
                            _currentLanguage.value = langs.find { it.languageTag == settings.primaryLanguage }
                        }.launchIn(scope)

                    isInitialized.value = true
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private fun createLanguageInfoList(settings: KeyboardSettings): List<LanguageInfo> {
            val displayNames = KeyboardSettings.getLanguageDisplayNames()

            return settings.activeLanguages.mapNotNull { languageTag ->
                displayNames[languageTag]?.let { displayName ->
                    try {
                        val uLocale = ULocale.forLanguageTag(languageTag)
                        LanguageInfo(
                            languageTag = languageTag,
                            displayName = displayName,
                            nativeName = uLocale.getDisplayName(uLocale),
                            isActive = true,
                            isPrimary = languageTag == settings.primaryLanguage,
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        /**
         * Normalizes text for language-agnostic comparison.
         *
         * Uses current language's normalization rules. Cached for performance.
         *
         * @param text Text to normalize
         * @return Normalized text (NFD + lowercase for applicable scripts)
         */
        fun normalizeText(text: String): String {
            if (text.isBlank()) return text

            val cachedResult = getCachedNormalizationResult(text)
            if (cachedResult != null) {
                return cachedResult
            }

            val currentLang = _currentLanguage.value?.languageTag ?: "en"
            val normalizedText = normalizeTextForLanguage(text, currentLang)

            cacheNormalizationResult(text, normalizedText)

            return normalizedText
        }

        /**
         * Normalizes text for specific language.
         *
         * Strategy:
         * - Detect dominant script in text
         * - Latin/Cyrillic/Greek/Armenian → NFD + lowercase
         * - Other scripts → NFD only (no case conversion)
         *
         * @param text Text to normalize
         * @param languageTag Language code (e.g., "en", "sv")
         * @return Normalized text
         */
        fun normalizeTextForLanguage(
            text: String,
            languageTag: String,
        ): String =
            try {
                val uLocale = ULocale.forLanguageTag(languageTag)
                val normalized = normalizer.normalize(text)

                val script = detectDominantScript(normalized)
                when (script) {
                    UScript.LATIN, UScript.CYRILLIC, UScript.GREEK, UScript.ARMENIAN -> {
                        normalized.lowercase(uLocale.toLocale())
                    }
                    else -> {
                        normalized
                    }
                }
            } catch (_: Exception) {
                normalizer.normalize(text)
            }

        private fun detectDominantScript(text: String): Int {
            val scriptCounts = mutableMapOf<Int, Int>()

            text.codePoints().forEach { codepoint ->
                val script = UScript.getScript(codepoint)
                if (script != UScript.COMMON && script != UScript.INHERITED) {
                    scriptCounts[script] = scriptCounts.getOrDefault(script, 0) + 1
                }
            }

            return scriptCounts.maxByOrNull { it.value }?.key ?: UScript.LATIN
        }

        private fun getCachedNormalizationResult(text: String): String? {
            performPeriodicCacheCleanup()

            val cacheEntry = normalizationCache[text]
            return if (cacheEntry != null && !isExpired(cacheEntry.timestamp)) {
                cacheEntry.normalizedText
            } else {
                normalizationCache.remove(text)
                null
            }
        }

        private fun cacheNormalizationResult(
            text: String,
            normalizedText: String,
        ) {
            while (normalizationCache.size >= MAX_NORMALIZATION_CACHE_SIZE) {
                val oldestEntry = normalizationCache.entries.minByOrNull { it.value.timestamp }
                oldestEntry?.let { normalizationCache.remove(it.key) }
            }

            normalizationCache[text] = CachedNormalizationResult(normalizedText, System.currentTimeMillis())
        }

        private fun performPeriodicCacheCleanup() {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastCacheCleanupTime < CACHE_CLEANUP_INTERVAL_MS) {
                return
            }

            lastCacheCleanupTime = currentTime

            val iterator = normalizationCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (isExpired(entry.value.timestamp)) {
                    iterator.remove()
                }
            }
        }

        private fun isExpired(timestamp: Long): Boolean = System.currentTimeMillis() - timestamp > CACHE_ENTRY_TTL_MS

        /**
         * Clears all caches and cancels observers.
         *
         * Call when keyboard service destroyed.
         */
        fun cleanup() {
            scope.cancel()
            normalizationCache.clear()
        }
    }
