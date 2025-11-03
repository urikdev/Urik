@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests ManagedCache LRU eviction, hit/miss tracking, and memory management.
 */
class ManagedCacheTest {
    private lateinit var cache: ManagedCache<String, String>

    @Before
    fun setup() {
        cache = ManagedCache("test_cache", maxSize = 3, onEvict = null)
    }

    @Test
    fun `put adds entry to cache`() {
        cache.put("key1", "value1")

        assertEquals("value1", cache.getIfPresent("key1"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `put multiple entries`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        assertEquals(3, cache.size())
        assertEquals("value1", cache.getIfPresent("key1"))
        assertEquals("value2", cache.getIfPresent("key2"))
        assertEquals("value3", cache.getIfPresent("key3"))
    }

    @Test
    fun `put returns previous value when key exists`() {
        cache.put("key", "old")
        val previous = cache.put("key", "new")

        assertEquals("old", previous)
        assertEquals("new", cache.getIfPresent("key"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `put returns null when key is new`() {
        val previous = cache.put("key", "value")

        assertNull(previous)
    }

    @Test
    fun `getIfPresent returns null for missing key`() {
        val result = cache.getIfPresent("missing")

        assertNull(result)
    }

    @Test
    fun `LRU eviction removes oldest entry when exceeding maxSize`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")
        cache.put("key4", "value4")

        assertEquals(3, cache.size())
        assertNull(cache.getIfPresent("key1"))
        assertEquals("value2", cache.getIfPresent("key2"))
        assertEquals("value3", cache.getIfPresent("key3"))
        assertEquals("value4", cache.getIfPresent("key4"))
    }

    @Test
    fun `accessing entry refreshes its position in LRU`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        cache.getIfPresent("key1")

        cache.put("key4", "value4")

        assertEquals(3, cache.size())
        assertEquals("value1", cache.getIfPresent("key1"))
        assertNull(cache.getIfPresent("key2"))
        assertEquals("value3", cache.getIfPresent("key3"))
        assertEquals("value4", cache.getIfPresent("key4"))
    }

    @Test
    fun `updating entry refreshes its position in LRU`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        cache.put("key1", "updated1")

        cache.put("key4", "value4")

        assertEquals(3, cache.size())
        assertEquals("updated1", cache.getIfPresent("key1"))
        assertNull(cache.getIfPresent("key2"))
        assertEquals("value3", cache.getIfPresent("key3"))
        assertEquals("value4", cache.getIfPresent("key4"))
    }

    @Test
    fun `getIfPresent tracks hits`() {
        cache.put("key", "value")

        cache.getIfPresent("key")
        cache.getIfPresent("key")

        assertEquals(2L, cache.hits)
        assertEquals(0L, cache.misses)
    }

    @Test
    fun `getIfPresent tracks misses`() {
        cache.getIfPresent("missing1")
        cache.getIfPresent("missing2")

        assertEquals(0L, cache.hits)
        assertEquals(2L, cache.misses)
    }

    @Test
    fun `getIfPresent tracks hits and misses correctly`() {
        cache.put("exists", "value")

        cache.getIfPresent("exists")
        cache.getIfPresent("missing1")
        cache.getIfPresent("exists")
        cache.getIfPresent("missing2")

        assertEquals(2L, cache.hits)
        assertEquals(2L, cache.misses)
    }

    @Test
    fun `hitRate calculates correctly`() {
        cache.put("key", "value")

        cache.getIfPresent("key")
        cache.getIfPresent("key")
        cache.getIfPresent("missing")

        assertEquals(66, cache.hitRate())
    }

    @Test
    fun `hitRate returns zero for empty cache`() {
        assertEquals(0, cache.hitRate())
    }

    @Test
    fun `hitRate returns 100 for all hits`() {
        cache.put("key", "value")

        cache.getIfPresent("key")
        cache.getIfPresent("key")

        assertEquals(100, cache.hitRate())
    }

    @Test
    fun `hitRate returns zero for all misses`() {
        cache.getIfPresent("missing1")
        cache.getIfPresent("missing2")

        assertEquals(0, cache.hitRate())
    }

    @Test
    fun `invalidate removes entry`() {
        cache.put("key", "value")

        val removed = cache.invalidate("key")

        assertEquals("value", removed)
        assertNull(cache.getIfPresent("key"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `invalidate returns null for missing key`() {
        val removed = cache.invalidate("missing")

        assertNull(removed)
    }

    @Test
    fun `invalidate does not affect other entries`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.invalidate("key1")

        assertEquals(1, cache.size())
        assertNull(cache.getIfPresent("key1"))
        assertEquals("value2", cache.getIfPresent("key2"))
    }

    @Test
    fun `invalidateAll clears cache`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        cache.invalidateAll()

        assertEquals(0, cache.size())
        assertNull(cache.getIfPresent("key1"))
        assertNull(cache.getIfPresent("key2"))
        assertNull(cache.getIfPresent("key3"))
    }

    @Test
    fun `invalidateAll resets hit and miss counters`() {
        cache.put("key", "value")
        cache.getIfPresent("key")
        cache.getIfPresent("missing")

        cache.invalidateAll()

        assertEquals(0L, cache.hits)
        assertEquals(0L, cache.misses)
    }

    @Test
    fun `trimToSize removes oldest entries`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        cache.trimToSize(1)

        assertEquals(1, cache.size())
        assertNull(cache.getIfPresent("key1"))
        assertNull(cache.getIfPresent("key2"))
        assertEquals("value3", cache.getIfPresent("key3"))
    }

    @Test
    fun `trimToSize does nothing when targetSize greater than current size`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.trimToSize(5)

        assertEquals(2, cache.size())
        assertEquals("value1", cache.getIfPresent("key1"))
        assertEquals("value2", cache.getIfPresent("key2"))
    }

    @Test
    fun `trimToSize respects LRU order`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        cache.getIfPresent("key1")

        cache.trimToSize(2)

        assertEquals(2, cache.size())
        assertEquals("value1", cache.getIfPresent("key1"))
        assertNull(cache.getIfPresent("key2"))
        assertEquals("value3", cache.getIfPresent("key3"))
    }

