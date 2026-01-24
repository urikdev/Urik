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
}
