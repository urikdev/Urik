package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SigmaCalculatorTest {
    private lateinit var sigmaCalculator: SigmaCalculator

    private val qwertyKeyPositions = mapOf(
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
        sigmaCalculator = SigmaCalculator()
    }

    @Test
    fun `calculateAdaptiveSigma returns tight cluster sigma for dense key`() {
        val result = sigmaCalculator.calculateAdaptiveSigma('a', qwertyKeyPositions)

        assertTrue("Expected neighborCount >= 4 for 'a'", result.neighborCount >= 4)
        assertEquals(35f, result.sigma, 0.001f)
    }

    @Test
    fun `calculateAdaptiveSigma returns default sigma for unknown key`() {
        val result = sigmaCalculator.calculateAdaptiveSigma('X', qwertyKeyPositions)

        assertEquals(GeometricScoringConstants.DEFAULT_SIGMA, result.sigma, 0.001f)
        assertEquals(0, result.neighborCount)
    }

    @Test
    fun `calculateAdaptiveSigma returns edge key sigma for isolated key`() {
        val sparseMap = mapOf(
            'z' to PointF(0f, 0f),
            'q' to PointF(1000f, 1000f)
        )
        val result = sigmaCalculator.calculateAdaptiveSigma('q', sparseMap)

        assertEquals(55f, result.sigma, 0.001f)
    }

    @Test
    fun `computeKeyNeighborhoods returns entry for every key in input map`() {
        val result = sigmaCalculator.computeKeyNeighborhoods(qwertyKeyPositions)

        assertTrue(result.keys.containsAll(qwertyKeyPositions.keys))
    }

    @Test
    fun `computeKeyNeighborhoods includes close neighbors for middle key`() {
        val result = sigmaCalculator.computeKeyNeighborhoods(qwertyKeyPositions)

        val fNeighborhood = result['f']
        assertTrue(
            "'f' neighborhood should have neighbors",
            fNeighborhood != null && fNeighborhood.neighborChars.isNotEmpty()
        )
    }

    @Test
    fun `calculateAnchorSigmaModifier returns tightening for first letter`() {
        val result = sigmaCalculator.calculateAnchorSigmaModifier(0, 5, 0, emptyAnalysis)

        assertEquals(0.80f, result, 0.001f)
    }

    @Test
    fun `calculateAnchorSigmaModifier returns tightening for last letter`() {
        val result = sigmaCalculator.calculateAnchorSigmaModifier(4, 5, 10, emptyAnalysis)

        assertEquals(0.80f, result, 0.001f)
    }

    @Test
    fun `calculateAnchorSigmaModifier returns mid expansion for mid letter with no near inflection and short word`() {
        val result = sigmaCalculator.calculateAnchorSigmaModifier(2, 5, 5, emptyAnalysis, 50)

        assertEquals(1.20f, result, 0.001f)
    }
}
