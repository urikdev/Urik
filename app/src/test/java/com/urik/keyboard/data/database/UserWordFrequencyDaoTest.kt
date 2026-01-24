@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserWordFrequencyDaoTest {
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: UserWordFrequencyDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.userWordFrequencyDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `incrementFrequency inserts new word on first call`() =
        runTest {
            dao.incrementFrequency("en", "hello", System.currentTimeMillis())

            val word = dao.findWord("en", "hello")
            assertNotNull(word)
            assertEquals(1, word?.frequency)
            assertEquals("hello", word?.wordNormalized)
        }

    @Test
    fun `incrementFrequency increments existing word`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementFrequency("en", "hello", timestamp)
            dao.incrementFrequency("en", "hello", timestamp + 1000)
            dao.incrementFrequency("en", "hello", timestamp + 2000)

            val word = dao.findWord("en", "hello")
            assertNotNull(word)
            assertEquals(3, word?.frequency)
        }

    @Test
    fun `incrementFrequency updates lastUsed timestamp`() =
        runTest {
            val firstTime = 1000L
            val secondTime = 5000L

            dao.incrementFrequency("en", "test", firstTime)
            dao.incrementFrequency("en", "test", secondTime)

            val word = dao.findWord("en", "test")
            assertEquals(secondTime, word?.lastUsed)
        }

    @Test
    fun `incrementFrequency is language-specific`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementFrequency("en", "hello", timestamp)
            dao.incrementFrequency("de", "hello", timestamp)

            val enWord = dao.findWord("en", "hello")
            val deWord = dao.findWord("de", "hello")

            assertEquals(1, enWord?.frequency)
            assertEquals(1, deWord?.frequency)
        }

    @Test
    fun `findWords returns frequencies for multiple words`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementFrequency("en", "hello", timestamp)
            dao.incrementFrequency("en", "hello", timestamp)
            dao.incrementFrequency("en", "world", timestamp)
            dao.incrementFrequency("en", "test", timestamp)
            dao.incrementFrequency("en", "test", timestamp)
            dao.incrementFrequency("en", "test", timestamp)

            val words = dao.findWords("en", listOf("hello", "world", "test", "missing"))

            assertEquals(3, words.size)
            val freqMap = words.associate { it.wordNormalized to it.frequency }
            assertEquals(2, freqMap["hello"])
            assertEquals(1, freqMap["world"])
            assertEquals(3, freqMap["test"])
        }

    @Test
    fun `findWord returns null for nonexistent word`() =
        runTest {
            val word = dao.findWord("en", "nonexistent")
            assertNull(word)
        }

    @Test
    fun `clearLanguage removes all words for language`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementFrequency("en", "hello", timestamp)
            dao.incrementFrequency("en", "world", timestamp)
            dao.incrementFrequency("de", "hallo", timestamp)

            val deleted = dao.clearLanguage("en")

            assertEquals(2, deleted)
            assertNull(dao.findWord("en", "hello"))
            assertNull(dao.findWord("en", "world"))
            assertNotNull(dao.findWord("de", "hallo"))
        }

    @Test
    fun `clearAll removes all words`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementFrequency("en", "hello", timestamp)
            dao.incrementFrequency("de", "hallo", timestamp)
            dao.incrementFrequency("es", "hola", timestamp)

            val deleted = dao.clearAll()

            assertEquals(3, deleted)
            assertEquals(0, dao.getTotalCount())
        }

    @Test
    fun `getMostFrequentWords returns top N by frequency`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementFrequency("en", "common", timestamp)
            dao.incrementFrequency("en", "common", timestamp)
            dao.incrementFrequency("en", "common", timestamp)
            dao.incrementFrequency("en", "medium", timestamp)
            dao.incrementFrequency("en", "medium", timestamp)
            dao.incrementFrequency("en", "rare", timestamp)

            val top2 = dao.getMostFrequentWords("en", 2)

            assertEquals(2, top2.size)
            assertEquals("common", top2[0].wordNormalized)
            assertEquals(3, top2[0].frequency)
            assertEquals("medium", top2[1].wordNormalized)
            assertEquals(2, top2[1].frequency)
        }

    @Test
    fun `getTotalCount returns correct count`() =
        runTest {
            val timestamp = System.currentTimeMillis()

            dao.incrementFrequency("en", "one", timestamp)
            dao.incrementFrequency("en", "two", timestamp)
            dao.incrementFrequency("de", "drei", timestamp)

            assertEquals(3, dao.getTotalCount())
        }

    @Test
    fun `unique index prevents duplicate language-word pairs`() =
        runTest {
            val word1 =
                UserWordFrequency(
                    languageTag = "en",
                    wordNormalized = "test",
                    frequency = 1,
                    lastUsed = 1000L,
                )

            dao.insertWord(word1)
            val result = dao.insertWord(word1)

            assertEquals(-1L, result)
            assertEquals(1, dao.getTotalCount())
        }
}
