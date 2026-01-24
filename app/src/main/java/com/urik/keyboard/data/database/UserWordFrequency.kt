package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_word_frequency",
    indices = [
        Index(
            value = ["language_tag", "word_normalized"],
            name = "idx_user_freq_lookup",
            unique = true,
        ),
        Index(
            value = ["language_tag", "frequency"],
            name = "idx_user_freq_ranking",
        ),
    ],
)
data class UserWordFrequency(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "language_tag")
    val languageTag: String,
    @ColumnInfo(name = "word_normalized")
    val wordNormalized: String,
    @ColumnInfo(name = "frequency")
    val frequency: Int = 1,
    @ColumnInfo(name = "last_used")
    val lastUsed: Long = System.currentTimeMillis(),
) {
    fun incrementFrequency(): UserWordFrequency =
        copy(
            frequency = frequency + 1,
            lastUsed = System.currentTimeMillis(),
        )
}
