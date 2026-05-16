package com.urik.keyboard.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface UserKanjiFrequencyDao {
    @Query("SELECT * FROM user_kanji_frequency")
    suspend fun getAll(): List<UserKanjiFrequency>

    @Query(
        """
        INSERT INTO user_kanji_frequency (reading, surface, frequency, last_used)
        VALUES (:reading, :surface, :amount, :lastUsed)
        ON CONFLICT(reading, surface)
        DO UPDATE SET frequency = frequency + :amount, last_used = :lastUsed
        """
    )
    suspend fun incrementBy(reading: String, surface: String, amount: Long, lastUsed: Long)

    @Query("DELETE FROM user_kanji_frequency")
    suspend fun clearAll(): Int
}
