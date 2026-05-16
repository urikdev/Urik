package com.urik.keyboard.ui.keyboard.components

import android.graphics.Canvas
import android.graphics.PointF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class KeyboardRendererTest {
    private lateinit var renderer: KeyboardRenderer
    private lateinit var mockCanvas: Canvas

    @Before
    fun setup() {
        renderer = KeyboardRenderer()
        mockCanvas = mock()
    }

    @Test
    fun `draw does nothing when fadeAlpha is zero`() {
        renderer.startSwipe(PointF(0f, 0f))
        renderer.updateSwipe(PointF().apply { x = 100f })
        renderer.draw(mockCanvas, fadeAlpha = 0f, pulseScale = 1f, isActive = false)
        verify(mockCanvas, never()).drawPath(any(), any())
        verify(mockCanvas, never()).drawCircle(any(), any(), any(), any())
    }

    @Test
    fun `draw calls drawPath when trail has more than one point`() {
        renderer.startSwipe(PointF(0f, 0f))
        renderer.updateSwipe(PointF().apply { x = 100f })
        renderer.draw(mockCanvas, fadeAlpha = 1f, pulseScale = 1f, isActive = true)
        verify(mockCanvas).drawPath(any(), any())
    }

    @Test
    fun `draw calls drawCircle for start dot when hasStartPoint`() {
        renderer.startSwipe(
            PointF().apply {
                x = 50f
                y = 50f
            }
        )
        renderer.draw(mockCanvas, fadeAlpha = 1f, pulseScale = 1f, isActive = true)
        verify(mockCanvas).drawCircle(any(), any(), any(), any())
    }

    @Test
    fun `draw calls drawCircle for current dot when isActive and hasCurrentPoint`() {
        renderer.startSwipe(PointF(0f, 0f))
        renderer.updateSwipe(PointF().apply { x = 100f })
        renderer.draw(mockCanvas, fadeAlpha = 1f, pulseScale = 1f, isActive = true)
        verify(mockCanvas).drawCircle(any(), any(), any(), any())
    }

    @Test
    fun `startSwipe returns true indicating caller should invalidate`() {
        val shouldInvalidate = renderer.startSwipe(PointF(0f, 0f))
        assertTrue("startSwipe should return true so caller calls invalidate()", shouldInvalidate)
    }

    @Test
    fun `updateSwipe returns true when point is far enough from last point`() {
        renderer.startSwipe(PointF(0f, 0f))
        val shouldInvalidate = renderer.updateSwipe(PointF().apply { x = 100f })
        assertTrue("updateSwipe should return true when path grows", shouldInvalidate)
    }

    @Test
    fun `updateSwipe returns false when point is too close to last point`() {
        renderer.startSwipe(PointF(0f, 0f))
        val shouldInvalidate = renderer.updateSwipe(PointF().apply { x = 1f })
        assertFalse("updateSwipe should return false when point is too close", shouldInvalidate)
    }

    @Test
    fun `reset clears path so draw does nothing when inactive`() {
        renderer.startSwipe(PointF(0f, 0f))
        renderer.updateSwipe(PointF().apply { x = 100f })
        renderer.reset()
        renderer.draw(mockCanvas, fadeAlpha = 1f, pulseScale = 1f, isActive = false)
        verify(mockCanvas, never()).drawPath(any(), any())
    }
}
