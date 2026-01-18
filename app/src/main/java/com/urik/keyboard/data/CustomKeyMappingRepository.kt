package com.urik.keyboard.data

import com.urik.keyboard.data.database.CustomKeyMapping
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.utils.ErrorLogger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for custom key mapping operations.
 *
 * Provides reactive Flow-based access and atomic update operations.
 */
@Singleton
class CustomKeyMappingRepository
    @Inject
    constructor(
        private val customKeyMappingDao: CustomKeyMappingDao,
    ) {
        /**
         * Observes all custom key mappings as a Flow.
         *
         * Emits new map whenever mappings change.
         */
        val mappings: Flow<List<CustomKeyMapping>> = customKeyMappingDao.observeAllMappings()

        /**
         * Gets all mappings as a map for quick lookup.
         */
        suspend fun getAllMappingsAsMap(): Map<String, String> =
            try {
                customKeyMappingDao
                    .getAllMappings()
                    .associate { it.baseKey to it.customSymbol }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "CustomKeyMappingRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "getAllMappingsAsMap"),
                )
                emptyMap()
            }

        /**
         * Sets or updates a custom mapping for a key.
         *
         * @param baseKey The key to map (e.g., "a")
         * @param customSymbol The symbol to assign (e.g., "@")
         */
        suspend fun setMapping(
            baseKey: String,
            customSymbol: String,
        ): Result<Unit> {
            val normalizedKey = baseKey.lowercase()
            return try {
                val mapping = CustomKeyMapping(normalizedKey, customSymbol)
                customKeyMappingDao.upsertMapping(mapping)
                Result.success(Unit)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "CustomKeyMappingRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "setMapping", "baseKey" to normalizedKey),
                )
                Result.failure(e)
            }
        }

        /**
         * Removes a custom mapping for a key.
         *
         * @param baseKey The key to remove mapping from
         * @return true if a mapping was removed
         */
        suspend fun removeMapping(baseKey: String): Result<Boolean> =
            try {
                val removed = customKeyMappingDao.removeMapping(baseKey.lowercase()) > 0
                Result.success(removed)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "CustomKeyMappingRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "removeMapping", "baseKey" to baseKey),
                )
                Result.failure(e)
            }

        /**
         * Clears all custom key mappings.
         */
        suspend fun clearAllMappings(): Result<Int> =
            try {
                val count = customKeyMappingDao.clearAllMappings()
                Result.success(count)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "CustomKeyMappingRepository",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("operation" to "clearAllMappings"),
                )
                Result.failure(e)
            }
    }
