package com.urik.keyboard.ui.keyboard.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SwipePointRingBufferTest {
    private lateinit var buffer: SwipePointRingBuffer

    @Before
    fun setup() {
        buffer = SwipePointRingBuffer()
    }

    @Test
    fun `new buffer has zero size`() {
        assertEquals(0, buffer.size)
    }

    @Test
    fun `write and read single point`() {
        buffer.write(10f, 20f, 1000L, 0.8f, 1.5f)
        assertEquals(1, buffer.size)

        val snapshot = buffer.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(10f, snapshot[0].x, 0.001f)
        assertEquals(20f, snapshot[0].y, 0.001f)
        assertEquals(1000L, snapshot[0].timestamp)
        assertEquals(0.8f, snapshot[0].pressure, 0.001f)
        assertEquals(1.5f, snapshot[0].velocity, 0.001f)
    }

    @Test
    fun `write fills to capacity without crash`() {
        for (i in 0 until 512) {
            buffer.write(i.toFloat(), i.toFloat(), i.toLong(), 1f, 0f)
        }
        assertEquals(512, buffer.size)
    }

    @Test
    fun `write beyond capacity overwrites oldest`() {
        for (i in 0 until 513) {
            buffer.write(i.toFloat(), i.toFloat(), i.toLong(), 1f, 0f)
        }
        assertEquals(512, buffer.size)

        val snapshot = buffer.snapshot()
        assertEquals(1f, snapshot[0].x, 0.001f)
        assertEquals(512f, snapshot[511].x, 0.001f)
    }

    @Test
    fun `snapshot returns points in chronological order`() {
        for (i in 0 until 600) {
            buffer.write(i.toFloat(), 0f, i.toLong(), 1f, 0f)
        }

        val snapshot = buffer.snapshot()
        for (i in 1 until snapshot.size) {
            assertTrue(
                "Points must be chronological",
                snapshot[i].timestamp > snapshot[i - 1].timestamp
            )
        }
    }

    @Test
    fun `reset clears size and zeroes all slots`() {
        for (i in 0 until 100) {
            buffer.write(i.toFloat(), i.toFloat(), i.toLong(), 1f, 1f)
        }

        buffer.reset()
        assertEquals(0, buffer.size)

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun `reset prevents data contamination between gestures`() {
        buffer.write(99f, 99f, 99L, 0.5f, 3f)
        buffer.reset()

        buffer.write(1f, 2f, 100L, 1f, 0f)
        val snapshot = buffer.snapshot()

        assertEquals(1, snapshot.size)
        assertEquals(1f, snapshot[0].x, 0.001f)
        assertEquals(2f, snapshot[0].y, 0.001f)
    }

    @Test
    fun `snapshot returns independent copy each call`() {
        buffer.write(1f, 2f, 100L, 1f, 0f)
        val first = buffer.snapshot()
        buffer.write(3f, 4f, 200L, 1f, 0f)
        val second = buffer.snapshot()
        assertFalse("Snapshots must be independent to avoid ConcurrentModificationException", first === second)
        assertEquals("First snapshot unchanged after second write", 1, first.size)
        assertEquals("Second snapshot includes both writes", 2, second.size)
    }

    @Test
    fun `peek returns last written point`() {
        buffer.write(10f, 20f, 1000L, 0.8f, 1.5f)
        buffer.write(30f, 40f, 2000L, 0.9f, 2.0f)

        val last = buffer.peekLast()
        assertEquals(30f, last!!.x, 0.001f)
        assertEquals(40f, last.y, 0.001f)
    }

    @Test
    fun `peek on empty buffer returns null`() {
        assertEquals(null, buffer.peekLast())
    }

    @Test
    fun `snapshot after wrap-around preserves data integrity`() {
        for (i in 0 until 600) {
            buffer.write(
                i.toFloat(),
                (i * 2).toFloat(),
                (i * 10).toLong(),
                0.5f + i % 10 * 0.05f,
                i.toFloat() * 0.1f
            )
        }

        val snapshot = buffer.snapshot()
        assertEquals(512, snapshot.size)

        assertEquals(88f, snapshot[0].x, 0.001f)
        assertEquals(176f, snapshot[0].y, 0.001f)
        assertEquals(880L, snapshot[0].timestamp)

        assertEquals(599f, snapshot[511].x, 0.001f)
    }

    @Test
    fun `multiple wrap-arounds maintain correct ordering`() {
        for (i in 0 until 1500) {
            buffer.write(i.toFloat(), 0f, i.toLong(), 1f, 0f)
        }

        val snapshot = buffer.snapshot()
        assertEquals(512, snapshot.size)

        for (i in 1 until snapshot.size) {
            assertTrue(
                "Points must be chronological after multiple wraps",
                snapshot[i].x > snapshot[i - 1].x
            )
        }

        assertEquals(988f, snapshot[0].x, 0.001f)
        assertEquals(1499f, snapshot[511].x, 0.001f)
    }
}
