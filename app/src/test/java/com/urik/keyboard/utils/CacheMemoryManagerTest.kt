@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.utils

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests CacheMemoryManager's MemoryPressureSubscriber registration,
 * unregistration, pressure level propagation, and cache trim behavior.
 *
 * Note: CacheMemoryManager starts a background memory monitoring coroutine
 * in init that can fire onTrimMemory asynchronously. Tests clear receivedLevels
 * before explicit onTrimMemory calls to isolate from background noise.
 */
@RunWith(RobolectricTestRunner::class)
class CacheMemoryManagerTest {
    private class TestSubscriber : MemoryPressureSubscriber {
        val receivedLevels = mutableListOf<Int>()

        override fun onMemoryPressure(level: Int) {
            receivedLevels.add(level)
        }
    }

    private class ThrowingSubscriber : MemoryPressureSubscriber {
        override fun onMemoryPressure(level: Int) {
            throw RuntimeException("Simulated subscriber failure")
        }
    }

    private lateinit var manager: CacheMemoryManager
    private lateinit var subscriber1: TestSubscriber
    private lateinit var subscriber2: TestSubscriber

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = CacheMemoryManager(context)
        subscriber1 = TestSubscriber()
        subscriber2 = TestSubscriber()
    }

    @After
    fun teardown() {
        manager.cleanup()
    }

    @Test
    fun `subscriber receives CRITICAL pressure level`() {
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(1, subscriber1.receivedLevels.size)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, subscriber1.receivedLevels[0])
    }

    @Test
    fun `subscriber receives MODERATE pressure level`() {
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertEquals(1, subscriber1.receivedLevels.size)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, subscriber1.receivedLevels[0])
    }

    @Test
    fun `subscriber receives LOW pressure level`() {
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(1, subscriber1.receivedLevels.size)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, subscriber1.receivedLevels[0])
    }

    @Test
    fun `subscriber receives COMPLETE pressure level`() {
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        assertEquals(1, subscriber1.receivedLevels.size)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, subscriber1.receivedLevels[0])
    }

    @Test
    fun `subscriber receives UI_HIDDEN pressure level`() {
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertEquals(1, subscriber1.receivedLevels.size)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, subscriber1.receivedLevels[0])
    }

    @Test
    fun `multiple subscribers all receive pressure callback`() {
        manager.registerPressureSubscriber(subscriber1)
        manager.registerPressureSubscriber(subscriber2)
        subscriber1.receivedLevels.clear()
        subscriber2.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(1, subscriber1.receivedLevels.size)
        assertEquals(1, subscriber2.receivedLevels.size)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, subscriber1.receivedLevels[0])
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, subscriber2.receivedLevels[0])
    }

    @Test
    fun `unregistered subscriber does not receive pressure callback`() {
        manager.registerPressureSubscriber(subscriber1)
        manager.registerPressureSubscriber(subscriber2)

        manager.unregisterPressureSubscriber(subscriber2)
        subscriber1.receivedLevels.clear()
        subscriber2.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(1, subscriber1.receivedLevels.size)
        assertTrue(subscriber2.receivedLevels.isEmpty())
    }

    @Test
    fun `subscriber receives multiple pressure events`() {
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(3, subscriber1.receivedLevels.size)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, subscriber1.receivedLevels[0])
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, subscriber1.receivedLevels[1])
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, subscriber1.receivedLevels[2])
    }

    @Test
    fun `throwing subscriber does not prevent other subscribers from receiving callback`() {
        val throwing = ThrowingSubscriber()
        manager.registerPressureSubscriber(throwing)
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(1, subscriber1.receivedLevels.size)
    }

    @Test
    fun `duplicate registration is idempotent`() {
        manager.registerPressureSubscriber(subscriber1)
        manager.registerPressureSubscriber(subscriber1)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(1, subscriber1.receivedLevels.size)
    }

    @Test
    fun `unregister nonexistent subscriber is safe`() {
        manager.registerPressureSubscriber(subscriber1)

        manager.unregisterPressureSubscriber(subscriber2)
        subscriber1.receivedLevels.clear()

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(1, subscriber1.receivedLevels.size)
    }

    @Test
    fun `CRITICAL trims all caches to 25 percent`() {
        val cache = manager.createCache<String, String>("test_cache", maxSize = 100)
        for (i in 1..100) {
            cache.put("key$i", "value$i")
        }

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(25, cache.size())
    }

    @Test
    fun `MODERATE trims non-critical caches to 50 percent`() {
        val cache = manager.createCache<String, String>("non_critical_cache", maxSize = 100)
        for (i in 1..100) {
            cache.put("key$i", "value$i")
        }

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertEquals(50, cache.size())
    }

    @Test
    fun `MODERATE trims critical caches to 70 percent`() {
        val cache = manager.createCache<String, String>("dictionary_cache", maxSize = 100)
        for (i in 1..100) {
            cache.put("key$i", "value$i")
        }

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertEquals(70, cache.size())
    }

    @Test
    fun `LOW trims non-critical caches to 80 percent`() {
        val cache = manager.createCache<String, String>("non_critical_cache", maxSize = 100)
        for (i in 1..100) {
            cache.put("key$i", "value$i")
        }

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(80, cache.size())
    }

    @Test
    fun `LOW does not trim critical caches`() {
        val cache = manager.createCache<String, String>("dictionary_cache", maxSize = 100)
        for (i in 1..100) {
            cache.put("key$i", "value$i")
        }

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(100, cache.size())
    }

    @Test
    fun `forceCleanup clears all caches`() {
        val cache1 = manager.createCache<String, String>("cache_a", maxSize = 50)
        val cache2 = manager.createCache<String, String>("cache_b", maxSize = 50)
        for (i in 1..50) {
            cache1.put("key$i", "value$i")
            cache2.put("key$i", "value$i")
        }

        manager.forceCleanup()

        assertEquals(0, cache1.size())
        assertEquals(0, cache2.size())
    }

    @Test
    fun `createCache rejects non-positive maxSize`() {
        var threw = false
        try {
            manager.createCache<String, String>("bad", maxSize = 0)
        } catch (_: IllegalArgumentException) {
            threw = true
        }

        assertTrue(threw)
    }

    @Test
    fun `createCache rejects negative maxSize`() {
        var threw = false
        try {
            manager.createCache<String, String>("bad", maxSize = -1)
        } catch (_: IllegalArgumentException) {
            threw = true
        }

        assertTrue(threw)
    }

    @Test
    fun `cleanup clears subscribers`() {
        manager.registerPressureSubscriber(subscriber1)

        manager.cleanup()
        subscriber1.receivedLevels.clear()

        manager = CacheMemoryManager(ApplicationProvider.getApplicationContext())
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertTrue(subscriber1.receivedLevels.isEmpty())
    }
}
