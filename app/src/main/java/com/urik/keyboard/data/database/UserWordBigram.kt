package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_word_bigram",
    indices = [
        Index(
            value = ["language_tag", "word_a_normalized", "word_b_normalized"],
            name = "idx_bigram_lookup",
            unique = true,
        ),
        Index(
            value = ["language_tag", "word_a_normalized", "frequency"],
            name = "idx_bigram_predictions",
        ),
    ],
)
data class UserWordBigram(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "language_tag")
    val languageTag: String,
    @ColumnInfo(name = "word_a_normalized")
    val wordANormalized: String,
    @ColumnInfo(name = "word_b_normalized")
    val wordBNormalized: String,
    @ColumnInfo(name = "frequency")
    val frequency: Int = 1,
    @ColumnInfo(name = "last_used")
    val lastUsed: Long = System.currentTimeMillis(),
)
