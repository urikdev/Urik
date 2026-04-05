package com.urik.keyboard.ui.keyboard.components

import com.urik.keyboard.utils.CacheMemoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PunctuationLoaderTest {
    private lateinit var loader: PunctuationLoader

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val cacheMemoryManager = CacheMemoryManager(context)
        loader = PunctuationLoader(context, cacheMemoryManager)
    }

    @Test
    fun `loadPunctuation returns non-empty list for valid language`() {
        val result = loader.loadPunctuation("en")
        assertTrue("Expected non-empty punctuation list", result.isNotEmpty())
    }

    @Test
    fun `loadPunctuation returns English fallback for unknown language`() {
        val englishPunctuation = loader.loadPunctuation("en")
        val result = loader.loadPunctuation("xx_nonexistent")
        assertEquals(englishPunctuation, result)
    }

    @Test
    fun `loadPunctuation caches results across calls`() {
        val first = loader.loadPunctuation("en")
        val second = loader.loadPunctuation("en")
        assertTrue("Cached result should be same instance", first === second)
    }

    @Test
    fun `cleanup invalidates cache without throwing`() {
        loader.loadPunctuation("en")
        loader.cleanup()
        // After cleanup, next call should reload (not crash)
        val result = loader.loadPunctuation("en")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `DEFAULT_PUNCTUATION contains expected base punctuation`() {
        val defaults = PunctuationLoader.DEFAULT_PUNCTUATION
        assertTrue(defaults.contains("."))
        assertTrue(defaults.contains(","))
        assertTrue(defaults.contains("?"))
        assertTrue(defaults.contains("!"))
    }
}
