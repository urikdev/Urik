package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access for learned words with full-text search support.
 *
 * Thread-safe Room DAO. All suspend functions safe for concurrent calls.
 * Transactions handle FTS table synchronization automatically.
 */
@Dao
interface LearnedWordDao {
    /**
     * Fast prefix suggestions for high-frequency words only.
     *
     * Filters to frequency >= 2 to exclude one-off typos.
     * Uses normalized prefix matching for consistent results.
     */
    @Query(
        """
        SELECT word, frequency FROM learned_words
        WHERE language_tag = :languageTag 
        AND word_normalized LIKE :prefix || '%'
        AND frequency >= 2
        ORDER BY frequency DESC, last_used DESC
        LIMIT :limit
    """,
    )
    suspend fun getFastSuggestions(
        prefix: String,
        languageTag: String,
        limit: Int = 5,
    ): List<FastSuggestion>

    /**
     * Full-text search for fuzzy word matching.
     *
     * Uses FTS4 for complex queries.
     * Supports wildcards and ranking.
     */
    @Query(
        """
        SELECT learned_words.* FROM learned_words_fts
        JOIN learned_words ON learned_words.id = learned_words_fts.rowid
        WHERE learned_words_fts MATCH :searchQuery
        AND learned_words.language_tag = :languageTag
        ORDER BY learned_words.frequency DESC
        LIMIT :limit
    """,
    )
    suspend fun searchWordsWithFts(
        searchQuery: String,
        languageTag: String,
        limit: Int = 10,
    ): List<LearnedWord>

    @Query(
        """
        SELECT * FROM learned_words 
        WHERE language_tag = :languageTag 
        AND word_normalized LIKE :prefix || '%'
        ORDER BY frequency DESC, last_used DESC
        LIMIT :limit
    """,
    )
    suspend fun findWordsWithPrefix(
        languageTag: String,
        prefix: String,
        limit: Int = 10,
    ): List<LearnedWord>

    @Query(
        """
        SELECT * FROM learned_words 
        WHERE language_tag = :languageTag 
        AND word_normalized = :normalizedWord
        LIMIT 1
    """,
    )
    suspend fun findExactWord(
        languageTag: String,
        normalizedWord: String,
    ): LearnedWord?

    /**
     * Batch existence check for multiple words.
     */
    @Query(
        """
        SELECT word_normalized FROM learned_words 
        WHERE language_tag = :languageTag 
        AND word_normalized IN (:normalizedWords)
    """,
    )
    suspend fun findExistingWords(
        languageTag: String,
        normalizedWords: List<String>,
    ): List<String>

    @Query(
        """
        SELECT * FROM learned_words 
        WHERE language_tag = :languageTag 
        ORDER BY frequency DESC
        LIMIT :limit
    """,
    )
    suspend fun getMostFrequentWords(
        languageTag: String,
        limit: Int = 100,
    ): List<LearnedWord>

    @Query(
        """
        SELECT COUNT(*) FROM learned_words 
        WHERE language_tag = :languageTag
    """,
    )
    suspend fun getWordCount(languageTag: String): Int

    @Query(
        """
        SELECT language_tag, COUNT(*) as word_count
        FROM learned_words 
        GROUP BY language_tag
        ORDER BY word_count DESC
    """,
    )
    suspend fun getWordCountByLanguageRaw(): List<LanguageWordCount>

    @Query("SELECT COUNT(*) FROM learned_words")
    suspend fun getTotalWordCount(): Int

    @Query("SELECT AVG(CAST(frequency AS REAL)) FROM learned_words")
    suspend fun getAverageFrequency(): Double

    @Query(
        """
        SELECT COUNT(*) FROM learned_words 
        WHERE source = :source
    """,
    )
    suspend fun getWordCountBySource(source: WordSource): Int

