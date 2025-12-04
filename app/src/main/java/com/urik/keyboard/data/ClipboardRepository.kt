package com.urik.keyboard.data

import com.urik.keyboard.data.database.ClipboardDao
import com.urik.keyboard.data.database.ClipboardItem
import com.urik.keyboard.utils.ErrorLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for clipboard history management.
 *
 * Handles persistent storage of copied text items. All operations fail gracefully.
 */
@Singleton
class ClipboardRepository
    @Inject
    constructor(
        private val clipboardDao: ClipboardDao,
    ) {
        suspend fun addItem(content: String): Result<Unit> =
            try {
                val item =
                    ClipboardItem(
                        content = content,
                        timestamp = System.currentTimeMillis(),
                    )
                clipboardDao.insert(item)

                val currentCount = clipboardDao.getUnpinnedCount()
                if (currentCount > com.urik.keyboard.KeyboardConstants.DatabaseConstants.MAX_CLIPBOARD_ITEMS) {
                    clipboardDao.enforceMaxItems()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "ClipboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "addItem"),
                )
                Result.failure(e)
            }

        suspend fun getRecentItems(): Result<List<ClipboardItem>> =
            try {
                Result.success(clipboardDao.getRecentItems())
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "ClipboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "getRecentItems"),
                )
                Result.success(emptyList())
            }

        suspend fun getPinnedItems(): Result<List<ClipboardItem>> =
            try {
                Result.success(clipboardDao.getPinnedItems())
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "ClipboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "getPinnedItems"),
                )
                Result.success(emptyList())
            }

        suspend fun togglePin(
            id: Long,
            pinned: Boolean,
        ): Result<Unit> =
            try {
                clipboardDao.updatePinned(id, pinned)
                Result.success(Unit)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "ClipboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "togglePin", "id" to id.toString()),
                )
                Result.failure(e)
            }

        suspend fun deleteItem(id: Long): Result<Unit> =
            try {
                clipboardDao.delete(id)
                Result.success(Unit)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "ClipboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "deleteItem", "id" to id.toString()),
                )
                Result.failure(e)
            }

        suspend fun deleteAllUnpinned(): Result<Unit> =
            try {
                clipboardDao.deleteAllUnpinned()
                Result.success(Unit)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "ClipboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "deleteAllUnpinned"),
                )
                Result.failure(e)
            }

        suspend fun getCount(): Result<Int> =
            try {
                Result.success(clipboardDao.getCount())
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "ClipboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "getCount"),
                )
                Result.success(0)
            }
    }
