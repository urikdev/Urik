@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserWordBigramDaoTest {
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: UserWordBigramDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.userWordBigramDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `incrementBigram inserts new bigram`() =
        runTest {
            dao.incrementBigram("en", "hello", "world", System.currentTimeMillis())

            assertEquals(1, dao.getTotalCount())
            val predictions = dao.getPredictions("en", "hello", 10)
            assertEquals(1, predictions.size)
            assertEquals("world", predictions[0])
        }

    @Test
    fun `incrementBigram increments existing bigram`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigram("en", "hello", "world", timestamp)
            dao.incrementBigram("en", "hello", "world", timestamp + 1000)
            dao.incrementBigram("en", "hello", "world", timestamp + 2000)

            val bigrams = dao.getTopBigrams("en", 10)
            assertEquals(1, bigrams.size)
            assertEquals(3, bigrams[0].frequency)
        }

    @Test
    fun `incrementBigramBy inserts with amount`() =
        runTest {
            dao.incrementBigramBy("en", "hello", "world", 5, System.currentTimeMillis())

            val bigrams = dao.getTopBigrams("en", 10)
            assertEquals(1, bigrams.size)
            assertEquals(5, bigrams[0].frequency)
        }

    @Test
    fun `incrementBigramBy adds to existing frequency`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigram("en", "hello", "world", timestamp)
            dao.incrementBigramBy("en", "hello", "world", 4, timestamp + 1000)

            val bigrams = dao.getTopBigrams("en", 10)
            assertEquals(5, bigrams[0].frequency)
        }

    @Test
    fun `getPredictions returns ordered by frequency`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigramBy("en", "hello", "world", 10, timestamp)
            dao.incrementBigramBy("en", "hello", "there", 5, timestamp)
            dao.incrementBigramBy("en", "hello", "friend", 20, timestamp)

            val predictions = dao.getPredictions("en", "hello", 3)

            assertEquals(3, predictions.size)
            assertEquals("friend", predictions[0])
            assertEquals("world", predictions[1])
            assertEquals("there", predictions[2])
        }

    @Test
    fun `getPredictions respects limit`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigram("en", "the", "cat", timestamp)
            dao.incrementBigram("en", "the", "dog", timestamp)
            dao.incrementBigram("en", "the", "bird", timestamp)

            val predictions = dao.getPredictions("en", "the", 2)

            assertEquals(2, predictions.size)
        }

    @Test
    fun `getPredictions is language-scoped`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigram("en", "hello", "world", timestamp)
            dao.incrementBigram("de", "hello", "welt", timestamp)

            val enPredictions = dao.getPredictions("en", "hello", 10)
            val dePredictions = dao.getPredictions("de", "hello", 10)

            assertEquals(1, enPredictions.size)
            assertEquals("world", enPredictions[0])
            assertEquals(1, dePredictions.size)
            assertEquals("welt", dePredictions[0])
        }

    @Test
    fun `pruneStaleEntries removes frequency-1 entries older than cutoff`() =
        runTest {
            val now = System.currentTimeMillis()
            val old = now - 60 * 24 * 60 * 60 * 1000L

            dao.incrementBigram("en", "stale", "pair", old)
            dao.incrementBigram("en", "fresh", "pair", now)
            dao.incrementBigram("en", "frequent", "pair", old)
            dao.incrementBigram("en", "frequent", "pair", old)

            val pruned = dao.pruneStaleEntries(now - 30L * 24 * 60 * 60 * 1000)

            assertEquals(1, pruned)
            assertEquals(2, dao.getTotalCount())
        }

    @Test
    fun `enforceMaxRows removes lowest frequency entries`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigramBy("en", "a", "rare", 1, timestamp)
            dao.incrementBigramBy("en", "b", "medium", 5, timestamp)
            dao.incrementBigramBy("en", "c", "common", 10, timestamp)

            dao.enforceMaxRows(2)

            assertEquals(2, dao.getTotalCount())
        }

    @Test
    fun `enforceMaxRows is no-op when below limit`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigram("en", "hello", "world", timestamp)

            dao.enforceMaxRows(100)

            assertEquals(1, dao.getTotalCount())
        }

    @Test
    fun `clearLanguage removes all bigrams for language`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigram("en", "hello", "world", timestamp)
            dao.incrementBigram("en", "good", "morning", timestamp)
            dao.incrementBigram("de", "guten", "morgen", timestamp)

            val deleted = dao.clearLanguage("en")

            assertEquals(2, deleted)
            assertEquals(1, dao.getTotalCount())
        }

    @Test
    fun `clearAll removes all bigrams`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementBigram("en", "hello", "world", timestamp)
            dao.incrementBigram("de", "guten", "morgen", timestamp)

            val deleted = dao.clearAll()

            assertEquals(2, deleted)
            assertEquals(0, dao.getTotalCount())
        }
}
