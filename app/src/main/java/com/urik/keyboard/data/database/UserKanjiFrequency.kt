package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_kanji_frequency",
    indices = [
        Index(
            value = ["reading", "surface"],
            name = "idx_kanji_freq_lookup",
            unique = true
        )
    ]
)
data class UserKanjiFrequency(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "reading")
    val reading: String,
    @ColumnInfo(name = "surface")
    val surface: String,
    @ColumnInfo(name = "frequency")
    val frequency: Long = 1L,
    @ColumnInfo(name = "last_used")
    val lastUsed: Long = System.currentTimeMillis()
)
