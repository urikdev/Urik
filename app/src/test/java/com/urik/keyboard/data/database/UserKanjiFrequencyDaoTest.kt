package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserKanjiFrequencyDaoTest {
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: UserKanjiFrequencyDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.userKanjiFrequencyDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `incrementBy inserts new entry on first call`() = runTest {
        dao.incrementBy("わたし", "私", 1L, System.currentTimeMillis())

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("わたし", rows[0].reading)
        assertEquals("私", rows[0].surface)
        assertEquals(1L, rows[0].frequency)
    }

    @Test
    fun `incrementBy accumulates frequency on repeat calls for same reading and surface`() = runTest {
        val now = System.currentTimeMillis()
        dao.incrementBy("わたし", "私", 1L, now)
        dao.incrementBy("わたし", "私", 1L, now + 1000)

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(2L, rows[0].frequency)
        assertEquals(now + 1000, rows[0].lastUsed)
    }

    @Test
    fun `getAll returns all persisted entries`() = runTest {
        val now = System.currentTimeMillis()
        dao.incrementBy("わたし", "私", 1L, now)
        dao.incrementBy("ねこ", "猫", 1L, now)
        dao.incrementBy("いぬ", "犬", 1L, now)

        val rows = dao.getAll()
        assertEquals(3, rows.size)
    }

    @Test
    fun `clearAll removes all entries`() = runTest {
        val now = System.currentTimeMillis()
        dao.incrementBy("わたし", "私", 1L, now)
        dao.incrementBy("ねこ", "猫", 1L, now)

        val deleted = dao.clearAll()
        assertEquals(2, deleted)
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `unique index treats different surface as different row`() = runTest {
        val now = System.currentTimeMillis()
        dao.incrementBy("わたし", "私", 1L, now)
        dao.incrementBy("わたし", "渡し", 1L, now)

        val rows = dao.getAll()
        assertEquals(2, rows.size)
    }
}
