@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Geometric analysis engine for swipe path interpretation.
 *
 * Provides curvature analysis, inflection detection, key boundary intersection,
 * and path confidence metrics to enable precision gesture-to-word resolution.
 */
@Singleton
class PathGeometryAnalyzer
    @Inject
    constructor() {
        enum class SegmentType {
            STRAIGHT,
            CORNER,
            CURVE,
            DWELL,
        }

        data class InflectionPoint(
            val pathIndex: Int,
            val position: PointF,
            val angle: Float,
            val nearestKey: Char?,
            val distanceToKey: Float,
            val isIntentional: Boolean,
        )

        data class PathSegment(
            val startIndex: Int,
            val endIndex: Int,
            val type: SegmentType,
            val averageVelocity: Float,
            val curvature: Float,
            val traversedKeys: Set<Char>,
        )

        data class GeometricAnalysis(
            val inflectionPoints: List<InflectionPoint>,
            val segments: List<PathSegment>,
            val pathConfidence: Float,
            val velocityProfile: FloatArray,
            val curvatureProfile: FloatArray,
            val traversedKeys: Map<Char, KeyTraversal>,
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is GeometricAnalysis) return false
                return inflectionPoints == other.inflectionPoints &&
                    segments == other.segments &&
                    pathConfidence == other.pathConfidence &&
                    traversedKeys == other.traversedKeys
            }

            override fun hashCode(): Int {
                var result = inflectionPoints.hashCode()
                result = 31 * result + segments.hashCode()
                result = 31 * result + pathConfidence.hashCode()
                result = 31 * result + traversedKeys.hashCode()
                return result
            }
        }

        data class KeyTraversal(
            val intersectionPoint: PointF,
            val entryAngle: Float,
            val dwellTime: Float,
            val velocityAtKey: Float,
            val confidence: Float,
        )

        data class AdaptiveSigma(
            val sigma: Float,
            val neighborCount: Int,
        )

        private val reusableCurvatureArray = FloatArray(GeometricScoringConstants.MAX_PATH_POINTS)
        private val reusableVelocityArray = FloatArray(GeometricScoringConstants.MAX_PATH_POINTS)

        /**
         * Performs comprehensive geometric analysis on a swipe path.
         */
        fun analyze(
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
        ): GeometricAnalysis {
            if (path.size < 3) {
                return createEmptyAnalysis()
            }

            val curvatureProfile = calculateCurvatureProfile(path)
            val velocityProfile = calculateVelocityProfile(path)
            val inflectionPoints = detectInflectionPoints(path, curvatureProfile, keyPositions)
            val traversedKeys = detectKeyTraversals(path, velocityProfile, keyPositions)
            val segments = classifySegments(path, curvatureProfile, velocityProfile, traversedKeys)
            val pathConfidence = calculatePathConfidence(path, inflectionPoints, velocityProfile, curvatureProfile)

            return GeometricAnalysis(
                inflectionPoints = inflectionPoints,
                segments = segments,
                pathConfidence = pathConfidence,
                velocityProfile = velocityProfile,
                curvatureProfile = curvatureProfile,
                traversedKeys = traversedKeys,
            )
        }

        /**
         * Calculates adaptive Gaussian sigma based on key neighborhood density.
         */
        fun calculateAdaptiveSigma(
            key: Char,
            keyPositions: Map<Char, PointF>,
        ): AdaptiveSigma {
            val keyPos =
                keyPositions[key] ?: return AdaptiveSigma(
                    GeometricScoringConstants.DEFAULT_SIGMA,
                    0,
                )

            var neighborCount = 0
            keyPositions.forEach { (otherKey, otherPos) ->
                if (otherKey != key) {
                    val dx = keyPos.x - otherPos.x
                    val dy = keyPos.y - otherPos.y
                    val distSq = dx * dx + dy * dy
                    if (distSq < GeometricScoringConstants.NEIGHBOR_RADIUS_SQ) {
                        neighborCount++
                    }
                }
            }

            val sigma =
                when {
                    neighborCount >= GeometricScoringConstants.TIGHT_CLUSTER_THRESHOLD -> {
                        GeometricScoringConstants.TIGHT_CLUSTER_SIGMA
                    }

                    neighborCount >= GeometricScoringConstants.NORMAL_CLUSTER_THRESHOLD -> {
                        GeometricScoringConstants.NORMAL_SIGMA
                    }

                    else -> {
                        GeometricScoringConstants.EDGE_KEY_SIGMA
                    }
                }

            return AdaptiveSigma(sigma, neighborCount)
        }

        /**
         * Calculates velocity-based weight for letter scoring.
         */
        fun calculateVelocityWeight(velocity: Float): Float =
            when {
                velocity < GeometricScoringConstants.SLOW_VELOCITY_THRESHOLD -> {
                    GeometricScoringConstants.SLOW_VELOCITY_BOOST
                }

                velocity < GeometricScoringConstants.NORMAL_VELOCITY_THRESHOLD -> {
                    1.0f
                }

                else -> {
                    GeometricScoringConstants.FAST_VELOCITY_DISCOUNT
                }
            }

        /**
         * Determines if path geometrically crossed through a key region.
         */
        fun didPathTraverseKey(
            key: Char,
            analysis: GeometricAnalysis,
        ): Boolean = analysis.traversedKeys.containsKey(key)

        /**
         * Finds inflection boost for a letter based on nearby intentional corners.
         */
        fun getInflectionBoost(
            key: Char,
            analysis: GeometricAnalysis,
        ): Float {
            val nearbyInflection =
                analysis.inflectionPoints.find { inflection ->
                    inflection.nearestKey == key &&
                        inflection.isIntentional &&
                        inflection.distanceToKey < GeometricScoringConstants.INFLECTION_BOOST_RADIUS
                }

            return nearbyInflection?.let { inflection ->
                val angleBoost =
                    (inflection.angle / GeometricScoringConstants.MAX_EXPECTED_ANGLE)
                        .coerceIn(0.5f, 1.0f)
                GeometricScoringConstants.INFLECTION_BOOST_BASE +
                    (GeometricScoringConstants.INFLECTION_BOOST_MAX - GeometricScoringConstants.INFLECTION_BOOST_BASE) * angleBoost
            } ?: 1.0f
        }

        /**
         * Calculates dynamic spatial/frequency weights based on path confidence.
         */
        fun calculateDynamicWeights(pathConfidence: Float): Pair<Float, Float> =
            when {
                pathConfidence > GeometricScoringConstants.HIGH_CONFIDENCE_THRESHOLD -> {
                    GeometricScoringConstants.HIGH_CONFIDENCE_SPATIAL_WEIGHT to
                        GeometricScoringConstants.HIGH_CONFIDENCE_FREQ_WEIGHT
                }

                pathConfidence > GeometricScoringConstants.MEDIUM_CONFIDENCE_THRESHOLD -> {
                    GeometricScoringConstants.MEDIUM_CONFIDENCE_SPATIAL_WEIGHT to
                        GeometricScoringConstants.MEDIUM_CONFIDENCE_FREQ_WEIGHT
                }

                pathConfidence > GeometricScoringConstants.LOW_CONFIDENCE_THRESHOLD -> {
                    GeometricScoringConstants.LOW_CONFIDENCE_SPATIAL_WEIGHT to
                        GeometricScoringConstants.LOW_CONFIDENCE_FREQ_WEIGHT
                }

                else -> {
                    GeometricScoringConstants.VERY_LOW_CONFIDENCE_SPATIAL_WEIGHT to
                        GeometricScoringConstants.VERY_LOW_CONFIDENCE_FREQ_WEIGHT
                }
            }

        /**
         * Detects repeated letter signals (dwell or oscillation).
         */
        fun detectRepeatedLetterSignal(
            path: List<SwipeDetector.SwipePoint>,
            keyPosition: PointF,
            startIndex: Int,
            endIndex: Int,
        ): Float {
            if (endIndex - startIndex < 2) return 0f

            var dwellScore = 0f
            var oscillationScore = 0f
            var pointsNearKey = 0

            for (i in startIndex until minOf(endIndex, path.size)) {
                val point = path[i]
                val dx = point.x - keyPosition.x
                val dy = point.y - keyPosition.y
                val distSq = dx * dx + dy * dy

                if (distSq < GeometricScoringConstants.DWELL_DETECTION_RADIUS_SQ) {
                    pointsNearKey++
                    if (point.velocity < GeometricScoringConstants.DWELL_VELOCITY_THRESHOLD) {
                        dwellScore += 0.15f
                    }
                }
            }

            if (pointsNearKey >= 3) {
                oscillationScore = detectOscillation(path, startIndex, endIndex)
            }

            return (dwellScore + oscillationScore).coerceAtMost(GeometricScoringConstants.MAX_REPEATED_LETTER_BOOST)
        }

        /**
         * Determines if a word is in a tight geometric cluster.
         */
        fun isClusteredWord(
            word: String,
            keyPositions: Map<Char, PointF>,
        ): Boolean {
            if (word.length > GeometricScoringConstants.MAX_CLUSTERED_WORD_LENGTH) return false

            for (i in word.indices) {
                val pos1 = keyPositions[word[i].lowercaseChar()] ?: return false
                for (j in i + 1 until word.length) {
                    val pos2 = keyPositions[word[j].lowercaseChar()] ?: return false
                    val dx = pos1.x - pos2.x
                    val dy = pos1.y - pos2.y
                    val distSq = dx * dx + dy * dy
                    if (distSq > GeometricScoringConstants.CLUSTER_MAX_DISTANCE_SQ) {
                        return false
                    }
                }
            }
            return true
        }

        /**
         * Calculates path coverage - what percentage of path is explained by candidate word.
         */
        fun calculatePathCoverage(
            word: String,
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
            letterPathIndices: List<Int>,
        ): Float {
            if (path.isEmpty() || letterPathIndices.isEmpty()) return 0f

            val coveredIndices = mutableSetOf<Int>()
            val coverageRadius = GeometricScoringConstants.PATH_COVERAGE_RADIUS

            letterPathIndices.forEachIndexed { letterIdx, pathIdx ->
                if (pathIdx >= 0 && pathIdx < path.size && letterIdx < word.length) {
                    val keyPos = keyPositions[word[letterIdx].lowercaseChar()] ?: return@forEachIndexed

                    for (i in maxOf(0, pathIdx - 3)..minOf(path.size - 1, pathIdx + 3)) {
                        val point = path[i]
                        val dx = point.x - keyPos.x
                        val dy = point.y - keyPos.y
                        if (dx * dx + dy * dy < coverageRadius * coverageRadius) {
                            coveredIndices.add(i)
                        }
                    }
                }
            }

            return coveredIndices.size.toFloat() / path.size.toFloat()
        }

        private fun calculateCurvatureProfile(path: List<SwipeDetector.SwipePoint>): FloatArray {
            val size = minOf(path.size, reusableCurvatureArray.size)
            reusableCurvatureArray.fill(0f, 0, size)

            if (size < 3) return reusableCurvatureArray.copyOf(size)

            for (i in 1 until size - 1) {
                reusableCurvatureArray[i] = calculateLocalCurvature(path[i - 1], path[i], path[i + 1])
            }

            reusableCurvatureArray[0] = reusableCurvatureArray.getOrElse(1) { 0f }
            reusableCurvatureArray[size - 1] = reusableCurvatureArray.getOrElse(size - 2) { 0f }

            return reusableCurvatureArray.copyOf(size)
        }

        private fun calculateLocalCurvature(
            p0: SwipeDetector.SwipePoint,
            p1: SwipeDetector.SwipePoint,
            p2: SwipeDetector.SwipePoint,
        ): Float {
            val v1x = p1.x - p0.x
            val v1y = p1.y - p0.y
            val v2x = p2.x - p1.x
            val v2y = p2.y - p1.y

            val cross = v1x * v2y - v1y * v2x
            val dot = v1x * v2x + v1y * v2y

            return atan2(cross, dot)
        }

        private fun calculateVelocityProfile(path: List<SwipeDetector.SwipePoint>): FloatArray {
            val size = minOf(path.size, reusableVelocityArray.size)
            reusableVelocityArray.fill(0f, 0, size)

            for (i in 0 until size) {
                reusableVelocityArray[i] = path[i].velocity
            }

            return reusableVelocityArray.copyOf(size)
        }

        private fun detectInflectionPoints(
            path: List<SwipeDetector.SwipePoint>,
            curvatureProfile: FloatArray,
            keyPositions: Map<Char, PointF>,
        ): List<InflectionPoint> {
            val inflections = mutableListOf<InflectionPoint>()

            for (i in 1 until minOf(path.size - 1, curvatureProfile.size)) {
                val curvature = abs(curvatureProfile[i])

                if (curvature > GeometricScoringConstants.INFLECTION_ANGLE_THRESHOLD) {
                    val point = path[i]
                    val (nearestKey, distance) = findNearestKey(point, keyPositions)

                    val isIntentional =
                        curvature > GeometricScoringConstants.INTENTIONAL_CORNER_THRESHOLD &&
                            distance < GeometricScoringConstants.INTENTIONAL_CORNER_KEY_RADIUS

                    inflections.add(
                        InflectionPoint(
                            pathIndex = i,
                            position = PointF(point.x, point.y),
                            angle = curvature,
                            nearestKey = nearestKey,
                            distanceToKey = distance,
                            isIntentional = isIntentional,
                        ),
                    )
                }
            }

            return inflections
        }

        private fun findNearestKey(
            point: SwipeDetector.SwipePoint,
            keyPositions: Map<Char, PointF>,
        ): Pair<Char?, Float> {
            var nearestKey: Char? = null
            var minDist = Float.MAX_VALUE

            keyPositions.forEach { (key, pos) ->
                val dx = point.x - pos.x
                val dy = point.y - pos.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < minDist) {
                    minDist = dist
                    nearestKey = key
                }
            }

            return nearestKey to minDist
        }

        private fun detectKeyTraversals(
            path: List<SwipeDetector.SwipePoint>,
            velocityProfile: FloatArray,
            keyPositions: Map<Char, PointF>,
        ): Map<Char, KeyTraversal> {
            val traversals = mutableMapOf<Char, KeyTraversal>()

            keyPositions.forEach { (key, keyPos) ->
                val traversal = detectSingleKeyTraversal(path, velocityProfile, keyPos)
                if (traversal != null) {
                    traversals[key] = traversal
                }
            }

            return traversals
        }

        private fun detectSingleKeyTraversal(
            path: List<SwipeDetector.SwipePoint>,
            velocityProfile: FloatArray,
            keyPos: PointF,
        ): KeyTraversal? {
            val keyRadius = GeometricScoringConstants.KEY_TRAVERSAL_RADIUS
            val keyRadiusSq = keyRadius * keyRadius

            var bestIntersection: PointF? = null
            var bestConfidence = 0f
            var entryAngle = 0f
            var dwellTime = 0f
            var velocityAtKey = 0f
            var pointsInRadius = 0

            for (i in 0 until path.size - 1) {
                val p1 = path[i]
                val p2 = path[i + 1]

                val intersection =
                    lineCircleIntersection(
                        p1.x,
                        p1.y,
                        p2.x,
                        p2.y,
                        keyPos.x,
                        keyPos.y,
                        keyRadius,
                    )

                if (intersection != null) {
                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    val angle = atan2(dy, dx)

                    val velocity = if (i < velocityProfile.size) velocityProfile[i] else 0f
                    val confidence = calculateTraversalConfidence(intersection, keyPos, velocity)

                    if (confidence > bestConfidence) {
                        bestConfidence = confidence
                        bestIntersection = intersection
                        entryAngle = angle
                        velocityAtKey = velocity
                    }
                }

                val dx = p1.x - keyPos.x
                val dy = p1.y - keyPos.y
                if (dx * dx + dy * dy < keyRadiusSq) {
                    pointsInRadius++
                    if (p1.velocity < GeometricScoringConstants.DWELL_VELOCITY_THRESHOLD) {
                        dwellTime += 1f
                    }
                }
            }

            return bestIntersection?.let {
                KeyTraversal(
                    intersectionPoint = it,
                    entryAngle = entryAngle,
                    dwellTime = dwellTime,
                    velocityAtKey = velocityAtKey,
                    confidence = bestConfidence,
                )
            }
        }

        private fun lineCircleIntersection(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            cx: Float,
            cy: Float,
            radius: Float,
        ): PointF? {
            val dx = x2 - x1
            val dy = y2 - y1
            val fx = x1 - cx
            val fy = y1 - cy

            val a = dx * dx + dy * dy
            val b = 2f * (fx * dx + fy * dy)
            val c = fx * fx + fy * fy - radius * radius

            var discriminant = b * b - 4f * a * c

            if (discriminant < 0 || a == 0f) return null

            discriminant = sqrt(discriminant)
            val t1 = (-b - discriminant) / (2f * a)
            val t2 = (-b + discriminant) / (2f * a)

            val t =
                when {
                    t1 in 0f..1f -> t1
                    t2 in 0f..1f -> t2
                    else -> return null
                }

            return PointF(x1 + t * dx, y1 + t * dy)
        }

        private fun calculateTraversalConfidence(
            intersection: PointF,
            keyCenter: PointF,
            velocity: Float,
        ): Float {
            val dx = intersection.x - keyCenter.x
            val dy = intersection.y - keyCenter.y
            val distFromCenter = sqrt(dx * dx + dy * dy)

            val proximityScore =
                1f -
                    (distFromCenter / GeometricScoringConstants.KEY_TRAVERSAL_RADIUS)
                        .coerceIn(0f, 1f)

            val velocityScore =
                when {
                    velocity < GeometricScoringConstants.SLOW_VELOCITY_THRESHOLD -> 1.0f
                    velocity < GeometricScoringConstants.NORMAL_VELOCITY_THRESHOLD -> 0.85f
                    else -> 0.7f
                }

            return proximityScore * 0.7f + velocityScore * 0.3f
        }

        private fun classifySegments(
            path: List<SwipeDetector.SwipePoint>,
            curvatureProfile: FloatArray,
            velocityProfile: FloatArray,
            traversedKeys: Map<Char, KeyTraversal>,
        ): List<PathSegment> {
            if (path.size < 2) return emptyList()

            val segments = mutableListOf<PathSegment>()
            var segmentStart = 0
            var currentType = classifyPoint(0, curvatureProfile, velocityProfile)

            for (i in 1 until path.size) {
                val pointType = classifyPoint(i, curvatureProfile, velocityProfile)

                if (pointType != currentType || i == path.size - 1) {
                    val endIndex = if (i == path.size - 1) i else i - 1
                    val traversedInSegment = findKeysInSegment(path, segmentStart, endIndex, traversedKeys)

                    segments.add(
                        PathSegment(
                            startIndex = segmentStart,
                            endIndex = endIndex,
                            type = currentType,
                            averageVelocity = calculateAverageVelocity(velocityProfile, segmentStart, endIndex),
                            curvature = calculateAverageCurvature(curvatureProfile, segmentStart, endIndex),
                            traversedKeys = traversedInSegment,
                        ),
                    )

                    segmentStart = i
                    currentType = pointType
                }
            }

            return segments
        }

        private fun classifyPoint(
            index: Int,
            curvatureProfile: FloatArray,
            velocityProfile: FloatArray,
        ): SegmentType {
            val curvature = if (index < curvatureProfile.size) abs(curvatureProfile[index]) else 0f
            val velocity = if (index < velocityProfile.size) velocityProfile[index] else 0f

            return when {
                velocity < GeometricScoringConstants.DWELL_VELOCITY_THRESHOLD -> SegmentType.DWELL
                curvature > GeometricScoringConstants.CORNER_CURVATURE_THRESHOLD -> SegmentType.CORNER
                curvature > GeometricScoringConstants.CURVE_CURVATURE_THRESHOLD -> SegmentType.CURVE
                else -> SegmentType.STRAIGHT
            }
        }

        private fun findKeysInSegment(
            path: List<SwipeDetector.SwipePoint>,
            startIndex: Int,
            endIndex: Int,
            traversedKeys: Map<Char, KeyTraversal>,
        ): Set<Char> {
            val keysInSegment = mutableSetOf<Char>()

            traversedKeys.forEach { (key, traversal) ->
                for (i in startIndex..endIndex) {
                    if (i >= path.size) break
                    val point = path[i]
                    val dx = point.x - traversal.intersectionPoint.x
                    val dy = point.y - traversal.intersectionPoint.y
                    if (dx * dx + dy * dy < GeometricScoringConstants.SEGMENT_KEY_PROXIMITY_SQ) {
                        keysInSegment.add(key)
                        break
                    }
                }
            }

            return keysInSegment
        }

        private fun calculateAverageVelocity(
            velocityProfile: FloatArray,
            startIndex: Int,
            endIndex: Int,
        ): Float {
            if (startIndex >= endIndex || startIndex >= velocityProfile.size) return 0f

            var sum = 0f
            var count = 0
            for (i in startIndex..minOf(endIndex, velocityProfile.size - 1)) {
                sum += velocityProfile[i]
                count++
            }

            return if (count > 0) sum / count else 0f
        }

        private fun calculateAverageCurvature(
            curvatureProfile: FloatArray,
            startIndex: Int,
            endIndex: Int,
        ): Float {
            if (startIndex >= endIndex || startIndex >= curvatureProfile.size) return 0f

            var sum = 0f
            var count = 0
            for (i in startIndex..minOf(endIndex, curvatureProfile.size - 1)) {
                sum += abs(curvatureProfile[i])
                count++
            }

            return if (count > 0) sum / count else 0f
        }

        private fun calculatePathConfidence(
            path: List<SwipeDetector.SwipePoint>,
            inflectionPoints: List<InflectionPoint>,
            velocityProfile: FloatArray,
            curvatureProfile: FloatArray,
        ): Float {
            if (path.size < 3) return 0.5f

            val intentionalRatio =
                if (inflectionPoints.isNotEmpty()) {
                    inflectionPoints.count { it.isIntentional }.toFloat() / inflectionPoints.size
                } else {
                    0.5f
                }

            val velocityConsistency = calculateVelocityConsistency(velocityProfile, path.size)
            val pathSmoothness = calculatePathSmoothness(curvatureProfile, path.size)

            return (
                intentionalRatio * GeometricScoringConstants.CONFIDENCE_INFLECTION_WEIGHT +
                    velocityConsistency * GeometricScoringConstants.CONFIDENCE_VELOCITY_WEIGHT +
                    pathSmoothness * GeometricScoringConstants.CONFIDENCE_SMOOTHNESS_WEIGHT
            ).coerceIn(0f, 1f)
        }

        private fun calculateVelocityConsistency(
            velocityProfile: FloatArray,
            size: Int,
        ): Float {
            if (size < 2) return 1f

            val actualSize = minOf(size, velocityProfile.size)
            var sum = 0f
            var sumSq = 0f

            for (i in 0 until actualSize) {
                val v = velocityProfile[i]
                sum += v
                sumSq += v * v
            }

            val mean = sum / actualSize
            val variance = (sumSq / actualSize) - (mean * mean)
            val stdDev = sqrt(maxOf(0f, variance))
            val coeffOfVariation = if (mean > 0) stdDev / mean else 1f

            return (1f - coeffOfVariation.coerceIn(0f, 1f))
        }

        private fun calculatePathSmoothness(
            curvatureProfile: FloatArray,
            size: Int,
        ): Float {
            if (size < 3) return 1f

            val actualSize = minOf(size, curvatureProfile.size)
            var totalCurvature = 0f

            for (i in 0 until actualSize) {
                totalCurvature += abs(curvatureProfile[i])
            }

            val avgCurvature = totalCurvature / actualSize
            val normalizedCurvature = avgCurvature / GeometricScoringConstants.MAX_EXPECTED_CURVATURE

            return (1f - normalizedCurvature.coerceIn(0f, 1f))
        }

        private fun detectOscillation(
            path: List<SwipeDetector.SwipePoint>,
            startIndex: Int,
            endIndex: Int,
        ): Float {
            if (endIndex - startIndex < 3) return 0f

            var directionChanges = 0
            var lastDx = 0f

            for (i in startIndex + 1 until minOf(endIndex, path.size)) {
                val dx = path[i].x - path[i - 1].x

                if (lastDx != 0f && dx != 0f) {
                    if ((lastDx > 0) != (dx > 0)) {
                        directionChanges++
                    }
                }
                lastDx = dx
            }

            return if (directionChanges >= 2) {
                minOf(directionChanges * 0.1f, 0.3f)
            } else {
                0f
            }
        }

        private fun createEmptyAnalysis(): GeometricAnalysis =
            GeometricAnalysis(
                inflectionPoints = emptyList(),
                segments = emptyList(),
                pathConfidence = 0.5f,
                velocityProfile = FloatArray(0),
                curvatureProfile = FloatArray(0),
                traversedKeys = emptyMap(),
            )
    }