    @Query(
        """
        SELECT source, COUNT(*) as count
        FROM learned_words 
        GROUP BY source
    """,
    )
    suspend fun getWordCountsBySource(): List<WordSourceCount>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM learned_words 
            WHERE word_normalized = :normalizedWord 
            AND language_tag = :languageTag
        )
    """,
    )
    suspend fun wordExists(
        normalizedWord: String,
        languageTag: String,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWord(word: LearnedWord): Long

    @Update
    suspend fun updateWord(word: LearnedWord)

    @Upsert
    suspend fun upsertWord(word: LearnedWord): Long

    /**
     * Learns word with frequency tracking and FTS synchronization.
     *
     * If word exists: increments frequency, updates timestamp
     * If new: inserts with frequency=1
     * FTS table automatically synchronized in transaction.
     */
    @Transaction
    suspend fun learnWord(word: LearnedWord) {
        val existing = findExactWord(word.languageTag, word.wordNormalized)
        if (existing != null) {
            val updated = existing.incrementFrequency()
            updateWord(updated)
        } else {
            insertWord(word)
        }
    }

    @Query(
        """
    DELETE FROM learned_words 
    WHERE language_tag = :languageTag 
    AND word_normalized = :normalizedWord
""",
    )
    suspend fun removeWord(
        languageTag: String,
        normalizedWord: String,
    ): Int

    /**
     * Removes word from both learned_words and FTS tables.
     *
     * @return number of rows deleted from learned_words (0 or 1)
     */
    @Transaction
    suspend fun removeWordComplete(
        languageTag: String,
        normalizedWord: String,
    ): Int {
        val wordToRemove = findExactWord(languageTag, normalizedWord)

        if (wordToRemove != null) {
            val rowsAffected = removeWord(languageTag, normalizedWord)
            if (rowsAffected > 0) {
                removeWordFromFts(wordToRemove.id)
            }

            return rowsAffected
        }

        return 0
    }

    @Query("DELETE FROM learned_words_fts WHERE rowid = :rowid")
    suspend fun removeWordFromFts(rowid: Long): Int

    /**
     * Removes one-off typos older than cutoff timestamp.
     *
     * Only removes frequency=1 words (never reused). Preserves all
     * words with frequency >= 2 regardless of age.
     */
    @Query("DELETE FROM learned_words WHERE frequency = 1 AND last_used < :cutoff")
    suspend fun cleanupLowFrequencyWords(cutoff: Long): Int

    @Query("DELETE FROM learned_words WHERE language_tag = :languageTag")
    suspend fun clearLanguage(languageTag: String): Int

    @Query(
        """
        SELECT * FROM learned_words 
        WHERE language_tag = :languageTag 
        ORDER BY frequency DESC 
        LIMIT 20
    """,
    )
    fun observeTopWords(languageTag: String): Flow<List<LearnedWord>>

    @Query("SELECT COUNT(*) FROM learned_words")
    fun observeTotalWordCount(): Flow<Int>

    @Query("SELECT * FROM learned_words WHERE language_tag = :languageTag")
    suspend fun getAllLearnedWordsForLanguage(languageTag: String): List<LearnedWord>

    @Query("SELECT * FROM learned_words")
    suspend fun getAllLearnedWords(): List<LearnedWord>

    @Transaction
    suspend fun importWordWithMerge(word: LearnedWord) {
        val existing = findExactWord(word.languageTag, word.wordNormalized)
        if (existing != null) {
            val merged =
                existing.copy(
                    frequency = existing.frequency + word.frequency,
                    lastUsed = maxOf(existing.lastUsed, word.lastUsed),
                )
            updateWord(merged)
        } else {
            insertWord(word)
        }
    }
}

data class FastSuggestion(
    val word: String,
    val frequency: Int,
)

data class LanguageWordCount(
    @ColumnInfo(name = "language_tag")
    val languageTag: String,
    @ColumnInfo(name = "word_count")
    val wordCount: Int,
)

data class WordSourceCount(
    val source: WordSource,
    val count: Int,
)
