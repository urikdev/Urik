package com.urik.keyboard.di

import android.content.Context
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
     * Provides encrypted Room database with automatic migration.
     *
     * Behavior:
     * - Auto-migrates unencrypted → encrypted if device has lock screen
     * - Migration failures preserve original database (no data loss)
     * - Falls back to unencrypted if no device lock screen configured
     *
     * @return Singleton KeyboardDatabase instance
     */
    @Provides
    @Singleton
    fun provideKeyboardDatabase(
        @ApplicationContext context: Context,
        securityManager: DatabaseSecurityManager,
    ): KeyboardDatabase {
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

            val passphrase =
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

            return KeyboardDatabase.getInstance(context, passphrase)
        } catch (e: Exception) {
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

    @Provides
    fun provideLearnedWordDao(database: KeyboardDatabase): LearnedWordDao = database.learnedWordDao()
}
