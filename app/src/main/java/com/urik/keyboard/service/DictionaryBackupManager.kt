package com.urik.keyboard.service

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.WordSource
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DictionaryExport(
    val version: Int = 1,
    val exportedAt: String,
    val totalWords: Int,
    val languages: List<String>,
    val words: List<ExportedWord>,
)

@Serializable
data class ExportedWord(
    val word: String,
    val wordNormalized: String,
    val languageTag: String,
    val frequency: Int,
    val source: String,
    val characterCount: Int,
    val createdAt: Long,
    val lastUsed: Long,
)

data class ExportResult(
    val wordCount: Int,
    val languages: List<String>,
)

data class ImportResult(
    val newWords: Int,
    val updatedWords: Int,
    val totalProcessed: Int,
)

@Singleton
open class DictionaryBackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: KeyboardDatabase,
        private val learnedWordDao: LearnedWordDao,
        private val cacheMemoryManager: CacheMemoryManager,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        private val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

        internal open suspend fun <R> runInTransaction(block: suspend () -> R): R = database.withTransaction { block() }

        suspend fun exportToUri(uri: Uri): Result<ExportResult> =
            withContext(ioDispatcher) {
                try {
                    val allWords = runInTransaction { learnedWordDao.getAllLearnedWords() }
                    val languages = allWords.map { it.languageTag }.distinct().sorted()

                    val exportedWords =
                        allWords.map { word ->
                            ExportedWord(
                                word = word.word,
                                wordNormalized = word.wordNormalized,
                                languageTag = word.languageTag,
                                frequency = word.frequency,
                                source = word.source.name,
                                characterCount = word.characterCount,
                                createdAt = word.createdAt,
                                lastUsed = word.lastUsed,
                            )
                        }

                    val export =
                        DictionaryExport(
                            version = 1,
                            exportedAt = Instant.now().toString(),
                            totalWords = allWords.size,
                            languages = languages,
                            words = exportedWords,
                        )

                    val jsonString = json.encodeToString(export)

                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext Result.failure(Exception("Failed to open output stream"))

                    Result.success(ExportResult(wordCount = allWords.size, languages = languages))
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "DictionaryBackupManager",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "export"),
                    )
                    Result.failure(e)
                }
            }

        suspend fun importFromUri(uri: Uri): Result<ImportResult> =
            withContext(ioDispatcher) {
                try {
                    val jsonString =
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            inputStream.bufferedReader().readText()
                        } ?: return@withContext Result.failure(Exception("Failed to open input stream"))

                    val export = json.decodeFromString<DictionaryExport>(jsonString)

                    var newWords = 0
                    var updatedWords = 0

                    runInTransaction {
                        for (exportedWord in export.words) {
                            if (exportedWord.word.isBlank() ||
                                exportedWord.wordNormalized.isBlank() ||
                                exportedWord.languageTag.isBlank()
                            ) {
                                continue
                            }

                            val wordSource =
                                try {
                                    WordSource.valueOf(exportedWord.source)
                                } catch (_: IllegalArgumentException) {
                                    WordSource.IMPORTED
                                }

                            val validFrequency = exportedWord.frequency.coerceAtLeast(1)
                            val validCharCount = exportedWord.characterCount.coerceAtLeast(1)

                            val learnedWord =
                                LearnedWord(
                                    id = 0,
                                    word = exportedWord.word,
                                    wordNormalized = exportedWord.wordNormalized,
                                    languageTag = exportedWord.languageTag,
                                    frequency = validFrequency,
                                    source = wordSource,
                                    characterCount = validCharCount,
                                    createdAt = exportedWord.createdAt,
                                    lastUsed = exportedWord.lastUsed,
                                )

                            val existing =
                                learnedWordDao.findExactWord(
                                    learnedWord.languageTag,
                                    learnedWord.wordNormalized,
                                )

                            if (existing != null) {
                                val merged =
                                    existing.copy(
                                        frequency = existing.frequency + learnedWord.frequency,
                                        lastUsed = maxOf(existing.lastUsed, learnedWord.lastUsed),
                                    )
                                learnedWordDao.updateWord(merged)
                                updatedWords++
                            } else {
                                learnedWordDao.insertWord(learnedWord)
                                newWords++
                            }
                        }
                    }

                    cacheMemoryManager.forceCleanup()

                    Result.success(
                        ImportResult(
                            newWords = newWords,
                            updatedWords = updatedWords,
                            totalProcessed = export.words.size,
                        ),
                    )
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "DictionaryBackupManager",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "import"),
                    )
                    Result.failure(e)
                }
            }
    }
