package com.urik.keyboard.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for custom key mappings.
 *
 * Thread-safe Room DAO. All suspend functions safe for concurrent calls.
 */
@Dao
interface CustomKeyMappingDao {
    /**
     * Observes all custom key mappings as a Flow.
     *
     * Emits new list whenever mappings change.
     */
    @Query("SELECT * FROM custom_key_mappings ORDER BY base_key ASC")
    fun observeAllMappings(): Flow<List<CustomKeyMapping>>

    /**
     * Gets all custom key mappings synchronously.
     */
    @Query("SELECT * FROM custom_key_mappings ORDER BY base_key ASC")
    suspend fun getAllMappings(): List<CustomKeyMapping>

    /**
     * Gets mapping for a specific base key.
     */
    @Query("SELECT * FROM custom_key_mappings WHERE base_key = :baseKey LIMIT 1")
    suspend fun getMappingForKey(baseKey: String): CustomKeyMapping?

    /**
     * Inserts or replaces a custom key mapping.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(mapping: CustomKeyMapping)

    /**
     * Removes mapping for a specific base key.
     *
     * @return Number of rows deleted (0 or 1)
     */
    @Query("DELETE FROM custom_key_mappings WHERE base_key = :baseKey")
    suspend fun removeMapping(baseKey: String): Int

    /**
     * Removes all custom key mappings.
     *
     * @return Number of rows deleted
     */
    @Query("DELETE FROM custom_key_mappings")
    suspend fun clearAllMappings(): Int

    /**
     * Gets count of custom mappings.
     */
    @Query("SELECT COUNT(*) FROM custom_key_mappings")
    suspend fun getMappingCount(): Int
}
