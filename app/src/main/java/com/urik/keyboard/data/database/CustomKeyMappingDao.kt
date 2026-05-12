package com.urik.keyboard.data.database

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomKeyMappingDao {
    @Query("SELECT * FROM custom_key_mappings ORDER BY base_key ASC")
    fun observeAllMappings(): Flow<List<CustomKeyMapping>>

    @Query("SELECT * FROM custom_key_mappings ORDER BY base_key ASC")
    suspend fun getAllMappings(): List<CustomKeyMapping>

    @Query("SELECT * FROM custom_key_mappings WHERE base_key = :baseKey LIMIT 1")
    suspend fun getMappingForKey(baseKey: String): CustomKeyMapping?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(mapping: CustomKeyMapping)

    /** @return number of rows deleted (0 or 1) */
    @Query("DELETE FROM custom_key_mappings WHERE base_key = :baseKey")
    suspend fun removeMapping(baseKey: String): Int

    /** @return number of rows deleted */
    @Query("DELETE FROM custom_key_mappings")
    suspend fun clearAllMappings(): Int

    @VisibleForTesting
    @Query("SELECT COUNT(*) FROM custom_key_mappings")
    suspend fun getMappingCount(): Int
}
