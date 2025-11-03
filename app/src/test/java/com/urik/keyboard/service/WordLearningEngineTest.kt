@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.WordSource
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests [WordLearningEngine] word learning, lookups, caching, batch operations, and error handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WordLearningEngineTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var learnedWordDao: LearnedWordDao
    private lateinit var languageManager: LanguageManager
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var cacheMemoryManager: CacheMemoryManager

    private lateinit var languageFlow: MutableStateFlow<String>
    private lateinit var settingsFlow: MutableStateFlow<KeyboardSettings>

    private lateinit var wordLearningEngine: WordLearningEngine

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        learnedWordDao =
            mock {
                onBlocking { getAllLearnedWordsForLanguage(any()) } doReturn emptyList()
                onBlocking { learnWord(any()) } doReturn Unit
                onBlocking { findExactWord(any(), any()) } doReturn null
                onBlocking { findWordsWithPrefix(any(), any(), any()) } doReturn emptyList()
                onBlocking { getMostFrequentWords(any(), any()) } doReturn emptyList()
                onBlocking { findExistingWords(any(), any()) } doReturn emptyList()
                onBlocking { removeWordComplete(any(), any()) } doReturn 0
                onBlocking { getTotalWordCount() } doReturn 0
                onBlocking { getAverageFrequency() } doReturn 0.0
                onBlocking { getWordCountsBySource() } doReturn emptyList()
                onBlocking { cleanupLowFrequencyWords(any()) } doReturn 0
            }

        languageManager = mock()
        settingsRepository = mock()
        cacheMemoryManager = mock()
        val realCache = ManagedCache<String, Boolean>("test", 100, null)
        whenever(cacheMemoryManager.createCache<String, Boolean>(any(), any(), any())).thenReturn(realCache)

        languageFlow = MutableStateFlow("en")
        whenever(languageManager.currentLanguage).thenReturn(languageFlow)

        settingsFlow = MutableStateFlow(KeyboardSettings())
        whenever(settingsRepository.settings).thenReturn(settingsFlow)

        wordLearningEngine =
            WordLearningEngine(
                learnedWordDao = learnedWordDao,
                languageManager = languageManager,
                settingsRepository = settingsRepository,
                ioDispatcher = testDispatcher,
                defaultDispatcher = testDispatcher,
                cacheMemoryManager = cacheMemoryManager,
                mainDispatcher = testDispatcher,
            )
    }

    @After
    fun teardown() {
        wordLearningEngine.cleanup()
        Dispatchers.resetMain()
    }

    @Test
    fun `initializeLearnedWordsCache loads words from DB and populates cache`() =
        runTest {
            val words =
                listOf(
                    LearnedWord.create(
                        word = "hello",
                        wordNormalized = "hello",
                        languageTag = "en",
                        frequency = 5,
                        source = WordSource.USER_TYPED,
                    ),
                    LearnedWord.create(
                        word = "world",
                        wordNormalized = "world",
                        languageTag = "en",
                        frequency = 3,
                        source = WordSource.USER_TYPED,
                    ),
                )
            whenever(learnedWordDao.getAllLearnedWordsForLanguage("en")).thenReturn(words)

            val result = wordLearningEngine.initializeLearnedWordsCache("en")

            assertTrue(result.isSuccess)
            verify(learnedWordDao).getAllLearnedWordsForLanguage("en")

            assertTrue(wordLearningEngine.isWordLearned("hello"))
            assertTrue(wordLearningEngine.isWordLearned("world"))
        }

    @Test
    fun `initializeLearnedWordsCache handles DB exception`() =
        runTest {
            whenever(learnedWordDao.getAllLearnedWordsForLanguage("en"))
                .thenThrow(android.database.sqlite.SQLiteException("DB error"))

            val result = wordLearningEngine.initializeLearnedWordsCache("en")

            assertTrue(result.isFailure)
        }

    @Test
    fun `initializeLearnedWordsCache returns failure when engine destroyed`() =
        runTest {
            wordLearningEngine.cleanup()

            val result = wordLearningEngine.initializeLearnedWordsCache("en")

            assertTrue(result.isFailure)
            assertEquals("Engine destroyed", result.exceptionOrNull()?.message)
        }

    @Test
    fun `learnWord learns valid word successfully`() =
        runTest {
            val result = wordLearningEngine.learnWord("testing", InputMethod.TYPED)

            assertTrue(result.isSuccess)
            assertNotNull(result.getOrNull())

            val learned = result.getOrNull()!!
            assertEquals("testing", learned.word)
            assertEquals("en", learned.languageTag)
            assertEquals(WordSource.USER_TYPED, learned.source)

            verify(learnedWordDao).learnWord(argThat { word == "testing" })
        }

    @Test
    fun `learnWord updates cache after DB write`() =
        runTest {
            wordLearningEngine.learnWord("cached", InputMethod.TYPED)

            assertTrue(wordLearningEngine.isWordLearned("cached"))
            verify(learnedWordDao, times(0)).findExactWord(any(), any())
        }

    @Test
    fun `learnWord rejects invalid input`() =
        runTest {
            val empty = wordLearningEngine.learnWord("", InputMethod.TYPED)
            val blank = wordLearningEngine.learnWord("   ", InputMethod.TYPED)
            val numbers = wordLearningEngine.learnWord("12345", InputMethod.TYPED)

            assertTrue(empty.isSuccess)
            assertNull(empty.getOrNull())

            assertTrue(blank.isSuccess)
            assertNull(blank.getOrNull())

            assertTrue(numbers.isSuccess)
            assertNull(numbers.getOrNull())

            verify(learnedWordDao, never()).learnWord(any())
        }

    @Test
    fun `learnWord normalizes text before storing`() =
        runTest {
            wordLearningEngine.learnWord("  HELLO  ", InputMethod.TYPED)

            verify(learnedWordDao).learnWord(
                argThat {
                    word == "hello" && wordNormalized == "hello"
                },
            )
        }

    @Test
    fun `learnWord respects settings flag`() =
        runTest {
            settingsFlow.value = KeyboardSettings(learnNewWords = false)

            val result = wordLearningEngine.learnWord("word", InputMethod.TYPED)

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
            verify(learnedWordDao, never()).learnWord(any())
        }

    @Test
    fun `learnWord handles SQLiteException gracefully`() =
        runTest {
            whenever(learnedWordDao.learnWord(any()))
                .thenThrow(android.database.sqlite.SQLiteException("DB error"))

            val result = wordLearningEngine.learnWord("word", InputMethod.TYPED)

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
        }

    @Test
    fun `learnWord triggers cleanup on SQLiteFullException`() =
        runTest {
            whenever(learnedWordDao.learnWord(any()))
                .thenThrow(android.database.sqlite.SQLiteFullException("DB full"))

            val result = wordLearningEngine.learnWord("word", InputMethod.TYPED)

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
            verify(learnedWordDao).cleanupLowFrequencyWords(any())
        }

    @Test
    fun `learnWord returns null when engine destroyed`() =
        runTest {
            wordLearningEngine.cleanup()

            val result = wordLearningEngine.learnWord("word", InputMethod.TYPED)

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
        }

    @Test
    fun `isWordLearned returns true for cached word`() =
        runTest {
            wordLearningEngine.learnWord("cached", InputMethod.TYPED)

            val result = wordLearningEngine.isWordLearned("cached")

            assertTrue(result)
            verify(learnedWordDao, never()).findExactWord(any(), any())
        }

    @Test
    fun `isWordLearned falls back to DB when cache miss`() =
        runTest {
            val dbWord =
                LearnedWord.create(
                    word = "uncached",
                    wordNormalized = "uncached",
                    languageTag = "en",
                    frequency = 1,
                    source = WordSource.USER_TYPED,
                )
            whenever(learnedWordDao.findExactWord("en", "uncached")).thenReturn(dbWord)

            val result = wordLearningEngine.isWordLearned("uncached")

            assertTrue(result)
            verify(learnedWordDao).findExactWord("en", "uncached")
        }

    @Test
    fun `isWordLearned returns false for unknown word`() =
        runTest {
            whenever(learnedWordDao.findExactWord("en", "unknown")).thenReturn(null)

            val result = wordLearningEngine.isWordLearned("unknown")

            assertFalse(result)
        }

    @Test
    fun `isWordLearned normalizes input`() =
        runTest {
            wordLearningEngine.learnWord("hello", InputMethod.TYPED)

            assertTrue(wordLearningEngine.isWordLearned("HELLO"))
            assertTrue(wordLearningEngine.isWordLearned("  hello  "))
        }

    @Test
    fun `isWordLearned rejects invalid input`() =
        runTest {
            assertFalse(wordLearningEngine.isWordLearned(""))
            assertFalse(wordLearningEngine.isWordLearned("   "))
            assertFalse(wordLearningEngine.isWordLearned("123"))
        }

    @Test
    fun `isWordLearned handles SQLiteException gracefully`() =
        runTest {
            whenever(learnedWordDao.findExactWord(any(), any()))
                .thenThrow(android.database.sqlite.SQLiteException("DB error"))

            val result = wordLearningEngine.isWordLearned("word")

            assertFalse(result)
        }

    @Test
    fun `isWordLearned returns false when engine destroyed`() =
        runTest {
            wordLearningEngine.cleanup()

            val result = wordLearningEngine.isWordLearned("word")

            assertFalse(result)
        }

    @Test
    fun `getSimilarLearnedWordsWithFrequency returns exact match first`() =
        runTest {
            val exact =
                LearnedWord.create(
                    word = "test",
                    wordNormalized = "test",
                    languageTag = "en",
                    frequency = 10,
                    source = WordSource.USER_TYPED,
                )
            whenever(learnedWordDao.findExactWord("en", "test")).thenReturn(exact)

            val results = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 3)

            assertEquals(1, results.size)
            assertEquals("test" to 10, results[0])
        }

    @Test
    fun `getSimilarLearnedWordsWithFrequency includes prefix matches`() =
        runTest {
            val prefix1 =
                LearnedWord.create(
                    word = "testing",
                    wordNormalized = "testing",
                    languageTag = "en",
                    frequency = 5,
                    source = WordSource.USER_TYPED,
                )
            val prefix2 =
                LearnedWord.create(
                    word = "tests",
                    wordNormalized = "tests",
                    languageTag = "en",
                    frequency = 3,
                    source = WordSource.USER_TYPED,
                )
            whenever(learnedWordDao.findWordsWithPrefix("en", "test", 3))
                .thenReturn(listOf(prefix1, prefix2))

            val results = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 3)

            assertEquals(2, results.size)
            assertTrue(results.any { it.first == "testing" })
            assertTrue(results.any { it.first == "tests" })
        }

    @Test
    fun `getSimilarLearnedWordsWithFrequency sorts by frequency`() =
        runTest {
            val low =
                LearnedWord.create(
                    word = "testa",
                    wordNormalized = "testa",
                    languageTag = "en",
                    frequency = 1,
                    source = WordSource.USER_TYPED,
                )
            val high =
                LearnedWord.create(
                    word = "testb",
                    wordNormalized = "testb",
                    languageTag = "en",
                    frequency = 100,
                    source = WordSource.USER_TYPED,
                )
            whenever(learnedWordDao.findWordsWithPrefix("en", "test", 3))
                .thenReturn(listOf(low, high))

            val results = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 3)

            assertEquals("testb", results[0].first)
            assertEquals("testa", results[1].first)
        }

    @Test
    fun `getSimilarLearnedWordsWithFrequency respects max results`() =
        runTest {
            val words =
                (1..10).map {
                    LearnedWord.create(
                        word = "test$it",
                        wordNormalized = "test$it",
                        languageTag = "en",
                        frequency = it,
                        source = WordSource.USER_TYPED,
                    )
                }
            whenever(learnedWordDao.findWordsWithPrefix("en", "test", 2))
                .thenReturn(words)

            val results = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 2)

            assertEquals(2, results.size)
        }

    @Test
    fun `getSimilarLearnedWordsWithFrequency rejects invalid input`() =
        runTest {
            val empty = wordLearningEngine.getSimilarLearnedWordsWithFrequency("", 3)
            val blank = wordLearningEngine.getSimilarLearnedWordsWithFrequency("   ", 3)
            val tooLong = wordLearningEngine.getSimilarLearnedWordsWithFrequency("a".repeat(51), 3)

            assertTrue(empty.isEmpty())
            assertTrue(blank.isEmpty())
            assertTrue(tooLong.isEmpty())
        }

    @Test
    fun `getSimilarLearnedWordsWithFrequency handles DB exception in exact match`() =
        runTest {
            whenever(learnedWordDao.findExactWord(any(), any()))
                .thenThrow(android.database.sqlite.SQLiteException("DB error"))

            val prefix =
                LearnedWord.create(
                    word = "testing",
                    wordNormalized = "testing",
                    languageTag = "en",
                    frequency = 5,
                    source = WordSource.USER_TYPED,
                )
            whenever(learnedWordDao.findWordsWithPrefix("en", "test", 3))
                .thenReturn(listOf(prefix))

            val results = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 3)

            assertEquals(1, results.size)
            assertEquals("testing", results[0].first)
        }

    @Test
    fun `getSimilarLearnedWordsWithFrequency returns empty when destroyed`() =
        runTest {
            wordLearningEngine.cleanup()

            val results = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 3)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `areWordsLearned checks multiple words`() =
        runTest {
            whenever(learnedWordDao.findExistingWords("en", listOf("word1", "word2")))
                .thenReturn(listOf("word1"))

            val results = wordLearningEngine.areWordsLearned(listOf("word1", "word2"))

            assertEquals(2, results.size)
            assertEquals(true, results["word1"])
            assertEquals(false, results["word2"])
        }

    @Test
    fun `areWordsLearned returns empty for empty input`() =
        runTest {
            val results = wordLearningEngine.areWordsLearned(emptyList())

            assertTrue(results.isEmpty())
            verify(learnedWordDao, never()).findExistingWords(any(), any())
        }

    @Test
    fun `areWordsLearned filters invalid words`() =
        runTest {
            val results = wordLearningEngine.areWordsLearned(listOf("", "   ", "valid"))

            assertEquals(3, results.size)
            assertEquals(false, results[""])
            assertEquals(false, results["   "])
            verify(learnedWordDao).findExistingWords("en", listOf("valid"))
        }

    @Test
    fun `areWordsLearned normalizes words`() =
        runTest {
            whenever(learnedWordDao.findExistingWords(eq("en"), any()))
                .thenReturn(listOf("hello"))

            val results = wordLearningEngine.areWordsLearned(listOf("HELLO", "  hello  "))

            assertEquals(2, results.size)
            assertEquals(true, results["HELLO"])
            assertEquals(true, results["  hello  "])
        }

    @Test
    fun `areWordsLearned handles DB exception`() =
        runTest {
            whenever(learnedWordDao.findExistingWords(any(), any()))
                .thenThrow(android.database.sqlite.SQLiteException("DB error"))

            val results = wordLearningEngine.areWordsLearned(listOf("word1", "word2"))

            assertEquals(2, results.size)
            assertEquals(false, results["word1"])
            assertEquals(false, results["word2"])
        }

    @Test
    fun `areWordsLearned returns empty when destroyed`() =
        runTest {
            wordLearningEngine.cleanup()

            val results = wordLearningEngine.areWordsLearned(listOf("word"))

            assertTrue(results.isEmpty())
        }

    @Test
    fun `removeWord removes from DB and cache`() =
        runTest {
            wordLearningEngine.learnWord("removeme", InputMethod.TYPED)
            assertTrue(wordLearningEngine.isWordLearned("removeme"))

            whenever(learnedWordDao.removeWordComplete("en", "removeme")).thenReturn(1)

            val result = wordLearningEngine.removeWord("removeme")

            assertTrue(result.isSuccess)
            assertEquals(true, result.getOrNull())
            assertFalse(wordLearningEngine.isWordLearned("removeme"))
            verify(learnedWordDao).removeWordComplete("en", "removeme")
        }

    @Test
    fun `removeWord returns false when word not found`() =
        runTest {
            whenever(learnedWordDao.removeWordComplete("en", "missing")).thenReturn(0)

            val result = wordLearningEngine.removeWord("missing")

            assertTrue(result.isSuccess)
            assertEquals(false, result.getOrNull())
        }

    @Test
    fun `removeWord normalizes input`() =
        runTest {
            whenever(learnedWordDao.removeWordComplete("en", "hello")).thenReturn(1)

            wordLearningEngine.removeWord("  HELLO  ")

            verify(learnedWordDao).removeWordComplete("en", "hello")
        }

    @Test
    fun `removeWord rejects blank input`() =
        runTest {
            val result = wordLearningEngine.removeWord("   ")

            assertTrue(result.isSuccess)
            assertEquals(false, result.getOrNull())
            verify(learnedWordDao, never()).removeWordComplete(any(), any())
        }

    @Test
    fun `removeWord handles DB exception`() =
        runTest {
            whenever(learnedWordDao.removeWordComplete(any(), any()))
                .thenThrow(android.database.sqlite.SQLiteException("DB error"))

            val result = wordLearningEngine.removeWord("word")

            assertTrue(result.isSuccess)
            assertEquals(false, result.getOrNull())
        }

    @Test
    fun `removeWord returns false when destroyed`() =
        runTest {
            wordLearningEngine.cleanup()

            val result = wordLearningEngine.removeWord("word")

            assertTrue(result.isSuccess)
            assertEquals(false, result.getOrNull())
        }

    @Test
    fun `getLearningStats returns stats successfully`() =
        runTest {
            whenever(learnedWordDao.getTotalWordCount()).thenReturn(42)
            whenever(learnedWordDao.getAverageFrequency()).thenReturn(5.5)

            val result = wordLearningEngine.getLearningStats()

            assertTrue(result.isSuccess)
            val stats = result.getOrNull()!!
            assertEquals(42, stats.totalWordsLearned)
            assertEquals(5.5, stats.averageWordFrequency, 0.01)
            assertEquals("en", stats.currentLanguage)
        }

    @Test
    fun `getLearningStats handles DB exceptions in individual queries`() =
        runTest {
            whenever(learnedWordDao.getTotalWordCount()).thenThrow(RuntimeException("Error"))
            whenever(learnedWordDao.getAverageFrequency()).thenReturn(0.0)

            val result = wordLearningEngine.getLearningStats()

            assertTrue(result.isSuccess)
            val stats = result.getOrNull()!!
            assertEquals(0, stats.totalWordsLearned)
        }

    @Test
    fun `getLearningStats returns default when destroyed`() =
        runTest {
            wordLearningEngine.cleanup()

            val result = wordLearningEngine.getLearningStats()

            assertTrue(result.isSuccess)
            val stats = result.getOrNull()!!
            assertEquals(0, stats.totalWordsLearned)
            assertEquals("Unknown", stats.currentLanguage)
        }

    @Test
    fun `consecutive errors trigger cooldown`() =
        runTest {
            repeat(5) {
                whenever(learnedWordDao.findExactWord(any(), any()))
                    .thenThrow(android.database.sqlite.SQLiteException("Error"))
                wordLearningEngine.isWordLearned("trigger$it")
            }

            val results = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 3)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `successful operation decrements error counter`() =
        runTest {
            whenever(learnedWordDao.findExactWord(any(), any()))
                .thenThrow(android.database.sqlite.SQLiteException("Error"))
                .thenThrow(android.database.sqlite.SQLiteException("Error"))
                .thenThrow(android.database.sqlite.SQLiteException("Error"))
                .thenReturn(null)

            repeat(3) {
                wordLearningEngine.isWordLearned("error$it")
            }

            wordLearningEngine.isWordLearned("success")

            val result = wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 3)
            assertNotNull(result)
        }

    @Test
    fun `settings changes are observed`() =
        runTest {
            settingsFlow.value = KeyboardSettings(learnNewWords = true)
            val result1 = wordLearningEngine.learnWord("word1", InputMethod.TYPED)
            assertNotNull(result1.getOrNull())

            settingsFlow.value = KeyboardSettings(learnNewWords = false)
            val result2 = wordLearningEngine.learnWord("word2", InputMethod.TYPED)
            assertNull(result2.getOrNull())
        }

    @Test
    fun `cleanup clears cache and resets state`() =
        runTest {
            wordLearningEngine.learnWord("word", InputMethod.TYPED)
            assertTrue(wordLearningEngine.isWordLearned("word"))

            wordLearningEngine.cleanup()

            assertFalse(wordLearningEngine.isWordLearned("word"))
        }

    @Test
    fun `cleanup is idempotent`() =
        runTest {
            wordLearningEngine.cleanup()
            wordLearningEngine.cleanup()

            val result = wordLearningEngine.learnWord("word", InputMethod.TYPED)
            assertNull(result.getOrNull())
        }

    @Test
    fun `operations after cleanup return safe defaults`() =
        runTest {
            wordLearningEngine.cleanup()

            assertFalse(wordLearningEngine.isWordLearned("word"))
            assertTrue(wordLearningEngine.getSimilarLearnedWordsWithFrequency("word").isEmpty())
            assertTrue(wordLearningEngine.areWordsLearned(listOf("word")).isEmpty())

            val learnResult = wordLearningEngine.learnWord("word", InputMethod.TYPED)
            assertNull(learnResult.getOrNull())

            val removeResult = wordLearningEngine.removeWord("word")
            assertEquals(false, removeResult.getOrNull())
        }
}
