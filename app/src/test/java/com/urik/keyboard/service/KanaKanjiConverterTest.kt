package com.urik.keyboard.service

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KanaKanjiConverterTest {
    private lateinit var context: Context
    private lateinit var converter: KanaKanjiConverter

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        converter = KanaKanjiConverter(context)
    }

    @Test
    fun `getCandidates returns empty list for empty input`() = runTest {
        assertTrue(converter.getCandidates("").isEmpty())
    }

    @Test
    fun `getCandidates returns empty list for unknown reading`() = runTest {
        assertTrue(converter.getCandidates("zzzz").isEmpty())
    }

    @Test
    fun `getCandidates returns candidates sorted by frequency descending`() = runTest {
        val candidates = converter.getCandidates("とうきょう")
        if (candidates.isNotEmpty()) {
            for (i in 0 until candidates.size - 1) {
                assertTrue(
                    "Candidates must be sorted by frequency descending",
                    candidates[i].frequency >= candidates[i + 1].frequency
                )
            }
        }
    }

    @Test
    fun `getCandidates returns results for prefix reading`() = runTest {
        val candidates = converter.getCandidates("わた")
        val readings = candidates.map { it.reading }
        assertTrue(
            "All candidates must have readings starting with 'わた'",
            readings.all { it.startsWith("わた") }
        )
    }

    @Test
    fun `incrementUserFrequency boosts candidate on subsequent lookup`() = runTest {
        val reading = "わたし"
        val surface = "私"
        converter.incrementUserFrequency(reading, surface)

        val candidates = converter.getCandidates(reading)
        val boosted = candidates.find { it.surface == surface && it.source == "learned" }
        assertTrue("Boosted candidate must appear in results", boosted != null)
    }
}
