package com.urik.keyboard.data

import com.urik.keyboard.data.database.CustomKeyMapping
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.utils.ErrorLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class CustomKeyMappingRepository
@Inject
constructor(private val customKeyMappingDao: CustomKeyMappingDao) {
    val mappings: Flow<List<CustomKeyMapping>> = customKeyMappingDao.observeAllMappings()

    suspend fun getAllMappingsAsMap(): Map<String, String> = try {
        customKeyMappingDao
            .getAllMappings()
            .associate { it.baseKey to it.customSymbol }
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "CustomKeyMappingRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "getAllMappingsAsMap")
        )
        emptyMap()
    }

    suspend fun setMapping(baseKey: String, customSymbol: String): Result<Unit> {
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
                context = mapOf("operation" to "setMapping", "baseKey" to normalizedKey)
            )
            Result.failure(e)
        }
    }

    suspend fun removeMapping(baseKey: String): Result<Boolean> = try {
        val removed = customKeyMappingDao.removeMapping(baseKey.lowercase()) > 0
        Result.success(removed)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "CustomKeyMappingRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "removeMapping", "baseKey" to baseKey)
        )
        Result.failure(e)
    }

    suspend fun clearAllMappings(): Result<Int> = try {
        val count = customKeyMappingDao.clearAllMappings()
        Result.success(count)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "CustomKeyMappingRepository",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = e,
            context = mapOf("operation" to "clearAllMappings")
        )
        Result.failure(e)
    }
}
