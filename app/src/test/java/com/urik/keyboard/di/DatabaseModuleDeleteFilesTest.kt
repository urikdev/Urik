package com.urik.keyboard.di

import android.content.Context
import com.urik.keyboard.data.database.DatabaseSecurityManager
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.utils.ErrorLogger
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseModuleDeleteFilesTest {
    @Mock private lateinit var securityManager: DatabaseSecurityManager

    private lateinit var context: Context
    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        whenever(securityManager.shouldMigrateToEncrypted(any())).thenReturn(false)
        DatabaseModule.resetOpenerForTesting()
    }

    @After
    fun tearDown() {
        closeable.close()
        DatabaseModule.resetOpenerForTesting()
    }

    @After
    fun cleanUpDatabaseFiles() {
        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.deleteRecursively()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-journal").delete()
        File(dbFile.path + "_backup.db").delete()
    }

    @Test
    fun `recovery deletes main db file and all sidecar files`() {
        val dbDir = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        val dbName = KeyboardDatabase.DATABASE_NAME
        val mainFile = context.getDatabasePath(dbName).apply { createNewFile() }
        val shmFile = dbDir.resolve("$dbName-shm").apply { createNewFile() }
        val walFile = dbDir.resolve("$dbName-wal").apply { createNewFile() }
        val journalFile = dbDir.resolve("$dbName-journal").apply { createNewFile() }
        val unrelatedFile = dbDir.resolve("other_database.db").apply { createNewFile() }

        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val recoveredDb: KeyboardDatabase = mock()
        var openCallCount = 0
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    return if (openCallCount == 1) throw SQLiteNotADatabaseException("corrupt") else recoveredDb
                }

                override fun reset() = Unit
            }
        )

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertFalse("main db file deleted", mainFile.exists())
        assertFalse("shm sidecar deleted", shmFile.exists())
        assertFalse("wal sidecar deleted", walFile.exists())
        assertFalse("journal sidecar deleted", journalFile.exists())
        assertTrue("unrelated file preserved", unrelatedFile.exists())
    }

    @Test
    fun `recovery does not delete files sharing keyboard_database name prefix`() {
        val dbDir = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        val dbName = KeyboardDatabase.DATABASE_NAME
        val mainFile = context.getDatabasePath(dbName).apply { createNewFile() }
        val backupFile = dbDir.resolve("${dbName}_backup.db").apply { createNewFile() }

        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val recoveredDb: KeyboardDatabase = mock()
        var openCallCount = 0
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    return if (openCallCount == 1) throw SQLiteNotADatabaseException("corrupt") else recoveredDb
                }

                override fun reset() = Unit
            }
        )

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertFalse("main db file deleted", mainFile.exists())
        assertTrue("backup file with underscore prefix preserved", backupFile.exists())
    }

    @Test
    fun `IOException thrown and logged when main db file cannot be deleted`() {
        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.mkdirs()
        File(dbFile, "dummy").createNewFile()

        whenever(securityManager.getDatabasePassphrase()).thenReturn(ByteArray(32) { 0x42 })

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
                    throw SQLiteNotADatabaseException("corrupt")

                override fun reset() = Unit
            }
        )

        val beforeCount = ErrorLogger.getErrorCount()

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            org.junit.Assert.fail("Expected exception")
        } catch (_: Exception) {
        }

        assertEquals(beforeCount + 2, ErrorLogger.getErrorCount())
    }

    @Test
    fun `recovery succeeds when only some sidecar files exist`() {
        val dbDir = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        val dbName = KeyboardDatabase.DATABASE_NAME
        val mainFile = context.getDatabasePath(dbName).apply { createNewFile() }
        val walFile = dbDir.resolve("$dbName-wal").apply { createNewFile() }

        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val recoveredDb: KeyboardDatabase = mock()
        var openCallCount = 0
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    return if (openCallCount == 1) throw SQLiteNotADatabaseException("corrupt") else recoveredDb
                }

                override fun reset() = Unit
            }
        )

        val result = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertFalse("main db file deleted", mainFile.exists())
        assertFalse("wal sidecar deleted", walFile.exists())
        assertTrue("recovery returns fresh db", result === recoveredDb)
    }
}
