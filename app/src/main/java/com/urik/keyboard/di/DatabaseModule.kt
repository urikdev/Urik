package com.urik.keyboard.di

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.urik.keyboard.data.database.ClipboardDao
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.data.database.DatabaseSecurityManager
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.utils.ErrorLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides database dependencies with SQLCipher encryption.
 *
 * Database lifecycle:
 * - First launch: Creates encrypted database (if device has lock screen)
 * - Upgrade from unencrypted: Auto-migrates silently
 * - No lock screen: Falls back to unencrypted database
 * - Corruption detection: Automatically deletes and recreates corrupt database
 *
 * Singleton providers ensure single database instance per app lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabaseSecurityManager(
        @ApplicationContext context: Context,
    ): DatabaseSecurityManager = DatabaseSecurityManager(context)

    /**
     * Provides encrypted Room database with automatic migration and corruption recovery.
     *
     * Behavior:
     * - Auto-migrates unencrypted → encrypted if device has lock screen
     * - Migration failures preserve original database (no data loss)
     * - Falls back to unencrypted if no device lock screen configured
     * - Detects database corruption on initialization and recreates automatically
     *
     * @return Singleton KeyboardDatabase instance
     */
    @Provides
    @Singleton
    fun provideKeyboardDatabase(
        @ApplicationContext context: Context,
        securityManager: DatabaseSecurityManager,
    ): KeyboardDatabase {
        var passphrase: ByteArray? = null
        try {
            if (securityManager.shouldMigrateToEncrypted(context)) {
                try {
                    securityManager.migrateToEncryptedDatabase(context)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf("phase" to "migration"),
                    )
                    throw e
                }
            }

            passphrase =
                try {
                    securityManager.getDatabasePassphrase()
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf("phase" to "passphrase_generation"),
                    )
                    throw e
                }

            return try {
                KeyboardDatabase.getInstance(context, passphrase)
            } catch (e: SQLiteDatabaseCorruptException) {
                passphrase?.fill(0)
                passphrase = null

                ErrorLogger.logException(
                    component = "DatabaseModule",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("phase" to "corruption_detected", "action" to "deleting_and_recreating"),
                )

                deleteDatabaseFiles(context)
                KeyboardDatabase.resetInstance()

                val freshPassphrase = securityManager.getDatabasePassphrase()
                try {
                    KeyboardDatabase.getInstance(context, freshPassphrase)
                } catch (e: Exception) {
                    freshPassphrase?.fill(0)
                    throw e
                }
            }
        } catch (e: Exception) {
            passphrase?.fill(0)

            if (e.message?.contains("migration") != true && e.message?.contains("passphrase") != true) {
                ErrorLogger.logException(
                    component = "DatabaseModule",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("phase" to "database_init"),
                )
            }
            throw e
        }
    }

    private fun deleteDatabaseFiles(context: Context) {
        val dbPath = context.applicationContext.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbPath.delete()

        dbPath.parentFile
            ?.listFiles()
            ?.filter {
                it.name.startsWith(KeyboardDatabase.DATABASE_NAME)
            }?.forEach { it.delete() }
    }

    @Provides
    fun provideLearnedWordDao(database: KeyboardDatabase): LearnedWordDao = database.learnedWordDao()

    @Provides
    fun provideClipboardDao(database: KeyboardDatabase): ClipboardDao = database.clipboardDao()

    @Provides
    fun provideCustomKeyMappingDao(database: KeyboardDatabase): CustomKeyMappingDao = database.customKeyMappingDao()
}
