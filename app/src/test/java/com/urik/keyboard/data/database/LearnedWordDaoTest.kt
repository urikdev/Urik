@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [LearnedWordDao] CRUD operations, FTS queries, and language-specific filtering.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedWordDaoTest {
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: LearnedWordDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.learnedWordDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `insertWord inserts new word`() =
        runTest {
            val word = createTestWord("hello", "hello", "en")

            val id = dao.insertWord(word)

            assertTrue(id > 0)
            val retrieved = dao.findExactWord("en", "hello")
            assertNotNull(retrieved)
            assertEquals("hello", retrieved?.word)
        }

    @Test
    fun `insertWord with IGNORE strategy skips duplicate`() =
        runTest {
            val word1 = createTestWord("hello", "hello", "en")
            val word2 = createTestWord("HELLO", "hello", "en")

            dao.insertWord(word1)
            val id2 = dao.insertWord(word2)

            assertEquals(-1L, id2)
            assertEquals(1, dao.getWordCount("en"))
        }

    @Test
    fun `updateWord modifies existing word`() =
        runTest {
            val word = createTestWord("hello", "hello", "en", frequency = 1)
            dao.insertWord(word)

            val existing = dao.findExactWord("en", "hello")!!
            val updated = existing.copy(frequency = 5)
            dao.updateWord(updated)

            val retrieved = dao.findExactWord("en", "hello")
            assertEquals(5, retrieved?.frequency)
        }

    @Test
    fun `upsertWord inserts new word`() =
        runTest {
            val word = createTestWord("hello", "hello", "en")

            dao.upsertWord(word)

            val retrieved = dao.findExactWord("en", "hello")
            assertNotNull(retrieved)
            assertEquals(1, retrieved?.frequency)
        }

    @Test
    fun `upsertWord updates existing word`() =
        runTest {
            val word = createTestWord("hello", "hello", "en", frequency = 1)
            dao.insertWord(word)

            val existing = dao.findExactWord("en", "hello")!!
            val updated = existing.copy(frequency = 5)
            dao.upsertWord(updated)

            val retrieved = dao.findExactWord("en", "hello")
            assertEquals(5, retrieved?.frequency)
        }

    @Test
    fun `learnWord inserts new word with FTS`() =
        runTest {
            val word = createTestWord("hello", "hello", "en")

            dao.learnWord(word)

            val retrieved = dao.findExactWord("en", "hello")
            assertNotNull(retrieved)
            assertEquals(1, retrieved?.frequency)
        }

    @Test
    fun `learnWord increments frequency for existing word`() =
        runTest {
            val word = createTestWord("hello", "hello", "en", frequency = 1)
            dao.learnWord(word)

            dao.learnWord(word)

            val retrieved = dao.findExactWord("en", "hello")
            assertEquals(2, retrieved?.frequency)
        }

    @Test
    fun `learnWord updates last_used timestamp`() =
        runTest {
            val word = createTestWord("hello", "hello", "en")
            dao.learnWord(word)

            Thread.sleep(10)
            dao.learnWord(word)

            val retrieved = dao.findExactWord("en", "hello")!!
            assertTrue(retrieved.lastUsed > word.lastUsed)
        }

    @Test
    fun `learnWord preserves source field when incrementing`() =
        runTest {
            val word = createTestWord("hello", "hello", "en", source = WordSource.SWIPE_LEARNED)
            dao.learnWord(word)

            dao.learnWord(word)

            val retrieved = dao.findExactWord("en", "hello")!!
            assertEquals(WordSource.SWIPE_LEARNED, retrieved.source)
        }

    @Test
    fun `findExactWord returns matching word`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("world", "world", "en"))

            val result = dao.findExactWord("en", "hello")

            assertNotNull(result)
            assertEquals("hello", result?.word)
        }

    @Test
    fun `findExactWord returns null for missing word`() =
        runTest {
            val result = dao.findExactWord("en", "missing")

            assertNull(result)
        }

    @Test
    fun `findExactWord is case sensitive for normalized`() =
        runTest {
            dao.insertWord(createTestWord("Hello", "hello", "en"))

            val result = dao.findExactWord("en", "Hello")

            assertNull(result)
        }

    @Test
    fun `findWordsWithPrefix returns matching words`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en", frequency = 5))
            dao.insertWord(createTestWord("help", "help", "en", frequency = 3))
            dao.insertWord(createTestWord("world", "world", "en", frequency = 4))

            val results = dao.findWordsWithPrefix("en", "hel", 10)

            assertEquals(2, results.size)
            assertTrue(results.any { it.word == "hello" })
            assertTrue(results.any { it.word == "help" })
        }

    @Test
    fun `findWordsWithPrefix orders by frequency`() =
        runTest {
            dao.insertWord(createTestWord("help", "help", "en", frequency = 3))
            dao.insertWord(createTestWord("hello", "hello", "en", frequency = 5))

            val results = dao.findWordsWithPrefix("en", "hel", 10)

            assertEquals("hello", results[0].word)
            assertEquals("help", results[1].word)
        }

    @Test
    fun `findWordsWithPrefix respects limit`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("help", "help", "en"))
            dao.insertWord(createTestWord("helpful", "helpful", "en"))

            val results = dao.findWordsWithPrefix("en", "hel", 2)

            assertEquals(2, results.size)
        }

    @Test
    fun `findWordsWithPrefix filters by language`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("hej", "hej", "sv"))

            val results = dao.findWordsWithPrefix("en", "he", 10)

            assertEquals(1, results.size)
            assertEquals("hello", results[0].word)
        }

    @Test
    fun `getFastSuggestions returns only high frequency words`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en", frequency = 5))
            dao.insertWord(createTestWord("help", "help", "en", frequency = 1))

            val results = dao.getFastSuggestions("hel", "en", 10)

            assertEquals(1, results.size)
            assertEquals("hello", results[0].word)
        }

    @Test
    fun `getFastSuggestions orders by frequency and last_used`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en", frequency = 5, lastUsed = 100))
            dao.insertWord(createTestWord("help", "help", "en", frequency = 5, lastUsed = 200))

            val results = dao.getFastSuggestions("hel", "en", 10)

            assertEquals("help", results[0].word)
            assertEquals("hello", results[1].word)
        }

    @Test
    fun `findExistingWords returns only existing words`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("world", "world", "en"))

            val results = dao.findExistingWords("en", listOf("hello", "missing", "world"))

            assertEquals(2, results.size)
            assertTrue(results.contains("hello"))
            assertTrue(results.contains("world"))
            assertFalse(results.contains("missing"))
        }

    @Test
    fun `findExistingWords handles empty list`() =
        runTest {
            val results = dao.findExistingWords("en", emptyList())

            assertTrue(results.isEmpty())
        }

    @Test
    fun `findExistingWords filters by language`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("hej", "hej", "sv"))

            val results = dao.findExistingWords("en", listOf("hello", "hej"))

            assertEquals(1, results.size)
            assertEquals("hello", results[0])
        }

    @Test
    fun `wordExists returns true for existing word`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))

            assertTrue(dao.wordExists("hello", "en"))
        }

    @Test
    fun `wordExists returns false for missing word`() =
        runTest {
            assertFalse(dao.wordExists("missing", "en"))
        }

    @Test
    fun `removeWord deletes word`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))

            val rowsAffected = dao.removeWord("en", "hello")

            assertEquals(1, rowsAffected)
            assertNull(dao.findExactWord("en", "hello"))
        }

    @Test
    fun `removeWord returns 0 for missing word`() =
        runTest {
            val rowsAffected = dao.removeWord("en", "missing")

            assertEquals(0, rowsAffected)
        }

    @Test
    fun `removeWordComplete removes from both tables`() =
        runTest {
            val word = createTestWord("hello", "hello", "en")
            dao.learnWord(word)

            val rowsAffected = dao.removeWordComplete("en", "hello")

            assertEquals(1, rowsAffected)
            assertNull(dao.findExactWord("en", "hello"))
        }

    @Test
    fun `clearLanguage removes all words for language`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("world", "world", "en"))
            dao.insertWord(createTestWord("hej", "hej", "sv"))

            val rowsAffected = dao.clearLanguage("en")

            assertEquals(2, rowsAffected)
            assertEquals(0, dao.getWordCount("en"))
            assertEquals(1, dao.getWordCount("sv"))
        }

    @Test
    fun `cleanupLowFrequencyWords removes old single-use words`() =
        runTest {
            val old = System.currentTimeMillis() - 100000
            val recent = System.currentTimeMillis()

            dao.insertWord(createTestWord("old", "old", "en", frequency = 1, lastUsed = old))
            dao.insertWord(createTestWord("recent", "recent", "en", frequency = 1, lastUsed = recent))
            dao.insertWord(createTestWord("frequent", "frequent", "en", frequency = 5, lastUsed = old))

            val cutoff = System.currentTimeMillis() - 50000
            val rowsAffected = dao.cleanupLowFrequencyWords(cutoff)

            assertEquals(1, rowsAffected)
            assertNull(dao.findExactWord("en", "old"))
            assertNotNull(dao.findExactWord("en", "recent"))
            assertNotNull(dao.findExactWord("en", "frequent"))
        }

    @Test
    fun `getWordCount returns correct count`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("world", "world", "en"))

            assertEquals(2, dao.getWordCount("en"))
        }

    @Test
    fun `getTotalWordCount returns all words`() =
        runTest {
            dao.insertWord(createTestWord("hello", "hello", "en"))
            dao.insertWord(createTestWord("hej", "hej", "sv"))

            assertEquals(2, dao.getTotalWordCount())
        }

    @Test
    fun `getMostFrequentWords returns top words`() =
        runTest {
            dao.insertWord(createTestWord("rare", "rare", "en", frequency = 1))
            dao.insertWord(createTestWord("common", "common", "en", frequency = 10))
            dao.insertWord(createTestWord("medium", "medium", "en", frequency = 5))

            val results = dao.getMostFrequentWords("en", 2)

            assertEquals(2, results.size)
            assertEquals("common", results[0].word)
            assertEquals("medium", results[1].word)
        }

    @Test
    fun `getWordCountsBySource groups correctly`() =
        runTest {
            dao.insertWord(createTestWord("typed1", "typed1", "en", source = WordSource.USER_TYPED))
            dao.insertWord(createTestWord("typed2", "typed2", "en", source = WordSource.USER_TYPED))
            dao.insertWord(createTestWord("swiped1", "swiped1", "en", source = WordSource.SWIPE_LEARNED))

            val results = dao.getWordCountsBySource()

            assertEquals(2, results.size)
            val typedCount = results.find { it.source == WordSource.USER_TYPED }?.count
            val swipedCount = results.find { it.source == WordSource.SWIPE_LEARNED }?.count
            assertEquals(2, typedCount)
            assertEquals(1, swipedCount)
        }

    /**
     * Creates test word with defaults for frequency, source, and timestamps.
     */
    private fun createTestWord(
        word: String,
        wordNormalized: String,
        languageTag: String,
        frequency: Int = 1,
        source: WordSource = WordSource.USER_TYPED,
        lastUsed: Long = System.currentTimeMillis(),
    ): LearnedWord =
        LearnedWord.create(
            word = word,
            wordNormalized = wordNormalized,
            languageTag = languageTag,
            frequency = frequency,
            source = source,
            createdAt = System.currentTimeMillis(),
            lastUsed = lastUsed,
        )
}
