package com.urik.keyboard.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.urik.keyboard.KeyboardConstants

/**
 * Data access for clipboard history.
 *
 * Performance optimizations:
 * - LIMIT queries to max items to prevent OOM on large datasets
 * - Hash-based deduplication via content_hash unique index
 * - Cleanup enforces max 100 unpinned items
 */
@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_items WHERE is_pinned = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentItems(limit: Int = KeyboardConstants.DatabaseConstants.MAX_CLIPBOARD_ITEMS): List<ClipboardItem>

    @Query("SELECT * FROM clipboard_items WHERE is_pinned = 1 ORDER BY timestamp DESC")
    suspend fun getPinnedItems(): List<ClipboardItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ClipboardItem): Long

    @Query("UPDATE clipboard_items SET is_pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(
        id: Long,
        pinned: Boolean,
    )

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM clipboard_items WHERE is_pinned = 0")
    suspend fun deleteAllUnpinned()

    @Query("SELECT COUNT(*) FROM clipboard_items WHERE is_pinned = 0")
    suspend fun getUnpinnedCount(): Int

    @Query("SELECT COUNT(*) FROM clipboard_items")
    suspend fun getCount(): Int

    /**
     * Enforces max item limit by deleting oldest unpinned items.
     *
     * Called after each insert to maintain max 100 unpinned items.
     * Pinned items are never auto-deleted.
     */
    @Query(
        """
        DELETE FROM clipboard_items
        WHERE id IN (
            SELECT id FROM clipboard_items
            WHERE is_pinned = 0
            ORDER BY timestamp DESC
            LIMIT -1 OFFSET :maxItems
        )
    """,
    )
    suspend fun enforceMaxItems(maxItems: Int = KeyboardConstants.DatabaseConstants.MAX_CLIPBOARD_ITEMS)
}
