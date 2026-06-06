package com.urik.keyboard.di

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.urik.keyboard.data.database.DatabaseSecurityManager
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.utils.ErrorLogger
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
class DatabaseModuleRecoveryTest {
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
        dbFile.delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-journal").delete()
    }

    @Test
    fun `SQLiteNotADatabaseException triggers full recovery path`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        val recoveredDb: KeyboardDatabase = mock()
        var openCallCount = 0
        var resetCalled = false

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    return when (openCallCount) {
                        1 -> throw SQLiteNotADatabaseException("file is not a database")
                        else -> recoveredDb
                    }
                }

                override fun reset() {
                    resetCalled = true
                }
            }
        )

        val result = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue("reset must be called on recovery", resetCalled)
        assertTrue("second open must be called after reset", openCallCount == 2)
        assertTrue("recovery returns the fresh database instance", result === recoveredDb)
        assertFalse("db file should be deleted on recovery", dbFile.exists())
    }

    @Test
    fun `SQLiteDatabaseCorruptException triggers full recovery path`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        val recoveredDb: KeyboardDatabase = mock()
        var openCallCount = 0

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    return when (openCallCount) {
                        1 -> throw SQLiteDatabaseCorruptException("database disk image is malformed")
                        else -> recoveredDb
                    }
                }

                override fun reset() = Unit
            }
        )

        val result = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue("second open must be called after reset", openCallCount == 2)
        assertTrue("recovery returns the fresh database instance", result === recoveredDb)
    }

    @Test
    fun `abort path - no passphrase and db exists - rethrows without deleting files`() {
        whenever(securityManager.getDatabasePassphrase()).thenReturn(null)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        var resetCalled = false

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
                    throw SQLiteNotADatabaseException("file is not a database")

                override fun reset() {
                    resetCalled = true
                }
            }
        )

        val beforeCount = ErrorLogger.getErrorCount()

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected exception to be rethrown")
        } catch (_: SQLiteNotADatabaseException) {
            assertTrue("db file must NOT be deleted on abort", dbFile.exists())
            assertFalse("reset must NOT be called on abort", resetCalled)
            assertEquals(beforeCount + 1, ErrorLogger.getErrorCount())
        }
    }

    @Test
    fun `freshPassphrase is zeroed when second open throws`() {
        var capturedFreshPassphrase: ByteArray? = null
        val initialPassphrase = ByteArray(32) { 0x42 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenAnswer {
                ByteArray(32) { 0x43 }.also { capturedFreshPassphrase = it }
            }

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        var openCallCount = 0
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    throw SQLiteNotADatabaseException("file is not a database")
                }

                override fun reset() = Unit
            }
        )

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected exception")
        } catch (_: Exception) {
        }

        val fresh = capturedFreshPassphrase
        assertNotNull("freshPassphrase reference must be captured", fresh)
        assertTrue(
            "freshPassphrase must be zeroed after double-fault",
            fresh!!.all { it == 0.toByte() }
        )
    }

    @Test
    fun `initialPassphrase is zeroed on success path`() {
        val passphraseBytes = ByteArray(32) { 0x42 }
        whenever(securityManager.getDatabasePassphrase()).thenReturn(passphraseBytes)

        val successDb: KeyboardDatabase = mock()
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase = successDb
                override fun reset() = Unit
            }
        )

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue(
            "initialPassphrase must be zeroed on success",
            passphraseBytes.all { it == 0.toByte() }
        )
    }

    @Test
    fun `initialPassphrase is zeroed on recovery path`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

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

        assertTrue(
            "initialPassphrase must be zeroed on recovery path",
            initialPassphrase.all { it == 0.toByte() }
        )
    }

    @Test
    fun `initialPassphrase is zeroed on double-fault path`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
                    throw SQLiteNotADatabaseException("corrupt")

                override fun reset() = Unit
            }
        )

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected exception")
        } catch (_: Exception) {
        }

        assertTrue(
            "initialPassphrase must be zeroed on double-fault path",
            initialPassphrase.all { it == 0.toByte() }
        )
    }

    @Test
    fun `null passphrase after recovery throws IllegalStateException`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(null)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

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
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message must mention passphrase unavailable",
                e.message?.contains("passphrase") == true
            )
            assertEquals(beforeCount + 2, ErrorLogger.getErrorCount())
        }
    }

    @Test
    fun `passphraseWasAvailable=false and dbExists=false falls through to recovery then throws on null passphrase`() {
        whenever(securityManager.getDatabasePassphrase()).thenReturn(null)

        var resetCalled = false
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
                    throw SQLiteNotADatabaseException("file is not a database")
                override fun reset() {
                    resetCalled = true
                }
            }
        )

        val beforeCount = ErrorLogger.getErrorCount()

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue("message must mention passphrase", e.message?.contains("passphrase") == true)
            assertTrue("reset must be called (recovery body reached)", resetCalled)
            assertEquals(beforeCount + 2, ErrorLogger.getErrorCount())
        }
    }

    @Test
    fun `second open failure in recovery body logs via ErrorLogger`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        val beforeCount = ErrorLogger.getErrorCount()

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
                    throw SQLiteNotADatabaseException("corrupt")

                override fun reset() = Unit
            }
        )

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected exception")
        } catch (_: Exception) {
        }

        val afterCount = ErrorLogger.getErrorCount()
        assertEquals(beforeCount + 2, afterCount)
    }

    @Test
    fun `opener reset failure during recovery is logged with correct phase`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        whenever(securityManager.getDatabasePassphrase()).thenReturn(initialPassphrase)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
                    throw SQLiteNotADatabaseException("corrupt")

                override fun reset() = error("reset failed")
            }
        )

        val beforeCount = ErrorLogger.getErrorCount()

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected exception")
        } catch (_: Exception) {
        }

        assertEquals(beforeCount + 2, ErrorLogger.getErrorCount())
    }

    @Test
    fun `second getDatabasePassphrase call throws during recovery is logged`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenThrow(RuntimeException("keystore failure"))

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        var openCallCount = 0
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    if (openCallCount == 1) throw SQLiteNotADatabaseException("corrupt")
                    throw AssertionError("second open must not be called")
                }

                override fun reset() = Unit
            }
        )

        val beforeCount = ErrorLogger.getErrorCount()

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected RuntimeException")
        } catch (_: RuntimeException) {
        }

        assertEquals(beforeCount + 2, ErrorLogger.getErrorCount())
    }

    @Test
    fun `initialPassphrase is zeroed on abort path`() {
        val passphraseBytes = ByteArray(32) { 0x42 }
        whenever(securityManager.getDatabasePassphrase()).thenReturn(passphraseBytes)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
                    throw SQLiteNotADatabaseException("file is not a database")
                override fun reset() = Unit
            }
        )

        try {
            DatabaseModule.provideKeyboardDatabase(context, securityManager)
            fail("Expected exception")
        } catch (_: Exception) {
        }

        assertTrue(
            "initialPassphrase must be zeroed on abort path",
            passphraseBytes.all { it == 0.toByte() }
        )
    }

    @Test
    fun `recovery path calls opener reset exactly once`() {
        val initialPassphrase = ByteArray(32) { 0x42 }
        val freshPassphrase = ByteArray(32) { 0x43 }
        whenever(securityManager.getDatabasePassphrase())
            .thenReturn(initialPassphrase)
            .thenReturn(freshPassphrase)

        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.createNewFile()

        var resetCallCount = 0
        val recoveredDb: KeyboardDatabase = mock()
        var openCallCount = 0

        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    openCallCount++
                    return if (openCallCount == 1) throw SQLiteNotADatabaseException("corrupt") else recoveredDb
                }

                override fun reset() {
                    resetCallCount++
                }
            }
        )

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertEquals("opener.reset() called exactly once during recovery", 1, resetCallCount)
        assertEquals("opener.open() called exactly twice (initial + recovery)", 2, openCallCount)
    }
}
