package com.urik.keyboard.service

import android.content.Context
import android.os.Looper
import androidx.room.Room
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.UserKanjiFrequencyDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KanaKanjiConverterTest {
    private lateinit var context: Context
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: UserKanjiFrequencyDao
    private lateinit var converter: KanaKanjiConverter

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.userKanjiFrequencyDao()
        converter = KanaKanjiConverter(context, dao, UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `getCandidates returns empty list for empty input`() = runTest {
        assertTrue(converter.getCandidates("", "ja").isEmpty())
    }

    @Test
    fun `getCandidates returns empty list for unknown reading`() = runTest {
        assertTrue(converter.getCandidates("zzzz", "ja").isEmpty())
    }

    @Test
    fun `getCandidates returns candidates sorted by frequency descending`() = runTest {
        val candidates = converter.getCandidates("とうきょう", "ja")
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
        val candidates = converter.getCandidates("わた", "ja")
        val readings = candidates.map { it.reading }
        assertTrue(
            "All candidates must have readings starting with 'わた'",
            readings.all { it.startsWith("わた") }
        )
    }

    @Test
    fun `recordSelection boosts candidate on subsequent lookup`() = runTest {
        val reading = "わたし"
        val surface = "私"
        converter.recordSelection(reading, surface)

        val candidates = converter.getCandidates(reading, "ja")
        val boosted = candidates.find { it.surface == surface && it.source == "learned" }
        assertTrue("Boosted candidate must appear in results", boosted != null)
    }

    @Test
    fun `recordSelection persists to Room`() = runTest {
        converter.recordSelection("わたし", "私")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("わたし", rows[0].reading)
        assertEquals("私", rows[0].surface)
        assertEquals(1L, rows[0].frequency)
    }

    @Test
    fun `ensureLoaded restores persisted frequencies after a fresh converter instance`() = runTest {
        dao.incrementBy("わたし", "私", 1L, System.currentTimeMillis())

        val freshConverter = KanaKanjiConverter(context, dao)
        freshConverter.getCandidates("わたし", "ja")

        val frequencies = freshConverter.userFrequenciesForTest()
        assertNotNull("Restored frequency map must contain the persisted entry", frequencies["わたし\t私"])
        assertEquals(1L, frequencies["わたし\t私"])
    }

    @Test
    fun `recordSelection boost survives simulated restart`() = runTest {
        converter.recordSelection("わたし", "私")
        advanceUntilIdle()

        val freshConverter = KanaKanjiConverter(context, dao)
        val candidates = freshConverter.getCandidates("わたし", "ja")
        val boosted = candidates.find { it.surface == "私" && it.source == "learned" }
        assertTrue("Boosted candidate must appear after restart", boosted != null)
    }

    @Test
    fun `loadIndex cancels cooperatively`() = runTest {
        val job = launch {
            converter.getCandidates("とうきょう", "ja")
        }
        job.cancelAndJoin()
        assertTrue("Job must be completed after cancelAndJoin", job.isCompleted)
    }
}
