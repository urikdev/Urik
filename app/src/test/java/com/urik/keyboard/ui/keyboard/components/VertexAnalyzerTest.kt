package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VertexAnalyzerTest {
    private lateinit var vertexAnalyzer: VertexAnalyzer

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
        vertexAnalyzer = VertexAnalyzer()
    }

    @Test
    fun `calculateVertexLengthPenalty returns 1f for path below minimum point count`() {
        val analysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 8,
            minimumExpectedLength = 10,
            pathPointCount = 5
        )
        val result = vertexAnalyzer.calculateVertexLengthPenalty(3, analysis, 5)
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `calculateVertexLengthPenalty returns 1f when significant vertex count is below filter threshold`() {
        val analysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 0,
            minimumExpectedLength = 2,
            pathPointCount = 500
        )
        val result = vertexAnalyzer.calculateVertexLengthPenalty(3, analysis, 500)
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `calculateVertexLengthPenalty returns mismatch penalty when deficit is 2 or more`() {
        val analysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 8,
            minimumExpectedLength = 10,
            pathPointCount = 500
        )
        val result = vertexAnalyzer.calculateVertexLengthPenalty(3, analysis, 500)
        assertEquals(0.40f, result, 0.001f)
    }

    @Test
    fun `shouldPruneCandidate returns false when vertex count is below threshold`() {
        val analysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 0,
            minimumExpectedLength = 2,
            pathPointCount = 500
        )
        val result = vertexAnalyzer.shouldPruneCandidate(3, analysis, 500)
        assertFalse(result)
    }

    @Test
    fun `shouldPruneCandidate returns true when deficit exceeds threshold with sufficient vertices`() {
        val analysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 8,
            minimumExpectedLength = 10,
            pathPointCount = 500
        )
        val result = vertexAnalyzer.shouldPruneCandidate(3, analysis, 500)
        assertTrue(result)
    }

    @Test
    fun `getVertexCurvatureBoost returns 1f when analysis has no vertices or intentional inflections`() {
        val result = vertexAnalyzer.getVertexCurvatureBoost(
            key = 'a',
            closestPointIndex = 0,
            keyPosition = PointF(40f, 130f),
            analysis = emptyAnalysis
        )
        assertEquals(1.0f, result, 0.001f)
    }
}
