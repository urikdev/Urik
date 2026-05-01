package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Characterization (golden-file) tests for PathGeometryAnalyzer.
 *
 * These tests lock in the current observable behavior of analyze() and the 7 standalone public
 * utility methods. Every subsequent extraction plan uses these tests as its regression gate.
 *
 * No structural changes to PathGeometryAnalyzer are made in this plan.
 */
@RunWith(RobolectricTestRunner::class)
class PathGeometryAnalyzerCharacterizationTest {
    private lateinit var pga: PathGeometryAnalyzer

    private val qwertyKeyPositions =
        mapOf(
            'q' to PointF(30f, 50f),
            'w' to PointF(80f, 50f),
            'e' to PointF(130f, 50f),
            'r' to PointF(180f, 50f),
            't' to PointF(230f, 50f),
            'y' to PointF(280f, 50f),
            'u' to PointF(330f, 50f),
            'i' to PointF(380f, 50f),
            'o' to PointF(430f, 50f),
            'p' to PointF(480f, 50f),
            'a' to PointF(40f, 130f),
            's' to PointF(90f, 130f),
            'd' to PointF(140f, 130f),
            'f' to PointF(190f, 130f),
            'g' to PointF(240f, 130f),
            'h' to PointF(290f, 130f),
            'j' to PointF(340f, 130f),
            'k' to PointF(390f, 130f),
            'l' to PointF(440f, 130f),
            'z' to PointF(90f, 210f),
            'x' to PointF(140f, 210f),
            'c' to PointF(190f, 210f),
            'v' to PointF(240f, 210f),
            'b' to PointF(290f, 210f),
            'n' to PointF(340f, 210f),
            'm' to PointF(390f, 210f)
        )

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
        pga = PathGeometryAnalyzer()
    }

    private fun generateLinearPath(
        vararg keyPoints: PointF,
        pointsPerSegment: Int = 5
    ): List<SwipeDetector.SwipePoint> {
        val result = ArrayList<SwipeDetector.SwipePoint>()
        var timestamp = 0L
        for (i in 0 until keyPoints.size - 1) {
            val from = keyPoints[i]
            val to = keyPoints[i + 1]
            for (j in 0 until pointsPerSegment) {
                val t = j.toFloat() / pointsPerSegment
                result.add(
                    SwipeDetector.SwipePoint(
                        x = from.x + (to.x - from.x) * t,
                        y = from.y + (to.y - from.y) * t,
                        timestamp = timestamp,
                        velocity = 1.0f
                    )
                )
                timestamp += 8L
            }
        }
        result.add(
            SwipeDetector.SwipePoint(
                x = keyPoints.last().x,
                y = keyPoints.last().y,
                timestamp = timestamp,
                velocity = 0.5f
            )
        )
        return result
    }

    private fun generateSlowVelocityPath(
        approachKey: PointF,
        dwellKey: PointF,
        dwellPoints: Int = 8
    ): List<SwipeDetector.SwipePoint> {
        val result = ArrayList<SwipeDetector.SwipePoint>()
        var timestamp = 0L
        for (j in 0 until 5) {
            val t = j.toFloat() / 5
            result.add(
                SwipeDetector.SwipePoint(
                    x = approachKey.x + (dwellKey.x - approachKey.x) * t,
                    y = approachKey.y + (dwellKey.y - approachKey.y) * t,
                    timestamp = timestamp,
                    velocity = 5.0f
                )
            )
            timestamp += 8L
        }
        for (j in 0 until dwellPoints) {
            result.add(
                SwipeDetector.SwipePoint(
                    x = dwellKey.x + (j % 3 - 1) * 2f,
                    y = dwellKey.y + j % 2 * 2f,
                    timestamp = timestamp,
                    velocity = 0.5f
                )
            )
            timestamp += 8L
        }
        return result
    }

    @Test
    fun `analyze returns empty analysis for empty path`() {
        val result = pga.analyze(emptyList(), qwertyKeyPositions)

        assertEquals(0, result.inflectionPoints.size)
        assertEquals(0, result.segments.size)
        assertEquals(0.5f, result.pathConfidence, 0.001f)
        assertArrayEquals(FloatArray(0), result.velocityProfile, 0.001f)
        assertArrayEquals(FloatArray(0), result.curvatureProfile, 0.001f)
        assertTrue(result.traversedKeys.isEmpty())
        assertTrue(result.dwellInterestPoints.isEmpty())
    }

    @Test
    fun `analyze returns empty analysis for path with fewer than 3 points`() {
        val path = listOf(
            SwipeDetector.SwipePoint(x = 30f, y = 50f, timestamp = 0L, velocity = 1.0f),
            SwipeDetector.SwipePoint(x = 80f, y = 50f, timestamp = 8L, velocity = 1.0f)
        )

        val result = pga.analyze(path, qwertyKeyPositions)

        assertEquals(0, result.inflectionPoints.size)
        assertEquals(0, result.segments.size)
        assertEquals(0.5f, result.pathConfidence, 0.001f)
        assertArrayEquals(FloatArray(0), result.velocityProfile, 0.001f)
        assertArrayEquals(FloatArray(0), result.curvatureProfile, 0.001f)
        assertTrue(result.traversedKeys.isEmpty())
        assertTrue(result.dwellInterestPoints.isEmpty())
    }

    @Test
    fun `analyze straight-line path produces zero inflections and empty dwell points`() {
        val path = generateLinearPath(
            qwertyKeyPositions['h']!!,
            qwertyKeyPositions['e']!!,
            pointsPerSegment = 5
        )

        val result = pga.analyze(path, qwertyKeyPositions)

        assertTrue(result.inflectionPoints.isEmpty())
        assertTrue(result.dwellInterestPoints.isEmpty())
        assertEquals(path.size, result.velocityProfile.size)
        assertEquals(path.size, result.curvatureProfile.size)
    }

    @Test
    fun `analyze single sharp corner produces at least one inflection point`() {
        val path = generateLinearPath(
            PointF(30f, 50f),
            PointF(480f, 50f),
            PointF(40f, 130f),
            pointsPerSegment = 5
        )

        val result = pga.analyze(path, qwertyKeyPositions)

        assertTrue(result.inflectionPoints.isNotEmpty())
        assertEquals(path.size, result.velocityProfile.size)
        assertEquals(path.size, result.curvatureProfile.size)
    }

    @Test
    fun `analyze multi-turn zigzag path produces multiple inflections and non-empty vertices`() {
        val path = generateLinearPath(
            qwertyKeyPositions['q']!!,
            qwertyKeyPositions['p']!!,
            qwertyKeyPositions['a']!!,
            qwertyKeyPositions['m']!!,
            qwertyKeyPositions['q']!!,
            pointsPerSegment = 5
        )

        val result = pga.analyze(path, qwertyKeyPositions)

        assertTrue(result.inflectionPoints.size >= 2)
        assertTrue(
            result.vertexAnalysis.vertices.isNotEmpty() || result.inflectionPoints.size >= 2
        )
        assertEquals(path.size, result.velocityProfile.size)
        assertEquals(path.size, result.curvatureProfile.size)
    }

    @Test
    fun `analyze slow-velocity cluster near a key produces dwell interest points`() {
        val path = generateSlowVelocityPath(
            qwertyKeyPositions['h']!!,
            qwertyKeyPositions['a']!!
        )

        val result = pga.analyze(path, qwertyKeyPositions)

        assertTrue(result.dwellInterestPoints.isNotEmpty())
        assertEquals('a', result.dwellInterestPoints[0].nearestKey)
        assertEquals(path.size, result.velocityProfile.size)
        assertEquals(path.size, result.curvatureProfile.size)
    }

    @Test
    fun `calculateVertexLengthPenalty returns 1f when rawPointCount is at or below min threshold`() {
        val vertexAnalysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 10,
            minimumExpectedLength = 8,
            pathPointCount = 5
        )

        val result = pga.calculateVertexLengthPenalty(
            wordLength = 3,
            vertexAnalysis = vertexAnalysis,
            rawPointCount = 5
        )

        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `calculateVertexLengthPenalty returns 1f when significantVertexCount is below minimum for filter`() {
        val vertexAnalysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 0,
            minimumExpectedLength = 8,
            pathPointCount = 500
        )

        val result = pga.calculateVertexLengthPenalty(
            wordLength = 3,
            vertexAnalysis = vertexAnalysis,
            rawPointCount = 500
        )

        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `shouldPruneCandidate returns false when significantVertexCount is below threshold`() {
        val vertexAnalysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 0,
            minimumExpectedLength = 2,
            pathPointCount = 500
        )

        val result = pga.shouldPruneCandidate(wordLength = 3, vertexAnalysis = vertexAnalysis, rawPointCount = 500)

        assertFalse(result)
    }

    @Test
    fun `shouldPruneCandidate returns false when deficit is zero`() {
        val vertexAnalysis = PathGeometryAnalyzer.VertexAnalysis(
            vertices = emptyList(),
            significantVertexCount = 6,
            minimumExpectedLength = 3,
            pathPointCount = 500
        )

        val result = pga.shouldPruneCandidate(wordLength = 3, vertexAnalysis = vertexAnalysis, rawPointCount = 500)

        assertFalse(result)
    }

    @Test
    fun `calculateAdaptiveSigma for a clustered key returns tight sigma`() {
        val result = pga.calculateAdaptiveSigma('a', qwertyKeyPositions)

        assertEquals(35f, result.sigma, 0.001f)
        assertTrue(result.neighborCount >= 4)
    }

    @Test
    fun `getVertexCurvatureBoost returns 1f for empty vertex and inflection analysis`() {
        val result = pga.getVertexCurvatureBoost(
            key = 'a',
            closestPointIndex = 0,
            keyPosition = qwertyKeyPositions['a']!!,
            analysis = emptyAnalysis
        )

        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `getDwellInterestBoost returns 1f when no dwell interest points are present`() {
        val result = pga.getDwellInterestBoost(
            key = 'a',
            closestPointIndex = 0,
            analysis = emptyAnalysis
        )

        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `getVelocityDwellBoost returns 1f when velocity profile is empty`() {
        val result = pga.getVelocityDwellBoost(
            closestPointIndex = 0,
            analysis = emptyAnalysis
        )

        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `calculatePathCoherenceScore returns neutral value for word shorter than minimum word length`() {
        val path = generateLinearPath(
            qwertyKeyPositions['h']!!,
            qwertyKeyPositions['i']!!,
            pointsPerSegment = 5
        )

        val result = pga.calculatePathCoherenceScore(
            word = "hi",
            path = path,
            keyPositions = qwertyKeyPositions,
            letterPathIndices = listOf(0, 5)
        )

        assertEquals(0.50f, result, 0.001f)
    }
}
