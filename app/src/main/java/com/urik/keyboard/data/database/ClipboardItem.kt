package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Clipboard history item stored locally.
 *
 * Captures text copied by user across apps for quick re-use.
 * Content deduplication via hash to avoid index bloat on large text.
 * Privacy-focused: never synced to cloud, encrypted at rest.
 *
 * Max 100 items enforced by cleanup policy (unpinned only).
 */
@Entity(
    tableName = "clipboard_items",
    indices = [
        Index(value = ["content_hash"], name = "idx_clipboard_content_hash", unique = true),
        Index(value = ["timestamp"], name = "idx_clipboard_timestamp"),
        Index(value = ["is_pinned", "timestamp"], name = "idx_clipboard_pinned"),
    ],
)
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: Int = content.hashCode(),
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,
)
