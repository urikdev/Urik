@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import com.urik.keyboard.KeyboardConstants.SwipeDetectionConstants
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Immutable pre-computed representation of a swipe path's geometric DNA.
 *
 * Built once per swipe before candidate evaluation. All spatial features
 * are memoized here so the per-candidate scoring loop reads, never computes.
 */
class SwipeSignal private constructor(
    val path: List<SwipeDetector.SwipePoint>,
    val pathLength: Float,
    val bounds: PathBounds,
    val charsInBounds: Set<Char>,
    val geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
    val averageVelocity: Float,
    val startAnchor: StartAnchor,
    val endAnchor: EndAnchor,
    val traversedKeys: Set<Char>,
    val passthroughKeys: Set<Char>,
    val intentionalInflectionCount: Int,
    val expectedWordLength: Int,
    val offRowKeys: Set<Char>,
    val spatialWeight: Float,
    val frequencyWeight: Float,
    val pointZeroDominant: Boolean,
) {
    data class PathBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    )

    data class StartAnchor(
        val centroid: PointF,
        val backprojected: PointF?,
        val candidateKeys: Set<Char>,
        val keyDistances: Map<Char, Float>,
        val closestKey: Char?,
        val pointZeroNearest: Char?,
        val pointZeroSecond: Char?,
        val isAmbiguous: Boolean,
        val isAnchorLocked: Boolean,
    )

    data class EndAnchor(
        val centroid: PointF,
        val keyDistances: Map<Char, Float>,
        val closestKey: Char?,
    )

    companion object {
        /**
         * Extracts all spatial features from an interpolated swipe path.
         *
         * Called once per swipe, before the candidate scoring loop.
         */
        fun extract(
            interpolatedPath: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
            pathGeometryAnalyzer: PathGeometryAnalyzer,
            cachedAdaptiveSigmas: Map<Char, PathGeometryAnalyzer.AdaptiveSigma>,
        ): SwipeSignal {
            val geometricAnalysis = pathGeometryAnalyzer.analyze(interpolatedPath, keyPositions)

            val (baselineSpatialWeight, baselineFreqWeight) =
                pathGeometryAnalyzer.calculateDynamicWeights(geometricAnalysis.pathConfidence)

            val bounds = calculatePathBounds(interpolatedPath)
            val charsInBounds = filterCharsByBounds(keyPositions, bounds)
            val pathLength = calculatePhysicalPathLength(interpolatedPath)

            val vp = geometricAnalysis.velocityProfile
            val vAvg = if (vp.isNotEmpty()) vp.sum() / vp.size else 0f
            val pointZeroDominant = vAvg < GeometricScoringConstants.POINT_ZERO_DOMINANCE_VELOCITY_THRESHOLD

            val offRowKeys = detectOffRowKeys(vAvg, keyPositions)

            val traversedKeys = HashSet<Char>(geometricAnalysis.traversedKeys.size)
            for (key in geometricAnalysis.traversedKeys.keys) {
                traversedKeys.add(key.lowercaseChar())
            }

            val passthroughKeys = HashSet<Char>(geometricAnalysis.traversedKeys.size)
            for ((key, traversal) in geometricAnalysis.traversedKeys) {
                val lc = key.lowercaseChar()
                if (traversal.velocityAtKey > GeometricScoringConstants.PASSTHROUGH_VELOCITY_THRESHOLD) {
                    val hasIntentionalInflection = geometricAnalysis.inflectionPoints.any { inflection ->
                        inflection.isIntentional && inflection.nearestKey?.lowercaseChar() == lc
                    }
                    if (!hasIntentionalInflection) {
                        passthroughKeys.add(lc)
                    }
                }
            }

            var intentionalInflectionCount = 0
            for (inflection in geometricAnalysis.inflectionPoints) {
                if (inflection.isIntentional) intentionalInflectionCount++
            }

            val expectedWordLength = calculateExpectedWordLength(
                intentionalInflectionCount, interpolatedPath.size,
            )

            val startAnchor = buildStartAnchor(
                interpolatedPath, keyPositions, pointZeroDominant,
            )
            val endAnchor = buildEndAnchor(interpolatedPath, keyPositions)

            return SwipeSignal(
                path = interpolatedPath,
                pathLength = pathLength,
                bounds = bounds,
                charsInBounds = charsInBounds,
                geometricAnalysis = geometricAnalysis,
                averageVelocity = vAvg,
                startAnchor = startAnchor,
                endAnchor = endAnchor,
                traversedKeys = traversedKeys,
                passthroughKeys = passthroughKeys,
                intentionalInflectionCount = intentionalInflectionCount,
                expectedWordLength = expectedWordLength,
                offRowKeys = offRowKeys,
                spatialWeight = baselineSpatialWeight,
                frequencyWeight = baselineFreqWeight,
                pointZeroDominant = pointZeroDominant,
            )
        }

        private fun calculatePathBounds(path: List<SwipeDetector.SwipePoint>): PathBounds {
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE
            for (point in path) {
                minX = min(minX, point.x)
                maxX = max(maxX, point.x)
                minY = min(minY, point.y)
                maxY = max(maxY, point.y)
            }
            return PathBounds(minX, maxX, minY, maxY)
        }

        private fun filterCharsByBounds(
            keyPositions: Map<Char, PointF>,
            bounds: PathBounds,
        ): Set<Char> {
            val margin = SwipeDetectionConstants.PATH_BOUNDS_MARGIN_PX
            return keyPositions.keys.filterTo(mutableSetOf()) { char ->
                val pos = keyPositions[char]!!
                pos.x >= bounds.minX - margin &&
                    pos.x <= bounds.maxX + margin &&
                    pos.y >= bounds.minY - margin &&
                    pos.y <= bounds.maxY + margin
            }
        }

        private fun calculatePhysicalPathLength(path: List<SwipeDetector.SwipePoint>): Float {
            var totalLength = 0f
            for (i in 1 until path.size) {
                val dx = path[i].x - path[i - 1].x
                val dy = path[i].y - path[i - 1].y
                totalLength += sqrt(dx * dx + dy * dy)
            }
            return totalLength
        }

        private fun detectOffRowKeys(
            vAvg: Float,
            keyPositions: Map<Char, PointF>,
        ): Set<Char> {
            if (vAvg <= GeometricScoringConstants.NORMAL_VELOCITY_THRESHOLD * 15f) {
                return emptySet()
            }
            var yMin = Float.MAX_VALUE
            var yMax = Float.MIN_VALUE
            for ((_, pos) in keyPositions) {
                if (pos.y < yMin) yMin = pos.y
                if (pos.y > yMax) yMax = pos.y
            }
            val yRange = yMax - yMin
            val topThreshold = yMin + yRange * 0.33f
            val bottomThreshold = yMax - yRange * 0.33f
            val result = HashSet<Char>(12)
            for ((char, pos) in keyPositions) {
                if (pos.y <= topThreshold || pos.y >= bottomThreshold) {
                    result.add(char)
                }
            }
            return result
        }

        private fun calculateExpectedWordLength(
            intentionalInflectionCount: Int,
            pathSize: Int,
        ): Int {
            val maxInflectionLength = when {
                pathSize < 35 -> 3
                pathSize < 50 -> 4
                pathSize < 70 -> 6
                pathSize < 100 -> 8
                pathSize < 150 -> 12
                pathSize < 200 -> 16
                else -> 20
            }
            val inflectionBasedLength = (intentionalInflectionCount + 2).coerceIn(2, maxInflectionLength)
            val pathPointBasedLength = (pathSize / 14).coerceIn(2, 20)
            return maxOf(inflectionBasedLength, pathPointBasedLength)
        }

        private fun buildStartAnchor(
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
            pointZeroDominant: Boolean,
        ): StartAnchor {
            val centroid = computeStartCentroid(path)
            val backprojected = computeBackprojectedStart(path)
            val candidateKeys = findCandidateStartKeys(centroid, path, keyPositions, backprojected)

            val pointZero = path[0]
            val keyDistances = candidateKeys.associateWith { char ->
                val keyPos = keyPositions[char] ?: return@associateWith Float.MAX_VALUE
                val dxC = keyPos.x - centroid.x
                val dyC = keyPos.y - centroid.y
                val distCentroid = sqrt(dxC * dxC + dyC * dyC)
                val dxP = keyPos.x - pointZero.x
                val dyP = keyPos.y - pointZero.y
                val distPointZero = sqrt(dxP * dxP + dyP * dyP)
                val weightedPointZero = if (pointZeroDominant) {
                    distPointZero * GeometricScoringConstants.POINT_ZERO_DISTANCE_WEIGHT
                } else {
                    distPointZero
                }
                var best = minOf(distCentroid, weightedPointZero)
                if (backprojected != null) {
                    val dxB = keyPos.x - backprojected.x
                    val dyB = keyPos.y - backprojected.y
                    best = minOf(best, sqrt(dxB * dxB + dyB * dyB))
                }
                best
            }
            val closestKey = keyDistances.minByOrNull { it.value }?.key

            var pointZeroNearest: Char? = null
            var pointZeroSecond: Char? = null
            var pointZeroMinDistSq = Float.MAX_VALUE
            var pointZeroSecondDistSq = Float.MAX_VALUE
            for ((char, pos) in keyPositions) {
                val dx = pos.x - pointZero.x
                val dy = pos.y - pointZero.y
                val distSq = dx * dx + dy * dy
                if (distSq < pointZeroMinDistSq) {
                    pointZeroSecondDistSq = pointZeroMinDistSq
                    pointZeroSecond = pointZeroNearest
                    pointZeroMinDistSq = distSq
                    pointZeroNearest = char
                } else if (distSq < pointZeroSecondDistSq) {
                    pointZeroSecondDistSq = distSq
                    pointZeroSecond = char
                }
            }

            val anchorThresholdSq = GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX *
                GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX
            val isAmbiguous = pointZeroSecond != null &&
                (pointZeroSecondDistSq - pointZeroMinDistSq) < anchorThresholdSq
            val isAnchorLocked = pointZeroMinDistSq < anchorThresholdSq

            return StartAnchor(
                centroid = centroid,
                backprojected = backprojected,
                candidateKeys = candidateKeys,
                keyDistances = keyDistances,
                closestKey = closestKey,
                pointZeroNearest = pointZeroNearest,
                pointZeroSecond = pointZeroSecond,
                isAmbiguous = isAmbiguous,
                isAnchorLocked = isAnchorLocked,
            )
        }

        private fun computeStartCentroid(path: List<SwipeDetector.SwipePoint>): PointF {
            if (path.isEmpty()) return PointF(0f, 0f)
            val startVelocity = if (path.size >= 2) {
                val p0 = path[0]
                val p1 = path[1]
                val dt = (p1.timestamp - p0.timestamp).coerceAtLeast(1L).toFloat()
                val dx = p1.x - p0.x
                val dy = p1.y - p0.y
                sqrt(dx * dx + dy * dy) / dt
            } else {
                0f
            }
            val sampleCount = if (startVelocity > SwipeDetectionConstants.HIGH_VELOCITY_START_THRESHOLD) {
                SwipeDetectionConstants.START_CENTROID_POINTS_FAST
            } else {
                SwipeDetectionConstants.START_CENTROID_POINTS_NORMAL
            }
            val n = minOf(sampleCount, path.size)
            var sumX = 0f
            var sumY = 0f
            for (i in 0 until n) {
                sumX += path[i].x
                sumY += path[i].y
            }
            return PointF(sumX / n, sumY / n)
        }

        private fun computeBackprojectedStart(path: List<SwipeDetector.SwipePoint>): PointF? {
            if (path.size < 3) return null
            val p0 = path[0]
            val p1 = path[1]
            val dt = (p1.timestamp - p0.timestamp).coerceAtLeast(1L).toFloat()
            val startVelocity = sqrt((p1.x - p0.x).let { it * it } + (p1.y - p0.y).let { it * it }) / dt
            if (startVelocity <= SwipeDetectionConstants.HIGH_VELOCITY_START_THRESHOLD) return null

            val sampleEnd = minOf(5, path.size)
            var vecX = 0f
            var vecY = 0f
            for (i in 1 until sampleEnd) {
                vecX += path[i].x - path[i - 1].x
                vecY += path[i].y - path[i - 1].y
            }
            val vecLen = sqrt(vecX * vecX + vecY * vecY)
            if (vecLen < 1f) return null

            val normX = vecX / vecLen
            val normY = vecY / vecLen
            val projectionDist = minOf(
                SwipeDetectionConstants.BACKPROJECTION_BASE_PX +
                    SwipeDetectionConstants.BACKPROJECTION_LOG_SCALE * ln(startVelocity),
                SwipeDetectionConstants.BACKPROJECTION_MAX_PX,
            )
            return PointF(p0.x - normX * projectionDist, p0.y - normY * projectionDist)
        }

        private fun findCandidateStartKeys(
            centroid: PointF,
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
            backprojectedStart: PointF?,
        ): Set<Char> {
            if (path.isEmpty()) return emptySet()

            val startVelocity = if (path.size >= 2) {
                val p0 = path[0]
                val p1 = path[1]
                val dt = (p1.timestamp - p0.timestamp).coerceAtLeast(1L).toFloat()
                val dx = p1.x - p0.x
                val dy = p1.y - p0.y
                sqrt(dx * dx + dy * dy) / dt
            } else {
                0f
            }

            val baseThresholdSq = SwipeDetectionConstants.CLOSE_KEY_DISTANCE_THRESHOLD_SQ
            val effectiveThresholdSq = when {
                startVelocity > SwipeDetectionConstants.EXTREME_VELOCITY_START_THRESHOLD -> {
                    val m = SwipeDetectionConstants.EXTREME_VELOCITY_RADIUS_MULTIPLIER
                    baseThresholdSq * m * m
                }
                startVelocity > SwipeDetectionConstants.HIGH_VELOCITY_START_THRESHOLD -> {
                    val m = SwipeDetectionConstants.VELOCITY_EXPANDED_RADIUS_MULTIPLIER
                    baseThresholdSq * m * m
                }
                else -> baseThresholdSq
            }

            val centroidKeys = keyPositions.entries
                .map { (char, pos) ->
                    val dx = pos.x - centroid.x
                    val dy = pos.y - centroid.y
                    char to (dx * dx + dy * dy)
                }.sortedBy { it.second }
                .take(8)
                .filter { it.second < effectiveThresholdSq }
                .map { it.first }
                .toSet()

            val firstPoint = path[0]
            val pointZeroKeys = keyPositions.entries
                .map { (char, pos) ->
                    val dx = pos.x - firstPoint.x
                    val dy = pos.y - firstPoint.y
                    char to (dx * dx + dy * dy)
                }.sortedBy { it.second }
                .take(SwipeDetectionConstants.POINT_ZERO_PROXIMITY_COUNT)
                .map { it.first }
                .toSet()

            val backprojKeys = if (backprojectedStart != null) {
                keyPositions.entries
                    .map { (char, pos) ->
                        val dx = pos.x - backprojectedStart.x
                        val dy = pos.y - backprojectedStart.y
                        char to (dx * dx + dy * dy)
                    }.sortedBy { it.second }
                    .take(SwipeDetectionConstants.POINT_ZERO_PROXIMITY_COUNT)
                    .map { it.first }
                    .toSet()
            } else {
                emptySet()
            }

            return centroidKeys + pointZeroKeys + backprojKeys
        }

        private fun buildEndAnchor(
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
        ): EndAnchor {
            val endN = minOf(SwipeDetectionConstants.END_CENTROID_POINTS, path.size)
            var endCentroidX = 0f
            var endCentroidY = 0f
            for (i in path.size - endN until path.size) {
                endCentroidX += path[i].x
                endCentroidY += path[i].y
            }
            endCentroidX /= endN
            endCentroidY /= endN

            val centroid = PointF(endCentroidX, endCentroidY)
            val keyDistances = keyPositions.mapValues { (_, keyPos) ->
                val dx = keyPos.x - endCentroidX
                val dy = keyPos.y - endCentroidY
                sqrt(dx * dx + dy * dy)
            }
            val closestKey = keyDistances.minByOrNull { it.value }?.key

            return EndAnchor(
                centroid = centroid,
                keyDistances = keyDistances,
                closestKey = closestKey,
            )
        }
    }
}
