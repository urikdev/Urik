@file:Suppress("ktlint:standard:no-wildcard-imports")

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

/**
 * Tests [ClipboardDao] CRUD operations, pin/unpin, and sorting.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardDaoTest {
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: ClipboardDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.clipboardDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `insert adds item and returns id`() =
        runTest {
            val item = createTestItem("Hello world")

            val id = dao.insert(item)

            assertTrue(id > 0)
            val count = dao.getCount()
            assertEquals(1, count)
        }

    @Test
    fun `insert with OnConflictStrategy IGNORE skips duplicate`() =
        runTest {
            val item1 = createTestItem("Hello world", timestamp = 100)
            val item2 = createTestItem("Hello world", timestamp = 200)

            val id1 = dao.insert(item1)
            val id2 = dao.insert(item2)

            assertTrue(id1 > 0)
            assertEquals(-1L, id2)
            assertEquals(1, dao.getCount())
        }

    @Test
    fun `getRecentItems returns unpinned sorted by timestamp DESC`() =
        runTest {
            dao.insert(createTestItem("First", timestamp = 100, isPinned = false))
            dao.insert(createTestItem("Second", timestamp = 200, isPinned = false))
            dao.insert(createTestItem("Third", timestamp = 300, isPinned = false))
            dao.insert(createTestItem("Pinned", timestamp = 400, isPinned = true))

            val results = dao.getRecentItems()

            assertEquals(3, results.size)
            assertEquals("Third", results[0].content)
            assertEquals("Second", results[1].content)
            assertEquals("First", results[2].content)
        }

    @Test
    fun `getPinnedItems returns pinned sorted by timestamp DESC`() =
        runTest {
            dao.insert(createTestItem("Pinned1", timestamp = 100, isPinned = true))
            dao.insert(createTestItem("Pinned2", timestamp = 200, isPinned = true))
            dao.insert(createTestItem("Unpinned", timestamp = 300, isPinned = false))

            val results = dao.getPinnedItems()

            assertEquals(2, results.size)
            assertEquals("Pinned2", results[0].content)
            assertEquals("Pinned1", results[1].content)
        }

    @Test
    fun `updatePinned toggles pin status`() =
        runTest {
            val item = createTestItem("Test", isPinned = false)
            val id = dao.insert(item)

            dao.updatePinned(id, true)

            val pinnedItems = dao.getPinnedItems()
            assertEquals(1, pinnedItems.size)
            assertEquals("Test", pinnedItems[0].content)
            assertTrue(pinnedItems[0].isPinned)
        }

    @Test
    fun `updatePinned unpins item`() =
        runTest {
            val item = createTestItem("Test", isPinned = true)
            val id = dao.insert(item)

            dao.updatePinned(id, false)

            val recentItems = dao.getRecentItems()
            assertEquals(1, recentItems.size)
            assertEquals("Test", recentItems[0].content)
            assertEquals(false, recentItems[0].isPinned)
        }

    @Test
    fun `delete removes item`() =
        runTest {
            val item = createTestItem("Test")
            val id = dao.insert(item)

            dao.delete(id)

            assertEquals(0, dao.getCount())
        }

    @Test
    fun `deleteAllUnpinned removes only unpinned items`() =
        runTest {
            dao.insert(createTestItem("Unpinned1", isPinned = false))
            dao.insert(createTestItem("Unpinned2", isPinned = false))
            dao.insert(createTestItem("Pinned1", isPinned = true))
            dao.insert(createTestItem("Pinned2", isPinned = true))

            dao.deleteAllUnpinned()

            assertEquals(2, dao.getCount())
            val pinnedItems = dao.getPinnedItems()
            assertEquals(2, pinnedItems.size)
            val recentItems = dao.getRecentItems()
            assertEquals(0, recentItems.size)
        }

    @Test
    fun `getCount returns correct count`() =
        runTest {
            dao.insert(createTestItem("First"))
            dao.insert(createTestItem("Second"))
            dao.insert(createTestItem("Third"))

            assertEquals(3, dao.getCount())
        }

    @Test
    fun `getCount returns zero for empty table`() =
        runTest {
            assertEquals(0, dao.getCount())
        }

    @Test
    fun `insert handles long content`() =
        runTest {
            val longContent = "A".repeat(10000)
            val item = createTestItem(longContent)

            val id = dao.insert(item)

            assertTrue(id > 0)
            val recentItems = dao.getRecentItems()
            assertEquals(longContent, recentItems[0].content)
        }

    @Test
    fun `getRecentItems handles empty table`() =
        runTest {
            val results = dao.getRecentItems()

            assertTrue(results.isEmpty())
        }

    @Test
    fun `getPinnedItems handles empty table`() =
        runTest {
            val results = dao.getPinnedItems()

            assertTrue(results.isEmpty())
        }

    @Test
    fun `timestamp ordering is stable`() =
        runTest {
            val baseTime = System.currentTimeMillis()
            dao.insert(createTestItem("First", timestamp = baseTime))
            dao.insert(createTestItem("Second", timestamp = baseTime + 1))
            dao.insert(createTestItem("Third", timestamp = baseTime + 2))

            val results = dao.getRecentItems()

            assertEquals("Third", results[0].content)
            assertEquals("Second", results[1].content)
            assertEquals("First", results[2].content)
        }

    @Test
    fun `pin and unpin workflow`() =
        runTest {
            val item = createTestItem("Test")
            val id = dao.insert(item)

            dao.updatePinned(id, true)
            assertEquals(1, dao.getPinnedItems().size)
            assertEquals(0, dao.getRecentItems().size)

            dao.updatePinned(id, false)
            assertEquals(0, dao.getPinnedItems().size)
            assertEquals(1, dao.getRecentItems().size)
        }

    @Test
    fun `getUnpinnedCount returns correct count`() =
        runTest {
            dao.insert(createTestItem("Unpinned1", isPinned = false))
            dao.insert(createTestItem("Unpinned2", isPinned = false))
            dao.insert(createTestItem("Pinned1", isPinned = true))
            dao.insert(createTestItem("Pinned2", isPinned = true))

            assertEquals(2, dao.getUnpinnedCount())
        }

    @Test
    fun `enforceMaxItems keeps only newest unpinned items`() =
        runTest {
            dao.insert(createTestItem("Old1", timestamp = 100, isPinned = false))
            dao.insert(createTestItem("Old2", timestamp = 200, isPinned = false))
            dao.insert(createTestItem("New1", timestamp = 300, isPinned = false))
            dao.insert(createTestItem("New2", timestamp = 400, isPinned = false))
            dao.insert(createTestItem("New3", timestamp = 500, isPinned = false))
            dao.insert(createTestItem("Pinned", timestamp = 50, isPinned = true))

            dao.enforceMaxItems(3)

            val recentItems = dao.getRecentItems()
            assertEquals(3, recentItems.size)
            assertEquals("New3", recentItems[0].content)
            assertEquals("New2", recentItems[1].content)
            assertEquals("New1", recentItems[2].content)

            val pinnedItems = dao.getPinnedItems()
            assertEquals(1, pinnedItems.size)
            assertEquals("Pinned", pinnedItems[0].content)
        }

    @Test
    fun `enforceMaxItems does not delete pinned items`() =
        runTest {
            dao.insert(createTestItem("Pinned1", timestamp = 100, isPinned = true))
            dao.insert(createTestItem("Pinned2", timestamp = 200, isPinned = true))
            dao.insert(createTestItem("Unpinned1", timestamp = 300, isPinned = false))
            dao.insert(createTestItem("Unpinned2", timestamp = 400, isPinned = false))

            dao.enforceMaxItems(1)

            assertEquals(1, dao.getUnpinnedCount())
            assertEquals(2, dao.getPinnedItems().size)
            val recentItems = dao.getRecentItems()
            assertEquals("Unpinned2", recentItems[0].content)
        }

    @Test
    fun `getRecentItems respects limit parameter`() =
        runTest {
            dao.insert(createTestItem("First", timestamp = 100, isPinned = false))
            dao.insert(createTestItem("Second", timestamp = 200, isPinned = false))
            dao.insert(createTestItem("Third", timestamp = 300, isPinned = false))
            dao.insert(createTestItem("Fourth", timestamp = 400, isPinned = false))
            dao.insert(createTestItem("Fifth", timestamp = 500, isPinned = false))

            val results = dao.getRecentItems(limit = 3)

            assertEquals(3, results.size)
            assertEquals("Fifth", results[0].content)
            assertEquals("Fourth", results[1].content)
            assertEquals("Third", results[2].content)
        }

    /**
     * Creates test clipboard item with defaults.
     */
    private fun createTestItem(
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        isPinned: Boolean = false,
    ): ClipboardItem =
        ClipboardItem(
            id = 0,
            content = content,
            timestamp = timestamp,
            isPinned = isPinned,
        )
}
