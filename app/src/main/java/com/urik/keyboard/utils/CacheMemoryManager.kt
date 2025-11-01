package com.urik.keyboard.utils

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized cache memory management with LRU eviction and memory pressure handling.
 *
 * @see ManagedCache for LRU cache implementation details
 */
@Suppress("DEPRECATION")
@Singleton
class CacheMemoryManager
    @Inject
    constructor(
        private val context: Context,
    ) : ComponentCallbacks2 {
        private val managedCaches = ConcurrentHashMap<String, ManagedCache<*, *>>()
        private val memoryMonitoringScope = CoroutineScope(Dispatchers.Default)
        private var memoryMonitoringJob: Job? = null

        private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        private val memoryInfo = ActivityManager.MemoryInfo()
        private val lowMemoryThresholdMb = 50L
        private val criticalMemoryThresholdMb = 20L

        private val defaultMaxSize = 100

        init {
            context.registerComponentCallbacks(this)
            startMemoryMonitoring()
        }

        /**
         * Creates managed LRU cache with automatic memory pressure handling.
         *
         * Created cache is automatically registered for memory trimming based on
         * [criticalCacheNames] classification.
         *
         * @param name Unique cache identifier for memory pressure classification
         * @param maxSize Maximum entries before LRU eviction (must be > 0)
         * @param onEvict Optional callback invoked when entry evicted (for cleanup)
         * @return Thread-safe LRU cache with hit/miss tracking
         * @throws IllegalArgumentException if maxSize <= 0
         */
        fun <K : Any, V : Any> createCache(
            name: String,
            maxSize: Int = defaultMaxSize,
            onEvict: ((K, V) -> Unit)? = null,
        ): ManagedCache<K, V> {
            require(maxSize > 0) { "Cache maxSize must be positive, got: $maxSize" }
            val cache = ManagedCache(name, maxSize, onEvict)
            managedCaches[name] = cache
            return cache
        }

        private fun startMemoryMonitoring() {
            memoryMonitoringJob?.cancel()
            memoryMonitoringJob =
                memoryMonitoringScope.launch {
                    while (true) {
                        try {
                            checkMemoryPressure()
                            delay(30000L)
                        } catch (_: Exception) {
                            delay(60000L)
                        }
                    }
                }
        }

        private fun checkMemoryPressure() {
            activityManager.getMemoryInfo(memoryInfo)
            val availableMemoryMb = memoryInfo.availMem / (1024 * 1024)

            when {
                availableMemoryMb < criticalMemoryThresholdMb -> {
                    onLowMemory()
                }
                availableMemoryMb < lowMemoryThresholdMb -> {
                    onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                }
            }
        }

        private val criticalCacheNames =
            setOf(
                "dictionary_cache",
                "generation_cache",
                "suggestion_cache",
                "learned_words_cache",
                "spell_suggestions",
            )

        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                managedCaches.forEach { (name, cache) ->
                    if (name in criticalCacheNames) {
                        cache.trimToSize((cache.maxSize * 0.7).toInt())
                    } else {
                        cache.trimToSize(cache.maxSize / 2)
                    }
                }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
        }

        @Deprecated("System calls this for backwards compatibility")
        override fun onLowMemory() {
            managedCaches.values.forEach { cache ->
                cache.trimToSize(cache.maxSize / 4)
            }
        }

        fun forceCleanup() {
            managedCaches.values.forEach { it.invalidateAll() }
        }

        fun cleanup() {
            memoryMonitoringJob?.cancel()
            context.unregisterComponentCallbacks(this)
            managedCaches.values.forEach { it.invalidateAll() }
            managedCaches.clear()
        }
    }

/**
 * Thread-safe LRU cache with hit/miss tracking and eviction callbacks.
 *
 * @param K Cache key type (must be non-nullable)
 * @param V Cache value type (must be non-nullable)
 * @param name Cache identifier for debugging
 * @param maxSize Maximum entries before LRU eviction
 * @param onEvict Optional callback for cleanup when entry removed
 */
class ManagedCache<K : Any, V : Any>(
    val name: String,
    val maxSize: Int,
    private val onEvict: ((K, V) -> Unit)?,
) {
    private val cache =
        object : LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                val shouldRemove = size > maxSize
                if (shouldRemove && eldest != null) {
                    onEvict?.invoke(eldest.key, eldest.value)
                }
                return shouldRemove
            }
        }

    @Volatile
    var hits = 0L
        private set

    @Volatile
    var misses = 0L
        private set

    @Synchronized
    fun getIfPresent(key: K): V? {
        val value = cache[key]
        if (value != null) {
            hits++
        } else {
            misses++
        }
        return value
    }

    @Synchronized
    fun put(
        key: K,
        value: V,
    ): V? = cache.put(key, value)

    @Synchronized
    fun invalidate(key: K): V? = cache.remove(key)

    @Synchronized
    fun invalidateAll() {
        cache.clear()
        hits = 0L
        misses = 0L
    }

    @Synchronized
    fun size(): Int = cache.size

    @Synchronized
    fun asMap(): Map<K, V> = cache.toMap()

    @Synchronized
    fun hitRate(): Int {
        val total = hits + misses
        return if (total > 0) ((hits * 100) / total).toInt() else 0
    }

    /**
     * Reduces cache size to target by evicting eldest entries.
     *
     * If current size <= targetSize, no-op. Otherwise removes (size - targetSize)
     * least recently used entries, invoking onEvict for each.
     *
     * @param targetSize Desired cache size after trimming
     */
    @Synchronized
    fun trimToSize(targetSize: Int) {
        if (targetSize >= cache.size) return

        val toRemove = cache.size - targetSize
        var removed = 0

        val iterator = cache.entries.iterator()
        while (iterator.hasNext() && removed < toRemove) {
            val entry = iterator.next()
            onEvict?.invoke(entry.key, entry.value)
            iterator.remove()
            removed++
        }
    }
}
