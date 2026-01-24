@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.WordSource
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryBackupManagerTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var database: KeyboardDatabase
    private lateinit var learnedWordDao: LearnedWordDao
    private lateinit var cacheMemoryManager: CacheMemoryManager

    private lateinit var backupManager: DictionaryBackupManager

    private val testUri = mock<Uri>()

    private val testWords =
        listOf(
            LearnedWord(
                id = 1,
                word = "Hello",
                wordNormalized = "hello",
                languageTag = "en",
                frequency = 10,
                source = WordSource.USER_TYPED,
                characterCount = 5,
                createdAt = 1000L,
                lastUsed = 2000L,
            ),
            LearnedWord(
                id = 2,
                word = "World",
                wordNormalized = "world",
                languageTag = "en",
                frequency = 5,
                source = WordSource.USER_TYPED,
                characterCount = 5,
                createdAt = 1500L,
                lastUsed = 2500L,
            ),
            LearnedWord(
                id = 3,
                word = "Hallo",
                wordNormalized = "hallo",
                languageTag = "de",
                frequency = 3,
                source = WordSource.USER_TYPED,
                characterCount = 5,
                createdAt = 1800L,
                lastUsed = 2800L,
            ),
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mock()
        contentResolver = mock()
        database = mock()
        learnedWordDao = mock()
        cacheMemoryManager = mock()

        whenever(context.contentResolver).thenReturn(contentResolver)

        backupManager =
            object : DictionaryBackupManager(
                context = context,
                database = database,
                learnedWordDao = learnedWordDao,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher,
            ) {
                override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
            }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `export creates valid JSON with all words`() =
        runTest {
            val outputStream = ByteArrayOutputStream()
            whenever(learnedWordDao.getAllLearnedWords()).thenReturn(testWords)
            whenever(contentResolver.openOutputStream(testUri)).thenReturn(outputStream)

            val result = backupManager.exportToUri(testUri)

            assertTrue(result.isSuccess)
            assertEquals(3, result.getOrNull()?.wordCount)

            val jsonString = outputStream.toString(Charsets.UTF_8.name())
            val export = Json.decodeFromString<DictionaryExport>(jsonString)

            assertEquals(1, export.version)
            assertEquals(3, export.totalWords)
            assertEquals(3, export.words.size)
            assertTrue(export.languages.contains("en"))
            assertTrue(export.languages.contains("de"))
        }

    @Test
    fun `export includes correct word data`() =
        runTest {
            val outputStream = ByteArrayOutputStream()
            whenever(learnedWordDao.getAllLearnedWords()).thenReturn(listOf(testWords[0]))
            whenever(contentResolver.openOutputStream(testUri)).thenReturn(outputStream)

            val result = backupManager.exportToUri(testUri)

            assertTrue(result.isSuccess)

            val jsonString = outputStream.toString(Charsets.UTF_8.name())
            val export = Json.decodeFromString<DictionaryExport>(jsonString)
            val exportedWord = export.words.first()

            assertEquals("Hello", exportedWord.word)
            assertEquals("hello", exportedWord.wordNormalized)
            assertEquals("en", exportedWord.languageTag)
            assertEquals(10, exportedWord.frequency)
            assertEquals("USER_TYPED", exportedWord.source)
            assertEquals(5, exportedWord.characterCount)
            assertEquals(1000L, exportedWord.createdAt)
            assertEquals(2000L, exportedWord.lastUsed)
        }

    @Test
    fun `export handles empty database`() =
        runTest {
            val outputStream = ByteArrayOutputStream()
            whenever(learnedWordDao.getAllLearnedWords()).thenReturn(emptyList())
            whenever(contentResolver.openOutputStream(testUri)).thenReturn(outputStream)

            val result = backupManager.exportToUri(testUri)

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()?.wordCount)
        }

    @Test
    fun `export returns failure when output stream unavailable`() =
        runTest {
            whenever(learnedWordDao.getAllLearnedWords()).thenReturn(testWords)
            whenever(contentResolver.openOutputStream(testUri)).thenReturn(null)

            val result = backupManager.exportToUri(testUri)

            assertTrue(result.isFailure)
        }

    @Test
    fun `import inserts new words correctly`() =
        runTest {
            val exportJson =
                """
                {
                    "version": 1,
                    "exportedAt": "2024-01-01T00:00:00Z",
                    "totalWords": 1,
                    "languages": ["en"],
                    "words": [{
                        "word": "NewWord",
                        "wordNormalized": "newword",
                        "languageTag": "en",
                        "frequency": 5,
                        "source": "USER_TYPED",
                        "characterCount": 7,
                        "createdAt": 1000,
                        "lastUsed": 2000
                    }]
                }
                """.trimIndent()

            val inputStream = ByteArrayInputStream(exportJson.toByteArray())
            whenever(contentResolver.openInputStream(testUri)).thenReturn(inputStream)
            whenever(learnedWordDao.findExactWord("en", "newword")).thenReturn(null)

            val result = backupManager.importFromUri(testUri)

            assertTrue(result.isSuccess)
            assertEquals(1, result.getOrNull()?.newWords)
            assertEquals(0, result.getOrNull()?.updatedWords)
            verify(learnedWordDao).insertWord(any())
        }

    @Test
    fun `import merges frequencies for existing words`() =
        runTest {
            val existingWord =
                LearnedWord(
                    id = 1,
                    word = "Hello",
                    wordNormalized = "hello",
                    languageTag = "en",
                    frequency = 10,
                    source = WordSource.USER_TYPED,
                    characterCount = 5,
                    createdAt = 1000L,
                    lastUsed = 2000L,
                )

            val exportJson =
                """
                {
                    "version": 1,
                    "exportedAt": "2024-01-01T00:00:00Z",
                    "totalWords": 1,
                    "languages": ["en"],
                    "words": [{
                        "word": "Hello",
                        "wordNormalized": "hello",
                        "languageTag": "en",
                        "frequency": 5,
                        "source": "USER_TYPED",
                        "characterCount": 5,
                        "createdAt": 500,
                        "lastUsed": 3000
                    }]
                }
                """.trimIndent()

            val inputStream = ByteArrayInputStream(exportJson.toByteArray())
            whenever(contentResolver.openInputStream(testUri)).thenReturn(inputStream)
            whenever(learnedWordDao.findExactWord("en", "hello")).thenReturn(existingWord)

            val result = backupManager.importFromUri(testUri)

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()?.newWords)
            assertEquals(1, result.getOrNull()?.updatedWords)
            verify(learnedWordDao).updateWord(any())
            verify(learnedWordDao, never()).insertWord(any())
        }

    @Test
    fun `import uses max lastUsed timestamp`() =
        runTest {
            val existingWord =
                LearnedWord(
                    id = 1,
                    word = "Test",
                    wordNormalized = "test",
                    languageTag = "en",
                    frequency = 10,
                    source = WordSource.USER_TYPED,
                    characterCount = 4,
                    createdAt = 1000L,
                    lastUsed = 5000L,
                )

            val exportJson =
                """
                {
                    "version": 1,
                    "exportedAt": "2024-01-01T00:00:00Z",
                    "totalWords": 1,
                    "languages": ["en"],
                    "words": [{
                        "word": "Test",
                        "wordNormalized": "test",
                        "languageTag": "en",
                        "frequency": 3,
                        "source": "USER_TYPED",
                        "characterCount": 4,
                        "createdAt": 500,
                        "lastUsed": 2000
                    }]
                }
                """.trimIndent()

            var capturedWord: LearnedWord? = null
            whenever(contentResolver.openInputStream(testUri))
                .thenReturn(ByteArrayInputStream(exportJson.toByteArray()))
            whenever(learnedWordDao.findExactWord("en", "test")).thenReturn(existingWord)
            whenever(learnedWordDao.updateWord(any())).doAnswer { invocation ->
                capturedWord = invocation.arguments[0] as LearnedWord
            }

            backupManager.importFromUri(testUri)

            assertEquals(13, capturedWord?.frequency)
            assertEquals(5000L, capturedWord?.lastUsed)
        }

    @Test
    fun `import clears caches after completion`() =
        runTest {
            val exportJson =
                """
                {
                    "version": 1,
                    "exportedAt": "2024-01-01T00:00:00Z",
                    "totalWords": 1,
                    "languages": ["en"],
                    "words": [{
                        "word": "Test",
                        "wordNormalized": "test",
                        "languageTag": "en",
                        "frequency": 5,
                        "source": "USER_TYPED",
                        "characterCount": 4,
                        "createdAt": 1000,
                        "lastUsed": 2000
                    }]
                }
                """.trimIndent()

            whenever(contentResolver.openInputStream(testUri))
                .thenReturn(ByteArrayInputStream(exportJson.toByteArray()))
            whenever(learnedWordDao.findExactWord("en", "test")).thenReturn(null)

            backupManager.importFromUri(testUri)

            verify(cacheMemoryManager).forceCleanup()
        }

    @Test
    fun `import handles unknown word source gracefully`() =
        runTest {
            val exportJson =
                """
                {
                    "version": 1,
                    "exportedAt": "2024-01-01T00:00:00Z",
                    "totalWords": 1,
                    "languages": ["en"],
                    "words": [{
                        "word": "Test",
                        "wordNormalized": "test",
                        "languageTag": "en",
                        "frequency": 5,
                        "source": "UNKNOWN_SOURCE",
                        "characterCount": 4,
                        "createdAt": 1000,
                        "lastUsed": 2000
                    }]
                }
                """.trimIndent()

            whenever(contentResolver.openInputStream(testUri))
                .thenReturn(ByteArrayInputStream(exportJson.toByteArray()))
            whenever(learnedWordDao.findExactWord("en", "test")).thenReturn(null)

            val result = backupManager.importFromUri(testUri)

            assertTrue(result.isSuccess)
            verify(learnedWordDao).insertWord(any())
        }

    @Test
    fun `import returns failure when input stream unavailable`() =
        runTest {
            whenever(contentResolver.openInputStream(testUri)).thenReturn(null)

            val result = backupManager.importFromUri(testUri)

            assertTrue(result.isFailure)
        }

    @Test
    fun `import returns failure for invalid JSON`() =
        runTest {
            val invalidJson = "{ invalid json }"
            whenever(contentResolver.openInputStream(testUri))
                .thenReturn(ByteArrayInputStream(invalidJson.toByteArray()))

            val result = backupManager.importFromUri(testUri)

            assertTrue(result.isFailure)
        }

    @Test
    fun `import handles multiple words with mixed new and existing`() =
        runTest {
            val existingWord =
                LearnedWord(
                    id = 1,
                    word = "Existing",
                    wordNormalized = "existing",
                    languageTag = "en",
                    frequency = 10,
                    source = WordSource.USER_TYPED,
                    characterCount = 8,
                    createdAt = 1000L,
                    lastUsed = 2000L,
                )

            val exportJson =
                """
                {
                    "version": 1,
                    "exportedAt": "2024-01-01T00:00:00Z",
                    "totalWords": 3,
                    "languages": ["en"],
                    "words": [
                        {
                            "word": "Existing",
                            "wordNormalized": "existing",
                            "languageTag": "en",
                            "frequency": 5,
                            "source": "USER_TYPED",
                            "characterCount": 8,
                            "createdAt": 500,
                            "lastUsed": 1500
                        },
                        {
                            "word": "NewOne",
                            "wordNormalized": "newone",
                            "languageTag": "en",
                            "frequency": 3,
                            "source": "USER_TYPED",
                            "characterCount": 6,
                            "createdAt": 1000,
                            "lastUsed": 2000
                        },
                        {
                            "word": "NewTwo",
                            "wordNormalized": "newtwo",
                            "languageTag": "en",
                            "frequency": 2,
                            "source": "USER_TYPED",
                            "characterCount": 6,
                            "createdAt": 1000,
                            "lastUsed": 2000
                        }
                    ]
                }
                """.trimIndent()

            whenever(contentResolver.openInputStream(testUri))
                .thenReturn(ByteArrayInputStream(exportJson.toByteArray()))
            whenever(learnedWordDao.findExactWord("en", "existing")).thenReturn(existingWord)
            whenever(learnedWordDao.findExactWord("en", "newone")).thenReturn(null)
            whenever(learnedWordDao.findExactWord("en", "newtwo")).thenReturn(null)

            val result = backupManager.importFromUri(testUri)

            assertTrue(result.isSuccess)
            assertEquals(2, result.getOrNull()?.newWords)
            assertEquals(1, result.getOrNull()?.updatedWords)
            assertEquals(3, result.getOrNull()?.totalProcessed)
            verify(learnedWordDao, times(2)).insertWord(any())
            verify(learnedWordDao, times(1)).updateWord(any())
        }

    @Test
    fun `export sorts languages alphabetically`() =
        runTest {
            val words =
                listOf(
                    testWords[2],
                    testWords[0],
                )
            val outputStream = ByteArrayOutputStream()
            whenever(learnedWordDao.getAllLearnedWords()).thenReturn(words)
            whenever(contentResolver.openOutputStream(testUri)).thenReturn(outputStream)

            backupManager.exportToUri(testUri)

            val jsonString = outputStream.toString(Charsets.UTF_8.name())
            val export = Json.decodeFromString<DictionaryExport>(jsonString)

            assertEquals(listOf("de", "en"), export.languages)
        }

    @Test
    fun `export excludes user_word_frequency data`() =
        runTest {
            val outputStream = ByteArrayOutputStream()
            whenever(learnedWordDao.getAllLearnedWords()).thenReturn(testWords)
            whenever(contentResolver.openOutputStream(testUri)).thenReturn(outputStream)

            backupManager.exportToUri(testUri)

            val jsonString = outputStream.toString(Charsets.UTF_8.name())

            assertFalse(jsonString.contains("user_word_frequency"))
            assertFalse(jsonString.contains("userWordFrequency"))

            val export = Json.decodeFromString<DictionaryExport>(jsonString)
            assertEquals(3, export.words.size)

            export.words.forEach { exportedWord ->
                assertTrue(exportedWord.word.isNotEmpty())
                assertTrue(exportedWord.wordNormalized.isNotEmpty())
                assertTrue(exportedWord.languageTag.isNotEmpty())
                assertTrue(exportedWord.frequency > 0)
            }
        }
}