    @Test
    fun `trimToSize to zero clears cache`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.trimToSize(0)

        assertEquals(0, cache.size())
    }

    @Test
    fun `onEvict called when LRU evicts entry`() {
        val evicted = mutableListOf<Pair<String, String>>()
        val cacheWithCallback =
            ManagedCache<String, String>(
                "test",
                maxSize = 2,
                onEvict = { k, v -> evicted.add(k to v) },
            )

        cacheWithCallback.put("key1", "value1")
        cacheWithCallback.put("key2", "value2")
        cacheWithCallback.put("key3", "value3")

        assertEquals(1, evicted.size)
        assertEquals("key1" to "value1", evicted[0])
    }

    @Test
    fun `onEvict called for each trimmed entry`() {
        val evicted = mutableListOf<Pair<String, String>>()
        val cacheWithCallback =
            ManagedCache<String, String>(
                "test",
                maxSize = 3,
                onEvict = { k, v -> evicted.add(k to v) },
            )

        cacheWithCallback.put("key1", "value1")
        cacheWithCallback.put("key2", "value2")
        cacheWithCallback.put("key3", "value3")

        cacheWithCallback.trimToSize(1)

        assertEquals(2, evicted.size)
        assertEquals("key1" to "value1", evicted[0])
        assertEquals("key2" to "value2", evicted[1])
    }

    @Test
    fun `onEvict not called when null`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")
        cache.put("key4", "value4")

        assertEquals(3, cache.size())
    }

    @Test
    fun `asMap returns copy of cache contents`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        val map = cache.asMap()

        assertEquals(2, map.size)
        assertEquals("value1", map["key1"])
        assertEquals("value2", map["key2"])
    }

    @Test
    fun `asMap returns empty map for empty cache`() {
        val map = cache.asMap()

        assertTrue(map.isEmpty())
    }

    @Test
    fun `maxSize of 1 keeps only most recent entry`() {
        val singleCache = ManagedCache<String, String>("single", maxSize = 1, onEvict = null)

        singleCache.put("key1", "value1")
        singleCache.put("key2", "value2")

        assertEquals(1, singleCache.size())
        assertNull(singleCache.getIfPresent("key1"))
        assertEquals("value2", singleCache.getIfPresent("key2"))
    }

    @Test
    fun `large maxSize handles many entries`() {
        val largeCache = ManagedCache<Int, String>("large", maxSize = 1000, onEvict = null)

        for (i in 1..1000) {
            largeCache.put(i, "value$i")
        }

        assertEquals(1000, largeCache.size())
        assertEquals("value1", largeCache.getIfPresent(1))
        assertEquals("value1000", largeCache.getIfPresent(1000))
    }
}
