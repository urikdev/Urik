@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [CustomKeyMappingDao] CRUD operations and Flow observation.
 */
@RunWith(RobolectricTestRunner::class)
class CustomKeyMappingDaoTest {
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: CustomKeyMappingDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.customKeyMappingDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `upsertMapping inserts new mapping`() =
        runTest {
            val mapping = CustomKeyMapping.create("a", "@")

            dao.upsertMapping(mapping)

            val retrieved = dao.getMappingForKey("a")
            assertNotNull(retrieved)
            assertEquals("a", retrieved?.baseKey)
            assertEquals("@", retrieved?.customSymbol)
        }

    @Test
    fun `upsertMapping replaces existing mapping`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("a", "@"))
            dao.upsertMapping(CustomKeyMapping.create("a", "#"))

            val retrieved = dao.getMappingForKey("a")
            assertEquals("#", retrieved?.customSymbol)
            assertEquals(1, dao.getMappingCount())
        }

    @Test
    fun `getMappingForKey returns null for missing key`() =
        runTest {
            val result = dao.getMappingForKey("missing")

            assertNull(result)
        }

    @Test
    fun `getAllMappings returns all mappings sorted by base key`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("z", "1"))
            dao.upsertMapping(CustomKeyMapping.create("a", "2"))
            dao.upsertMapping(CustomKeyMapping.create("m", "3"))

            val results = dao.getAllMappings()

            assertEquals(3, results.size)
            assertEquals("a", results[0].baseKey)
            assertEquals("m", results[1].baseKey)
            assertEquals("z", results[2].baseKey)
        }

    @Test
    fun `observeAllMappings emits updates`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("a", "@"))

            val results = dao.observeAllMappings().first()

            assertEquals(1, results.size)
            assertEquals("a", results[0].baseKey)
            assertEquals("@", results[0].customSymbol)
        }

    @Test
    fun `removeMapping deletes existing mapping`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("a", "@"))

            val rowsAffected = dao.removeMapping("a")

            assertEquals(1, rowsAffected)
            assertNull(dao.getMappingForKey("a"))
        }

    @Test
    fun `removeMapping returns 0 for missing key`() =
        runTest {
            val rowsAffected = dao.removeMapping("missing")

            assertEquals(0, rowsAffected)
        }

    @Test
    fun `clearAllMappings removes all mappings`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("a", "@"))
            dao.upsertMapping(CustomKeyMapping.create("b", "#"))
            dao.upsertMapping(CustomKeyMapping.create("c", "$"))

            val rowsAffected = dao.clearAllMappings()

            assertEquals(3, rowsAffected)
            assertEquals(0, dao.getMappingCount())
        }

    @Test
    fun `clearAllMappings returns 0 when empty`() =
        runTest {
            val rowsAffected = dao.clearAllMappings()

            assertEquals(0, rowsAffected)
        }

    @Test
    fun `getMappingCount returns correct count`() =
        runTest {
            assertEquals(0, dao.getMappingCount())

            dao.upsertMapping(CustomKeyMapping.create("a", "@"))
            assertEquals(1, dao.getMappingCount())

            dao.upsertMapping(CustomKeyMapping.create("b", "#"))
            assertEquals(2, dao.getMappingCount())
        }

    @Test
    fun `mapping stores createdAt timestamp`() =
        runTest {
            val beforeInsert = System.currentTimeMillis()
            dao.upsertMapping(CustomKeyMapping.create("a", "@"))
            val afterInsert = System.currentTimeMillis()

            val retrieved = dao.getMappingForKey("a")

            assertNotNull(retrieved)
            assertTrue(retrieved!!.createdAt >= beforeInsert)
            assertTrue(retrieved.createdAt <= afterInsert)
        }

    @Test
    fun `base key is case sensitive in storage`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("a", "@"))

            val lowercase = dao.getMappingForKey("a")
            val uppercase = dao.getMappingForKey("A")

            assertNotNull(lowercase)
            assertNull(uppercase)
        }

    @Test
    fun `custom symbol can be multi-character`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("a", "->"))

            val retrieved = dao.getMappingForKey("a")

            assertEquals("->", retrieved?.customSymbol)
        }

    @Test
    fun `custom symbol can be emoji`() =
        runTest {
            dao.upsertMapping(CustomKeyMapping.create("a", "😀"))

            val retrieved = dao.getMappingForKey("a")

            assertEquals("😀", retrieved?.customSymbol)
        }
}
