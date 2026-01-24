package com.urik.keyboard.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UserWordFrequencyDao {
    @Query(
        """
        SELECT * FROM user_word_frequency
        WHERE language_tag = :languageTag
        AND word_normalized = :normalizedWord
        LIMIT 1
        """,
    )
    suspend fun findWord(
        languageTag: String,
        normalizedWord: String,
    ): UserWordFrequency?

    @Query(
        """
        SELECT * FROM user_word_frequency
        WHERE language_tag = :languageTag
        AND word_normalized IN (:normalizedWords)
        """,
    )
    suspend fun findWords(
        languageTag: String,
        normalizedWords: List<String>,
    ): List<UserWordFrequency>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWord(word: UserWordFrequency): Long

    @Update
    suspend fun updateWord(word: UserWordFrequency)

    @Query(
        """
        INSERT INTO user_word_frequency (language_tag, word_normalized, frequency, last_used)
        VALUES (:languageTag, :wordNormalized, 1, :lastUsed)
        ON CONFLICT(language_tag, word_normalized)
        DO UPDATE SET
            frequency = frequency + 1,
            last_used = :lastUsed
        """,
    )
    suspend fun incrementFrequency(
        languageTag: String,
        wordNormalized: String,
        lastUsed: Long,
    )

    @Query("DELETE FROM user_word_frequency WHERE language_tag = :languageTag")
    suspend fun clearLanguage(languageTag: String): Int

    @Query("DELETE FROM user_word_frequency")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM user_word_frequency")
    suspend fun getTotalCount(): Int

    @Query(
        """
        SELECT * FROM user_word_frequency
        WHERE language_tag = :languageTag
        ORDER BY frequency DESC
        LIMIT :limit
        """,
    )
    suspend fun getMostFrequentWords(
        languageTag: String,
        limit: Int = 100,
    ): List<UserWordFrequency>
}
