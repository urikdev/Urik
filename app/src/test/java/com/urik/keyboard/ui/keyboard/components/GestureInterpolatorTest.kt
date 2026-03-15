package com.urik.keyboard.ui.keyboard.components

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureInterpolatorTest {
    private lateinit var interpolator: GestureInterpolator
    private lateinit var ringBuffer: SwipePointRingBuffer

    @Before
    fun setup() {
        ringBuffer = SwipePointRingBuffer()
        interpolator = GestureInterpolator(ringBuffer)
    }

    @Test
    fun `first point passes through directly`() {
        interpolator.onRawPoint(100f, 200f, 1000L, 1f, 0f)
        assertEquals(1, ringBuffer.size)
    }

    @Test
    fun `second point passes through directly`() {
        interpolator.onRawPoint(100f, 200f, 1000L, 1f, 0f)
        interpolator.onRawPoint(110f, 200f, 1010L, 1f, 1f)
        assertEquals(2, ringBuffer.size)
    }

    @Test
    fun `third point passes through directly`() {
        interpolator.onRawPoint(100f, 200f, 1000L, 1f, 0f)
        interpolator.onRawPoint(110f, 200f, 1010L, 1f, 1f)
        interpolator.onRawPoint(120f, 200f, 1020L, 1f, 1f)
        assertEquals(3, ringBuffer.size)
    }

    @Test
    fun `fourth point triggers spline interpolation with intermediate points`() {
        interpolator.onRawPoint(0f, 0f, 0L, 1f, 0f)
        interpolator.onRawPoint(50f, 0f, 10L, 1f, 5f)
        interpolator.onRawPoint(100f, 50f, 20L, 1f, 5f)
        interpolator.onRawPoint(150f, 50f, 30L, 1f, 5f)

        assertTrue(
            "Spline should produce more points than raw input",
            ringBuffer.size > 4
        )
    }

    @Test
    fun `slow movement below 6px gap does not interpolate`() {
        interpolator.onRawPoint(100f, 200f, 1000L, 1f, 0f)
        interpolator.onRawPoint(102f, 200f, 1010L, 1f, 0.2f)
        interpolator.onRawPoint(104f, 200f, 1020L, 1f, 0.2f)
        interpolator.onRawPoint(106f, 200f, 1030L, 1f, 0.2f)

        assertEquals(
            "Close points should pass through without interpolation",
            4,
            ringBuffer.size
        )
    }

    @Test
    fun `fast movement with 60px plus gap caps at 10 interpolated points`() {
        interpolator.onRawPoint(0f, 0f, 0L, 1f, 0f)
        interpolator.onRawPoint(100f, 0f, 5L, 1f, 20f)
        interpolator.onRawPoint(200f, 100f, 10L, 1f, 20f)
        interpolator.onRawPoint(400f, 100f, 15L, 1f, 40f)

        assertTrue(
            "Large gap should interpolate but cap at max density",
            ringBuffer.size <= 4 + 10 + 10
        )
    }

    @Test
    fun `interpolated points lie between control points spatially`() {
        interpolator.onRawPoint(0f, 0f, 0L, 1f, 0f)
        interpolator.onRawPoint(100f, 0f, 10L, 1f, 10f)
        interpolator.onRawPoint(200f, 100f, 20L, 1f, 10f)
        interpolator.onRawPoint(300f, 100f, 30L, 1f, 10f)

        val snapshot = ringBuffer.snapshot()

        for (point in snapshot) {
            assertTrue("X should be in range", point.x >= -50f && point.x <= 350f)
            assertTrue("Y should be in range", point.y >= -50f && point.y <= 150f)
        }
    }

    @Test
    fun `timestamps are monotonically increasing`() {
        interpolator.onRawPoint(0f, 0f, 100L, 1f, 0f)
        interpolator.onRawPoint(50f, 10f, 200L, 1f, 0.5f)
        interpolator.onRawPoint(100f, 20f, 300L, 1f, 0.5f)
        interpolator.onRawPoint(200f, 30f, 400L, 1f, 1f)

        val snapshot = ringBuffer.snapshot()
        for (i in 1 until snapshot.size) {
            assertTrue(
                "Timestamps must be monotonically increasing",
                snapshot[i].timestamp >= snapshot[i - 1].timestamp
            )
        }
    }

    @Test
    fun `reset clears sliding window`() {
        interpolator.onRawPoint(0f, 0f, 0L, 1f, 0f)
        interpolator.onRawPoint(50f, 0f, 10L, 1f, 5f)
        interpolator.onRawPoint(100f, 0f, 20L, 1f, 5f)

        interpolator.reset()
        ringBuffer.reset()

        interpolator.onRawPoint(200f, 200f, 100L, 1f, 0f)
        val snapshot = ringBuffer.snapshot()

        assertEquals(1, snapshot.size)
        assertEquals(200f, snapshot[0].x, 0.001f)
    }

    @Test
    fun `sharp 90 degree turn does not produce spline overshoot`() {
        interpolator.onRawPoint(0f, 100f, 0L, 1f, 0f)
        interpolator.onRawPoint(50f, 100f, 10L, 1f, 5f)
        interpolator.onRawPoint(100f, 100f, 20L, 1f, 5f)
        interpolator.onRawPoint(100f, 50f, 30L, 1f, 5f)

        val snapshot = ringBuffer.snapshot()

        val marginPx = 80f
        for ((i, point) in snapshot.withIndex()) {
            assertTrue(
                "Point $i overshoots X: ${point.x}",
                point.x >= -marginPx && point.x <= 100f + marginPx
            )
            assertTrue(
                "Point $i overshoots Y: ${point.y}",
                point.y >= 50f - marginPx && point.y <= 100f + marginPx
            )
        }
    }

    @Test
    fun `U-shaped reversal does not create loop artifacts`() {
        interpolator.onRawPoint(0f, 0f, 0L, 1f, 0f)
        interpolator.onRawPoint(80f, 0f, 10L, 1f, 8f)
        interpolator.onRawPoint(80f, 80f, 20L, 1f, 8f)
        interpolator.onRawPoint(0f, 80f, 30L, 1f, 8f)

        val snapshot = ringBuffer.snapshot()

        var hasBacktrack = false
        for (i in 1 until snapshot.size) {
            val dx = snapshot[i].x - snapshot[i - 1].x
            val dy = snapshot[i].y - snapshot[i - 1].y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 100f) {
                hasBacktrack = true
            }
        }

        assertFalse(
            "U-turn should not produce large inter-point jumps (loop artifact)",
            hasBacktrack
        )
    }

    @Test
    fun `continuous multi-segment gesture produces monotonic X progression for straight swipe`() {
        interpolator.onRawPoint(0f, 100f, 0L, 1f, 0f)
        interpolator.onRawPoint(40f, 100f, 10L, 1f, 4f)
        interpolator.onRawPoint(80f, 100f, 20L, 1f, 4f)
        interpolator.onRawPoint(120f, 100f, 30L, 1f, 4f)
        interpolator.onRawPoint(160f, 100f, 40L, 1f, 4f)
        interpolator.onRawPoint(200f, 100f, 50L, 1f, 4f)

        val snapshot = ringBuffer.snapshot()

        for (i in 1 until snapshot.size) {
            assertTrue(
                "X should be monotonically increasing for a rightward swipe, " +
                    "but point $i: ${snapshot[i].x} < ${snapshot[i - 1].x}",
                snapshot[i].x >= snapshot[i - 1].x - 1f
            )
        }
    }

    @Test
    fun `high velocity segment does not exceed 10 interpolated points per segment`() {
        interpolator.onRawPoint(0f, 0f, 0L, 1f, 0f)
        interpolator.onRawPoint(20f, 0f, 5L, 1f, 4f)
        interpolator.onRawPoint(40f, 0f, 10L, 1f, 4f)
        interpolator.onRawPoint(300f, 0f, 15L, 1f, 52f)

        assertTrue(
            "Even with 260px gap, should not exceed 4 raw + 10 + 10 interpolated",
            ringBuffer.size <= 24
        )
    }
}
