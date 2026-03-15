package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResidualScorerShortWordTest {
    private lateinit var pathGeometryAnalyzer: PathGeometryAnalyzer
    private lateinit var scorer: ResidualScorer

    private val colemakKeyPositions =
        mapOf(
            'q' to PointF(30f, 50f),
            'w' to PointF(80f, 50f),
            'f' to PointF(130f, 50f),
            'p' to PointF(180f, 50f),
            'g' to PointF(230f, 50f),
            'j' to PointF(280f, 50f),
            'l' to PointF(330f, 50f),
            'u' to PointF(380f, 50f),
            'y' to PointF(430f, 50f),
            'a' to PointF(40f, 130f),
            'r' to PointF(90f, 130f),
            's' to PointF(140f, 130f),
            't' to PointF(190f, 130f),
            'd' to PointF(240f, 130f),
            'h' to PointF(290f, 130f),
            'n' to PointF(340f, 130f),
            'e' to PointF(390f, 130f),
            'i' to PointF(440f, 130f),
            'o' to PointF(490f, 130f),
            'z' to PointF(90f, 210f),
            'x' to PointF(140f, 210f),
            'c' to PointF(190f, 210f),
            'v' to PointF(240f, 210f),
            'b' to PointF(290f, 210f),
            'k' to PointF(340f, 210f),
            'm' to PointF(390f, 210f),
        )

    @Before
    fun setup() {
        pathGeometryAnalyzer = PathGeometryAnalyzer()
        scorer = ResidualScorer(pathGeometryAnalyzer)
    }

    @Test
    fun `short word aid scores higher than arrested on short Colemak path`() {
        val shortPath =
            generateLinearPath(
                colemakKeyPositions['a']!!,
                colemakKeyPositions['i']!!,
                colemakKeyPositions['d']!!,
                pointsPerSegment = 5,
            )

        val sigmaCache = buildSigmaCache(colemakKeyPositions)
        val neighborhoodCache = pathGeometryAnalyzer.computeKeyNeighborhoods(colemakKeyPositions)

        val signal =
            SwipeSignal.extract(
                shortPath,
                colemakKeyPositions,
                pathGeometryAnalyzer,
                sigmaCache,
                shortPath.size,
            )

        val aidEntry = makeEntry("aid", 50_000)
        val arrestedEntry = makeEntry("arrested", 80_000_000)

        val aidResult =
            scorer.scoreCandidate(
                aidEntry,
                signal,
                colemakKeyPositions,
                sigmaCache,
                neighborhoodCache,
                80_000_000L,
            )
        val arrestedResult =
            scorer.scoreCandidate(
                arrestedEntry,
                signal,
                colemakKeyPositions,
                sigmaCache,
                neighborhoodCache,
                80_000_000L,
            )

        assertTrue(
            "aid (${aidResult?.combinedScore}) should outscore arrested (${arrestedResult?.combinedScore}) on a short a→i→d path",
            (aidResult?.combinedScore ?: 0f) > (arrestedResult?.combinedScore ?: 0f),
        )
    }

    @Test
    fun `short word the scores higher than together on short path`() {
        val shortPath =
            generateLinearPath(
                colemakKeyPositions['t']!!,
                colemakKeyPositions['h']!!,
                colemakKeyPositions['e']!!,
                pointsPerSegment = 5,
            )

        val sigmaCache = buildSigmaCache(colemakKeyPositions)
        val neighborhoodCache = pathGeometryAnalyzer.computeKeyNeighborhoods(colemakKeyPositions)

        val signal =
            SwipeSignal.extract(
                shortPath,
                colemakKeyPositions,
                pathGeometryAnalyzer,
                sigmaCache,
                shortPath.size,
            )

        val theEntry = makeEntry("the", 500_000_000)
        val togetherEntry = makeEntry("together", 20_000_000)

        val theResult =
            scorer.scoreCandidate(
                theEntry,
                signal,
                colemakKeyPositions,
                sigmaCache,
                neighborhoodCache,
                500_000_000L,
            )
        val togetherResult =
            scorer.scoreCandidate(
                togetherEntry,
                signal,
                colemakKeyPositions,
                sigmaCache,
                neighborhoodCache,
                500_000_000L,
            )

        assertTrue(
            "the (${theResult?.combinedScore}) should outscore together (${togetherResult?.combinedScore}) on a short t→h→e path",
            (theResult?.combinedScore ?: 0f) > (togetherResult?.combinedScore ?: 0f),
        )
    }

    @Test
    fun `dogs still scores well on appropriate-length Colemak path`() {
        val path =
            generateLinearPath(
                colemakKeyPositions['d']!!,
                colemakKeyPositions['o']!!,
                colemakKeyPositions['g']!!,
                colemakKeyPositions['s']!!,
                pointsPerSegment = 8,
            )

        val sigmaCache = buildSigmaCache(colemakKeyPositions)
        val neighborhoodCache = pathGeometryAnalyzer.computeKeyNeighborhoods(colemakKeyPositions)

        val signal =
            SwipeSignal.extract(
                path,
                colemakKeyPositions,
                pathGeometryAnalyzer,
                sigmaCache,
                path.size,
            )

        val dogsEntry = makeEntry("dogs", 10_000_000)

        val dogsResult =
            scorer.scoreCandidate(
                dogsEntry,
                signal,
                colemakKeyPositions,
                sigmaCache,
                neighborhoodCache,
                10_000_000L,
            )

        assertTrue(
            "dogs should score reasonably (got ${dogsResult?.combinedScore})",
            (dogsResult?.combinedScore ?: 0f) > 0.20f,
        )
    }

    @Test
    fun `long word bonus does not regress for legitimate long gestures`() {
        val longPath =
            generateLinearPath(
                colemakKeyPositions['t']!!,
                colemakKeyPositions['o']!!,
                colemakKeyPositions['g']!!,
                colemakKeyPositions['e']!!,
                colemakKeyPositions['t']!!,
                colemakKeyPositions['h']!!,
                colemakKeyPositions['e']!!,
                colemakKeyPositions['r']!!,
                pointsPerSegment = 8,
            )

        val sigmaCache = buildSigmaCache(colemakKeyPositions)
        val neighborhoodCache = pathGeometryAnalyzer.computeKeyNeighborhoods(colemakKeyPositions)

        val signal =
            SwipeSignal.extract(
                longPath,
                colemakKeyPositions,
                pathGeometryAnalyzer,
                sigmaCache,
                longPath.size,
            )

        val togetherEntry = makeEntry("together", 20_000_000)

        val result =
            scorer.scoreCandidate(
                togetherEntry,
                signal,
                colemakKeyPositions,
                sigmaCache,
                neighborhoodCache,
                20_000_000L,
            )

        assertTrue(
            "together should score well on a full-length path (got ${result?.combinedScore})",
            (result?.combinedScore ?: 0f) > 0.20f,
        )
    }

    private fun generateLinearPath(
        vararg keyPoints: PointF,
        pointsPerSegment: Int = 5,
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
                        velocity = 1.0f,
                    ),
                )
                timestamp += 8L
            }
        }
        result.add(
            SwipeDetector.SwipePoint(
                x = keyPoints.last().x,
                y = keyPoints.last().y,
                timestamp = timestamp,
                velocity = 0.5f,
            ),
        )
        return result
    }

    private fun buildSigmaCache(positions: Map<Char, PointF>): Map<Char, PathGeometryAnalyzer.AdaptiveSigma> =
        positions.keys.associateWith { char ->
            pathGeometryAnalyzer.calculateAdaptiveSigma(char, positions)
        }

    private fun makeEntry(
        word: String,
        frequency: Long,
    ): SwipeDetector.DictionaryEntry =
        SwipeDetector.DictionaryEntry(
            word = word,
            frequencyScore = kotlin.math.ln(frequency.toFloat() + 1f) / 20f,
            rawFrequency = frequency,
            firstChar = word.first().lowercaseChar(),
            uniqueLetterCount = word.toSet().size,
            frequencyTier =
                SwipeDetector.FrequencyTier.fromRank(
                    if (frequency > 100_000_000) {
                        0
                    } else if (frequency > 10_000_000) {
                        500
                    } else if (frequency > 1_000_000) {
                        3000
                    } else {
                        10000
                    },
                ),
        )
}
