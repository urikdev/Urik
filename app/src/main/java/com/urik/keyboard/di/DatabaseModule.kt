package com.urik.keyboard.di

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.data.database.ClipboardDao
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.data.database.DatabaseSecurityManager
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.UserKanjiFrequencyDao
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.utils.ErrorLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

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
    private val defaultOpener = object : DatabaseOpener {
        override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
            KeyboardDatabase.getInstance(context, passphrase)

        override fun reset() = KeyboardDatabase.resetInstance()
    }

    @Volatile
    @VisibleForTesting
    internal var opener: DatabaseOpener = defaultOpener

    @VisibleForTesting
    internal fun setOpenerForTesting(testOpener: DatabaseOpener) {
        opener = testOpener
    }

    @VisibleForTesting
    internal fun resetOpenerForTesting() {
        opener = defaultOpener
    }

    @Provides
    @Singleton
    fun provideDatabaseSecurityManager(@ApplicationContext context: Context): DatabaseSecurityManager =
        DatabaseSecurityManager(context)

    @Provides
    @Singleton
    @Suppress("ThrowsCount", "InstanceOfCheckForException")
    fun provideKeyboardDatabase(
        @ApplicationContext context: Context,
        securityManager: DatabaseSecurityManager
    ): KeyboardDatabase {
        var passphrase: ByteArray? = null
        var alreadyLogged = false
        try {
            if (securityManager.shouldMigrateToEncrypted(context)) {
                try {
                    securityManager.migrateToEncryptedDatabase(context)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf("phase" to "migration")
                    )
                    alreadyLogged = true
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
                        context = mapOf("phase" to "passphrase_generation")
                    )
                    alreadyLogged = true
                    throw e
                }

            val passphraseWasAvailable = passphrase != null
            val initialPassphrase = passphrase
            passphrase = null
            return try {
                opener.open(context, initialPassphrase)
            } catch (e: Exception) {
                if (e !is SQLiteDatabaseCorruptException && e !is SQLiteNotADatabaseException) throw e

                val dbExists = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME).exists()
                if (!passphraseWasAvailable && dbExists) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf(
                            "phase" to "passphrase_unavailable",
                            "action" to "aborting_to_prevent_data_loss"
                        )
                    )
                    alreadyLogged = true
                    throw e
                }

                alreadyLogged = true
                ErrorLogger.logException(
                    component = "DatabaseModule",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("phase" to "corruption_detected", "action" to "deleting_and_recreating")
                )

                deleteDatabaseFiles(context)
                try {
                    opener.reset()
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf("phase" to "corruption_reset_failed")
                    )
                    throw e
                }

                val freshPassphrase = try {
                    securityManager.getDatabasePassphrase()
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = e,
                        context = mapOf("phase" to "corruption_passphrase_fetch_failed")
                    )
                    alreadyLogged = true
                    throw e
                }
                if (freshPassphrase == null) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = IllegalStateException(
                            "Cannot open database after corruption recovery: no passphrase available"
                        ),
                        context = mapOf("phase" to "no_passphrase_after_recovery")
                    )
                    error("Cannot open database after corruption recovery: no passphrase available")
                }
                try {
                    opener.open(context, freshPassphrase)
                } catch (reopenException: Exception) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = reopenException,
                        context = mapOf("phase" to "corruption_recovery_reopen_failed")
                    )
                    throw reopenException
                } finally {
                    freshPassphrase.fill(0)
                }
            } finally {
                initialPassphrase?.fill(0)
            }
        } catch (e: Exception) {
            passphrase?.fill(0)

            if (!alreadyLogged) {
                ErrorLogger.logException(
                    component = "DatabaseModule",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("phase" to "database_init")
                )
            }
            throw e
        }
    }

    private fun deleteDatabaseFiles(context: Context) {
        val dbPath = context.applicationContext.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbPath.delete()
        if (dbPath.exists()) {
            val ioException = IOException("Failed to delete corrupt database file: ${dbPath.absolutePath}")
            ErrorLogger.logException(
                component = "DatabaseModule",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = ioException,
                context = mapOf("phase" to "corruption_db_delete_failed", "path" to dbPath.absolutePath)
            )
            throw ioException
        }

        dbPath.parentFile
            ?.listFiles()
            ?.filter {
                it.name == KeyboardDatabase.DATABASE_NAME ||
                    it.name.startsWith("${KeyboardDatabase.DATABASE_NAME}-")
            }?.forEach {
                if (!it.delete()) {
                    ErrorLogger.logException(
                        component = "DatabaseModule",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = IOException("Failed to delete database sidecar: ${it.name}"),
                        context = mapOf("phase" to "corruption_sidecar_delete_failed", "file" to it.name)
                    )
                }
            }
    }

    @Provides
    fun provideLearnedWordDao(database: KeyboardDatabase): LearnedWordDao = database.learnedWordDao()

    @Provides
    fun provideClipboardDao(database: KeyboardDatabase): ClipboardDao = database.clipboardDao()

    @Provides
    fun provideCustomKeyMappingDao(database: KeyboardDatabase): CustomKeyMappingDao = database.customKeyMappingDao()

    @Provides
    fun provideUserWordFrequencyDao(database: KeyboardDatabase): UserWordFrequencyDao = database.userWordFrequencyDao()

    @Provides
    fun provideUserWordBigramDao(database: KeyboardDatabase): UserWordBigramDao = database.userWordBigramDao()

    @Provides
    fun provideUserKanjiFrequencyDao(database: KeyboardDatabase): UserKanjiFrequencyDao =
        database.userKanjiFrequencyDao()
}
