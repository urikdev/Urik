package com.urik.keyboard.service

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.urik.keyboard.data.database.CustomKeyMapping
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SettingsExport(
    val version: Int = 1,
    val exportedAt: String,
    val preferences: Map<String, String>,
    val customKeyMappings: List<ExportedKeyMapping>
)

@Serializable
data class ExportedKeyMapping(val baseKey: String, val customSymbol: String)

data class SettingsExportResult(val mappingCount: Int)

data class SettingsImportResult(val mappingCount: Int)

@Singleton
open class SettingsBackupManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val customKeyMappingDao: CustomKeyMappingDao,
    private val database: KeyboardDatabase
) {
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @VisibleForTesting
    internal open suspend fun <R> runInTransaction(block: suspend () -> R): R = database.withTransaction { block() }

    suspend fun exportToUri(uri: Uri): Result<SettingsExportResult> = withContext(ioDispatcher) {
        try {
            val prefsResult = settingsRepository.exportPreferences()
            if (prefsResult.isFailure) return@withContext Result.failure(prefsResult.exceptionOrNull()!!)
            val prefs = prefsResult.getOrThrow()

            val mappings = customKeyMappingDao.getAllMappings()
            val exportedMappings = mappings.map { ExportedKeyMapping(it.baseKey, it.customSymbol) }

            val export = SettingsExport(
                version = 1,
                exportedAt = Instant.now().toString(),
                preferences = prefs,
                customKeyMappings = exportedMappings
            )

            val jsonString = json.encodeToString(export)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.failure(Exception("Failed to open output stream"))

            Result.success(SettingsExportResult(mappingCount = exportedMappings.size))
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SettingsBackupManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "export")
            )
            Result.failure(e)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<SettingsImportResult> = withContext(ioDispatcher) {
        try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: return@withContext Result.failure(Exception("Failed to open input stream"))

            val export = json.decodeFromString<SettingsExport>(jsonString)

            settingsRepository.importPreferences(export.preferences)
                .getOrElse { return@withContext Result.failure(it) }

            var insertedCount = 0
            runInTransaction {
                customKeyMappingDao.clearAllMappings()
                export.customKeyMappings.forEach { mapping ->
                    if (mapping.baseKey.isNotBlank() && mapping.customSymbol.isNotBlank()) {
                        customKeyMappingDao.upsertMapping(
                            CustomKeyMapping(
                                baseKey = mapping.baseKey,
                                customSymbol = mapping.customSymbol
                            )
                        )
                        insertedCount++
                    }
                }
            }

            Result.success(SettingsImportResult(mappingCount = insertedCount))
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SettingsBackupManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "import")
            )
            Result.failure(e)
        }
    }
}
