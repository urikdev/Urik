package com.urik.keyboard.di

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("KotlinConstantConditions")
class DatabaseModuleCorruptionTest {
    @Test
    fun `SQLiteNotADatabaseException is-a SQLiteException`() {
        val ex = SQLiteNotADatabaseException("file is not a database")
        assertTrue(ex is SQLiteException)
    }

    @Test
    fun `SQLiteNotADatabaseException is NOT-a SQLiteDatabaseCorruptException`() {
        val ex = SQLiteNotADatabaseException("file is not a database")
        assertFalse(ex is SQLiteDatabaseCorruptException)
    }

    @Test
    fun `multi-catch guard covers SQLiteNotADatabaseException`() {
        val ex = SQLiteNotADatabaseException("file is not a database")
        var caught: Boolean

        try {
            throw ex
        } catch (e: Exception) {
            if (e is SQLiteDatabaseCorruptException || e is SQLiteNotADatabaseException) {
                caught = true
            } else {
                throw e
            }
        }

        assertTrue("multi-catch guard must cover SQLiteNotADatabaseException", caught)
    }

    @Test
    fun `multi-catch guard covers SQLiteDatabaseCorruptException`() {
        val ex = SQLiteDatabaseCorruptException("database disk image is malformed")
        var caught: Boolean

        try {
            throw ex
        } catch (e: Exception) {
            if (e is SQLiteDatabaseCorruptException || e is SQLiteNotADatabaseException) {
                caught = true
            } else {
                throw e
            }
        }

        assertTrue("multi-catch guard must cover SQLiteDatabaseCorruptException", caught)
    }

    @Test
    fun `multi-catch guard does not cover generic SQLiteException`() {
        val ex = object : SQLiteException("SQLITE_BUSY: database is locked") {}
        var caught = false

        try {
            throw ex
        } catch (e: Exception) {
            if (e is SQLiteDatabaseCorruptException || e is SQLiteNotADatabaseException) {
                caught = true
            } else if (e !is SQLiteException) {
                throw e
            }
        }

        assertFalse("multi-catch guard must NOT cover generic SQLiteException", caught)
    }
}
