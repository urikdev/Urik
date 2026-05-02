package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DwellDetectorTest {
    private lateinit var dwellDetector: DwellDetector

    private val emptyAnalysis = PathGeometryAnalyzer.GeometricAnalysis(
        inflectionPoints = emptyList(),
        segments = emptyList(),
        pathConfidence = 0.5f,
        velocityProfile = FloatArray(0),
        curvatureProfile = FloatArray(0),
        traversedKeys = emptyMap(),
        vertexAnalysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 0,
            minimumExpectedLength = 2,
            pathPointCount = 0
        ),
        dwellInterestPoints = emptyList()
    )

    @Before
    fun setup() {
        dwellDetector = DwellDetector()
    }

    @Test
    fun `detectRepeatedLetterSignal returns 0 when range too small`() {
        val path = listOf(
            SwipeDetector.SwipePoint(0f, 0f, 0L, velocity = 0.1f),
            SwipeDetector.SwipePoint(1f, 1f, 1L, velocity = 0.1f),
            SwipeDetector.SwipePoint(2f, 2f, 2L, velocity = 0.1f)
        )
        val result = dwellDetector.detectRepeatedLetterSignal(path, PointF(0f, 0f), 0, 1)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `detectRepeatedLetterSignal returns 0 when no points near key`() {
        val path = (0 until 5).map {
            SwipeDetector.SwipePoint(500f, 500f, it.toLong(), velocity = 0.1f)
        }
        val result = dwellDetector.detectRepeatedLetterSignal(path, PointF(0f, 0f), 0, path.size)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `detectRepeatedLetterSignal returns positive score when slow points near key`() {
        val keyPosition = PointF(40f, 130f)
        val path = (0 until 10).map {
            SwipeDetector.SwipePoint(40f, 130f, it.toLong(), velocity = 0.1f)
        }
        val result = dwellDetector.detectRepeatedLetterSignal(path, keyPosition, 0, path.size)
        assertTrue(result > 0f)
    }

    @Test
    fun `getDwellInterestBoost returns 1f when no dwell points`() {
        val result = dwellDetector.getDwellInterestBoost('a', 0, emptyAnalysis)
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `getDwellInterestBoost returns boost when key matches a dwell point`() {
        val analysis = emptyAnalysis.copy(
            dwellInterestPoints = listOf(
                PathGeometryAnalyzer.DwellInterestPoint(
                    pathIndexStart = 0,
                    pathIndexEnd = 5,
                    centroidX = 40f,
                    centroidY = 130f,
                    nearestKey = 'a',
                    distanceToKey = 5f,
                    confidence = 1.0f
                )
            )
        )
        val result = dwellDetector.getDwellInterestBoost('a', 2, analysis)
        assertTrue(result > 1.0f)
    }

    @Test
    fun `getVelocityDwellBoost returns 1f when velocity profile is empty`() {
        val result = dwellDetector.getVelocityDwellBoost(0, emptyAnalysis)
        assertEquals(1.0f, result, 0.001f)
    }
}
