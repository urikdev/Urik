package com.urik.keyboard.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface UserWordBigramDao {
    @Query(
        """
        INSERT INTO user_word_bigram (language_tag, word_a_normalized, word_b_normalized, frequency, last_used)
        VALUES (:languageTag, :wordANormalized, :wordBNormalized, 1, :lastUsed)
        ON CONFLICT(language_tag, word_a_normalized, word_b_normalized)
        DO UPDATE SET
            frequency = frequency + 1,
            last_used = :lastUsed
        """,
    )
    suspend fun incrementBigram(
        languageTag: String,
        wordANormalized: String,
        wordBNormalized: String,
        lastUsed: Long,
    )

    @Query(
        """
        INSERT INTO user_word_bigram (language_tag, word_a_normalized, word_b_normalized, frequency, last_used)
        VALUES (:languageTag, :wordANormalized, :wordBNormalized, :amount, :lastUsed)
        ON CONFLICT(language_tag, word_a_normalized, word_b_normalized)
        DO UPDATE SET
            frequency = frequency + :amount,
            last_used = :lastUsed
        """,
    )
    suspend fun incrementBigramBy(
        languageTag: String,
        wordANormalized: String,
        wordBNormalized: String,
        amount: Int,
        lastUsed: Long,
    )

    @Query(
        """
        SELECT word_b_normalized FROM user_word_bigram
        WHERE language_tag = :languageTag
        AND word_a_normalized = :wordANormalized
        ORDER BY frequency DESC
        LIMIT :limit
        """,
    )
    suspend fun getPredictions(
        languageTag: String,
        wordANormalized: String,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT * FROM user_word_bigram
        WHERE language_tag = :languageTag
        ORDER BY frequency DESC
        LIMIT :limit
        """,
    )
    suspend fun getTopBigrams(
        languageTag: String,
        limit: Int = 100,
    ): List<UserWordBigram>

    @Query("DELETE FROM user_word_bigram WHERE language_tag = :languageTag")
    suspend fun clearLanguage(languageTag: String): Int

    @Query("DELETE FROM user_word_bigram")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM user_word_bigram")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM user_word_bigram WHERE frequency = 1 AND last_used < :cutoff")
    suspend fun pruneStaleEntries(cutoff: Long): Int

    @Query(
        """
        DELETE FROM user_word_bigram WHERE id IN (
            SELECT id FROM user_word_bigram
            ORDER BY frequency ASC, last_used ASC
            LIMIT MAX(0, (SELECT COUNT(*) FROM user_word_bigram) - :maxRows)
        )
        """,
    )
    suspend fun enforceMaxRows(maxRows: Int): Int
}
