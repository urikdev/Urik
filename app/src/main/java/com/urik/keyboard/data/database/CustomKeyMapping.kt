package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User-defined custom symbol mapping for a keyboard key.
 *
 * Maps a base key (e.g., "a") to a custom symbol (e.g., "@").
 * Global across all languages. Single symbol per key.
 */
@Entity(tableName = "custom_key_mappings")
data class CustomKeyMapping(
    @PrimaryKey
    @ColumnInfo(name = "base_key")
    val baseKey: String,
    @ColumnInfo(name = "custom_symbol")
    val customSymbol: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun create(
            baseKey: String,
            customSymbol: String,
        ): CustomKeyMapping =
            CustomKeyMapping(
                baseKey = baseKey.lowercase(),
                customSymbol = customSymbol,
                createdAt = System.currentTimeMillis(),
            )
    }
}
