@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

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
            val compensatedPosition: PointF? = null,
            val velocityAtInflection: Float = 0f,
        )

        data class DwellInterestPoint(
            val pathIndexStart: Int,
            val pathIndexEnd: Int,
            val centroidX: Float,
            val centroidY: Float,
            val nearestKey: Char?,
            val distanceToKey: Float,
            val confidence: Float,
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
            val vertexAnalysis: VertexAnalysis,
            val dwellInterestPoints: List<DwellInterestPoint> = emptyList(),
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is GeometricAnalysis) return false
                return inflectionPoints == other.inflectionPoints &&
                    segments == other.segments &&
                    pathConfidence == other.pathConfidence &&
                    traversedKeys == other.traversedKeys &&
                    vertexAnalysis == other.vertexAnalysis &&
                    dwellInterestPoints == other.dwellInterestPoints
            }

            override fun hashCode(): Int {
                var result = inflectionPoints.hashCode()
                result = 31 * result + segments.hashCode()
                result = 31 * result + pathConfidence.hashCode()
                result = 31 * result + traversedKeys.hashCode()
                result = 31 * result + vertexAnalysis.hashCode()
                result = 31 * result + dwellInterestPoints.hashCode()
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

        data class PathVertex(
            val pathIndex: Int,
            val position: PointF,
            val angleChange: Float,
            val velocityRatio: Float,
            val nearestKey: Char?,
            val isSignificant: Boolean,
        )

        data class VertexAnalysis(
            val vertices: List<PathVertex>,
            val significantVertexCount: Int,
            val minimumExpectedLength: Int,
            val pathPointCount: Int = 0,
        )

        data class AdaptiveSigma(
            val sigma: Float,
            val neighborCount: Int,
        )

        class KeyNeighborhood(
            val neighborChars: CharArray,
            val neighborDistances: FloatArray,
        )

        private val reusableCurvatureArray = FloatArray(MAX_PATH_POINTS)
        private val reusableVelocityArray = FloatArray(MAX_PATH_POINTS)
        private val reusableCoverageFlags = BooleanArray(MAX_PATH_POINTS)
        private val cachedIntersectionPoint = PointF()

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
            val inflectionPoints = detectInflectionPoints(path, curvatureProfile, velocityProfile, keyPositions)
            val traversedKeys = detectKeyTraversals(path, velocityProfile, keyPositions)
            val segments = classifySegments(path, curvatureProfile, velocityProfile, traversedKeys)
            val pathConfidence = calculatePathConfidence(path, inflectionPoints, velocityProfile, curvatureProfile)
            val vertexAnalysis = detectVertices(path, velocityProfile, keyPositions)
            val dwellInterestPoints = detectDwellClusters(path, velocityProfile, keyPositions)

            return GeometricAnalysis(
                inflectionPoints = inflectionPoints,
                segments = segments,
                pathConfidence = pathConfidence,
                velocityProfile = velocityProfile,
                curvatureProfile = curvatureProfile,
                traversedKeys = traversedKeys,
                vertexAnalysis = vertexAnalysis,
                dwellInterestPoints = dwellInterestPoints,
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
                    if (distSq < NEIGHBOR_RADIUS_SQ) {
                        neighborCount++
                    }
                }
            }

            val sigma =
                when {
                    neighborCount >= TIGHT_CLUSTER_THRESHOLD -> {
                        TIGHT_CLUSTER_SIGMA
                    }

                    neighborCount >= NORMAL_CLUSTER_THRESHOLD -> {
                        NORMAL_SIGMA
                    }

                    else -> {
                        EDGE_KEY_SIGMA
                    }
                }

            return AdaptiveSigma(sigma, neighborCount)
        }

        /**
         * Calculates velocity-based weight for letter scoring.
         */
        fun calculateVelocityWeight(velocity: Float): Float =
            when {
                velocity < SLOW_VELOCITY_THRESHOLD -> {
                    SLOW_VELOCITY_BOOST
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
                        inflection.distanceToKey < INFLECTION_BOOST_RADIUS
                }

            return nearbyInflection?.let { inflection ->
                val angleBoost =
                    (inflection.angle / MAX_EXPECTED_ANGLE)
                        .coerceIn(0.5f, 1.0f)
                INFLECTION_BOOST_BASE +
                    (INFLECTION_BOOST_MAX - INFLECTION_BOOST_BASE) * angleBoost
            } ?: 1.0f
        }

        /**
         * Calculates dynamic spatial/frequency weights based on path confidence.
         */
        fun calculateDynamicWeights(pathConfidence: Float): Pair<Float, Float> =
            when {
                pathConfidence > HIGH_CONFIDENCE_THRESHOLD -> {
                    HIGH_CONFIDENCE_SPATIAL_WEIGHT to
                        HIGH_CONFIDENCE_FREQ_WEIGHT
                }

                pathConfidence > MEDIUM_CONFIDENCE_THRESHOLD -> {
                    MEDIUM_CONFIDENCE_SPATIAL_WEIGHT to
                        MEDIUM_CONFIDENCE_FREQ_WEIGHT
                }

                pathConfidence > LOW_CONFIDENCE_THRESHOLD -> {
                    LOW_CONFIDENCE_SPATIAL_WEIGHT to
                        LOW_CONFIDENCE_FREQ_WEIGHT
                }

                else -> {
                    VERY_LOW_CONFIDENCE_SPATIAL_WEIGHT to
                        VERY_LOW_CONFIDENCE_FREQ_WEIGHT
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

                if (distSq < DWELL_DETECTION_RADIUS_SQ) {
                    pointsNearKey++
                    if (point.velocity < DWELL_VELOCITY_THRESHOLD) {
                        dwellScore += 0.15f
                    }
                }
            }

            if (pointsNearKey >= 3) {
                oscillationScore = detectOscillation(path, startIndex, endIndex)
            }

            return (dwellScore + oscillationScore).coerceAtMost(MAX_REPEATED_LETTER_BOOST)
        }

        /**
         * Determines if a word is in a tight geometric cluster.
         */
        fun isClusteredWord(
            word: String,
            keyPositions: Map<Char, PointF>,
        ): Boolean {
            if (word.length > MAX_CLUSTERED_WORD_LENGTH) return false

            for (i in word.indices) {
                val pos1 = keyPositions[word[i].lowercaseChar()] ?: return false
                for (j in i + 1 until word.length) {
                    val pos2 = keyPositions[word[j].lowercaseChar()] ?: return false
                    val dx = pos1.x - pos2.x
                    val dy = pos1.y - pos2.y
                    val distSq = dx * dx + dy * dy
                    if (distSq > CLUSTER_MAX_DISTANCE_SQ) {
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

            val size = path.size
            val flags = reusableCoverageFlags
            flags.fill(false, 0, minOf(size, flags.size))
            val coverageRadius = PATH_COVERAGE_RADIUS
            val radiusSq = coverageRadius * coverageRadius

            letterPathIndices.forEachIndexed { letterIdx, pathIdx ->
                if (pathIdx in 0..<size && letterIdx < word.length) {
                    val keyPos = keyPositions[word[letterIdx].lowercaseChar()] ?: return@forEachIndexed

                    for (i in maxOf(0, pathIdx - 3)..minOf(size - 1, pathIdx + 3)) {
                        val point = path[i]
                        val dx = point.x - keyPos.x
                        val dy = point.y - keyPos.y
                        if (dx * dx + dy * dy < radiusSq) {
                            flags[i] = true
                        }
                    }
                }
            }

            var coveredCount = 0
            for (i in 0 until size) {
                if (flags[i]) coveredCount++
            }

            return coveredCount.toFloat() / size.toFloat()
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
            velocityProfile: FloatArray,
            keyPositions: Map<Char, PointF>,
        ): List<InflectionPoint> {
            val inflections = mutableListOf<InflectionPoint>()

            for (i in 1 until minOf(path.size - 1, curvatureProfile.size)) {
                val curvature = abs(curvatureProfile[i])

                if (curvature > INFLECTION_ANGLE_THRESHOLD) {
                    val point = path[i]
                    val (nearestKey, distance) = findNearestKey(point, keyPositions)

                    val isIntentional =
                        curvature > INTENTIONAL_CORNER_THRESHOLD &&
                            distance < INTENTIONAL_CORNER_KEY_RADIUS

                    val velocity = if (i < velocityProfile.size) velocityProfile[i] else 0f

                    val compensated =
                        if (velocity > CORNER_COMPENSATION_VELOCITY_THRESHOLD &&
                            i > 0 && i < path.size - 1
                        ) {
                            computeCornerCompensation(path, i, velocity)
                        } else {
                            null
                        }

                    inflections.add(
                        InflectionPoint(
                            pathIndex = i,
                            position = PointF(point.x, point.y),
                            angle = curvature,
                            nearestKey = nearestKey,
                            distanceToKey = distance,
                            isIntentional = isIntentional,
                            compensatedPosition = compensated,
                            velocityAtInflection = velocity,
                        ),
                    )
                }
            }

            return inflections
        }

        private fun computeCornerCompensation(
            path: List<SwipeDetector.SwipePoint>,
            index: Int,
            velocity: Float,
        ): PointF {
            val prev = path[index - 1]
            val curr = path[index]
            val next = path[index + 1]

            val inX = curr.x - prev.x
            val inY = curr.y - prev.y
            val outX = next.x - curr.x
            val outY = next.y - curr.y

            val inLen = sqrt(inX * inX + inY * inY)
            val outLen = sqrt(outX * outX + outY * outY)

            if (inLen < 0.001f || outLen < 0.001f) return PointF(curr.x, curr.y)

            val bisectX = inX / inLen + outX / outLen
            val bisectY = inY / inLen + outY / outLen
            val bisectLen = sqrt(bisectX * bisectX + bisectY * bisectY)

            if (bisectLen < 0.001f) return PointF(curr.x, curr.y)

            val offset = (velocity * CORNER_COMPENSATION_VELOCITY_SCALE)
                .coerceAtMost(CORNER_COMPENSATION_MAX_OFFSET_PX)

            return PointF(
                curr.x - (bisectX / bisectLen) * offset,
                curr.y - (bisectY / bisectLen) * offset,
            )
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

            var bestIntersectionX = 0f
            var bestIntersectionY = 0f
            var hasBestIntersection = false
            var bestConfidence = 0f
            var entryAngle = 0f
            var dwellTime = 0f
            var velocityAtKey = 0f
            var pointsInRadius = 0

            for (i in 0 until path.size - 1) {
                val p1 = path[i]
                val p2 = path[i + 1]

                if (lineCircleIntersection(
                        p1.x,
                        p1.y,
                        p2.x,
                        p2.y,
                        keyPos.x,
                        keyPos.y,
                        keyRadius,
                        cachedIntersectionPoint,
                    )
                ) {
                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    val angle = atan2(dy, dx)

                    val velocity = if (i < velocityProfile.size) velocityProfile[i] else 0f
                    val confidence =
                        calculateTraversalConfidencePrimitive(
                            cachedIntersectionPoint.x,
                            cachedIntersectionPoint.y,
                            keyPos.x,
                            keyPos.y,
                            velocity,
                        )

                    if (confidence > bestConfidence) {
                        bestConfidence = confidence
                        bestIntersectionX = cachedIntersectionPoint.x
                        bestIntersectionY = cachedIntersectionPoint.y
                        hasBestIntersection = true
                        entryAngle = angle
                        velocityAtKey = velocity
                    }
                }

                val dx = p1.x - keyPos.x
                val dy = p1.y - keyPos.y
                if (dx * dx + dy * dy < keyRadiusSq) {
                    pointsInRadius++
                    if (p1.velocity < DWELL_VELOCITY_THRESHOLD) {
                        dwellTime += 1f
                    }
                }
            }

            return if (hasBestIntersection) {
                KeyTraversal(
                    intersectionPoint = PointF(bestIntersectionX, bestIntersectionY),
                    entryAngle = entryAngle,
                    dwellTime = dwellTime,
                    velocityAtKey = velocityAtKey,
                    confidence = bestConfidence,
                )
            } else {
                null
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
            outPoint: PointF,
        ): Boolean {
            val dx = x2 - x1
            val dy = y2 - y1
            val fx = x1 - cx
            val fy = y1 - cy

            val a = dx * dx + dy * dy
            val b = 2f * (fx * dx + fy * dy)
            val c = fx * fx + fy * fy - radius * radius

            var discriminant = b * b - 4f * a * c

            if (discriminant < 0 || a == 0f) return false

            discriminant = sqrt(discriminant)
            val t1 = (-b - discriminant) / (2f * a)
            val t2 = (-b + discriminant) / (2f * a)

            val t =
                when {
                    t1 in 0f..1f -> t1
                    t2 in 0f..1f -> t2
                    else -> return false
                }

            outPoint.set(x1 + t * dx, y1 + t * dy)
            return true
        }

        private fun calculateTraversalConfidencePrimitive(
            intersectionX: Float,
            intersectionY: Float,
            keyCenterX: Float,
            keyCenterY: Float,
            velocity: Float,
        ): Float {
            val dx = intersectionX - keyCenterX
            val dy = intersectionY - keyCenterY
            val distFromCenter = sqrt(dx * dx + dy * dy)

            val proximityScore =
                1f -
                    (distFromCenter / GeometricScoringConstants.KEY_TRAVERSAL_RADIUS)
                        .coerceIn(0f, 1f)

            val velocityScore =
                when {
                    velocity < SLOW_VELOCITY_THRESHOLD -> 1.0f
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
                velocity < DWELL_VELOCITY_THRESHOLD -> SegmentType.DWELL
                curvature > CORNER_CURVATURE_THRESHOLD -> SegmentType.CORNER
                curvature > CURVE_CURVATURE_THRESHOLD -> SegmentType.CURVE
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
                    if (dx * dx + dy * dy < SEGMENT_KEY_PROXIMITY_SQ) {
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
                intentionalRatio * CONFIDENCE_INFLECTION_WEIGHT +
                    velocityConsistency * CONFIDENCE_VELOCITY_WEIGHT +
                    pathSmoothness * CONFIDENCE_SMOOTHNESS_WEIGHT
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
            val normalizedCurvature = avgCurvature / MAX_EXPECTED_CURVATURE

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

        private fun detectVertices(
            path: List<SwipeDetector.SwipePoint>,
            velocityProfile: FloatArray,
            keyPositions: Map<Char, PointF>,
        ): VertexAnalysis {
            if (path.size < 5) {
                return VertexAnalysis(
                    vertices = emptyList(),
                    significantVertexCount = 0,
                    minimumExpectedLength = 2,
                    pathPointCount = path.size,
                )
            }

            val simplifiedIndices = douglasPeuckerSimplify(path)
            val vertices = mutableListOf<PathVertex>()

            simplifiedIndices
                .asSequence()
                .windowed(3, 1)
                .forEach { window ->
                    val prevIdx = window[0]
                    val currIdx = window[1]
                    val nextIdx = window[2]

                    val prev = path[prevIdx]
                    val curr = path[currIdx]
                    val next = path[nextIdx]

                    val v1x = curr.x - prev.x
                    val v1y = curr.y - prev.y
                    val v2x = next.x - curr.x
                    val v2y = next.y - curr.y

                    val len1 = sqrt(v1x * v1x + v1y * v1y)
                    val len2 = sqrt(v2x * v2x + v2y * v2y)

                    if (len1 < GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX ||
                        len2 < GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX
                    ) {
                        return@forEach
                    }

                    val dotProduct = (v1x * v2x + v1y * v2y) / (len1 * len2)
                    var angleChange = kotlin.math.acos(dotProduct.coerceIn(-1f, 1f))

                    val (nearestKey, distToNearestKey) = findNearestKey(curr, keyPositions)

                    var vertexPosition = PointF(curr.x, curr.y)
                    if (nearestKey != null &&
                        distToNearestKey < GeometricScoringConstants.KEY_TRAVERSAL_RADIUS &&
                        distToNearestKey > 5f
                    ) {
                        val keyPos = keyPositions[nearestKey]!!
                        val kv1x = keyPos.x - prev.x
                        val kv1y = keyPos.y - prev.y
                        val kv2x = next.x - keyPos.x
                        val kv2y = next.y - keyPos.y
                        val kLen1 = sqrt(kv1x * kv1x + kv1y * kv1y)
                        val kLen2 = sqrt(kv2x * kv2x + kv2y * kv2y)
                        if (kLen1 >= GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX &&
                            kLen2 >= GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX
                        ) {
                            val kDot = (kv1x * kv2x + kv1y * kv2y) / (kLen1 * kLen2)
                            val keyAngle = kotlin.math.acos(kDot.coerceIn(-1f, 1f))
                            if (keyAngle > angleChange) {
                                angleChange = keyAngle
                                vertexPosition = PointF(keyPos.x, keyPos.y)
                            }
                        }
                    }

                    val velocityAtVertex = if (currIdx < velocityProfile.size) velocityProfile[currIdx] else 0f
                    val avgSurroundingVelocity = calculateSurroundingVelocity(velocityProfile, currIdx)
                    val velocityRatio =
                        if (avgSurroundingVelocity > 0.01f) {
                            velocityAtVertex / avgSurroundingVelocity
                        } else {
                            1f
                        }

                    val isAngleVertex = angleChange > VERTEX_ANGLE_THRESHOLD_RAD
                    val isVelocityVertex = velocityRatio < VERTEX_VELOCITY_DROP_RATIO

                    val isDenseArea = isDenseKeyboardArea(curr, keyPositions)
                    val adjustedAngleThreshold =
                        if (isDenseArea) {
                            VERTEX_ANGLE_THRESHOLD_RAD *
                                VERTEX_DENSE_AREA_SENSITIVITY_BOOST
                        } else {
                            VERTEX_ANGLE_THRESHOLD_RAD
                        }
                    val isDenseAreaVertex = isDenseArea && angleChange > adjustedAngleThreshold

                    val isSignificant = isAngleVertex || isVelocityVertex || isDenseAreaVertex

                    if (isSignificant || angleChange > VERTEX_ANGLE_THRESHOLD_RAD * 0.7f) {
                        vertices.add(
                            PathVertex(
                                pathIndex = currIdx,
                                position = vertexPosition,
                                angleChange = angleChange,
                                velocityRatio = velocityRatio,
                                nearestKey = nearestKey,
                                isSignificant = isSignificant,
                            ),
                        )
                    }
                }

            addFlyByVertices(path, simplifiedIndices, keyPositions, vertices)

            val significantCount = vertices.count { it.isSignificant }
            val minimumExpected =
                maxOf(
                    2,
                    significantCount - VERTEX_TOLERANCE_CONSTANT,
                )

            return VertexAnalysis(
                vertices = vertices,
                significantVertexCount = significantCount,
                minimumExpectedLength = minimumExpected,
                pathPointCount = path.size,
            )
        }

        private fun douglasPeuckerSimplify(path: List<SwipeDetector.SwipePoint>): List<Int> {
            if (path.size <= 2) return path.indices.toList()

            val epsilon = VERTEX_PATH_SIMPLIFICATION_EPSILON
            val result = mutableListOf<Int>()
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to path.size - 1)

            val keep = BooleanArray(path.size)
            keep[0] = true
            keep[path.size - 1] = true

            while (stack.isNotEmpty()) {
                val (startIdx, endIdx) = stack.removeLast()

                if (endIdx - startIdx < 2) continue

                var maxDist = 0f
                var maxIdx = startIdx

                val startPoint = path[startIdx]
                val endPoint = path[endIdx]
                val lineDx = endPoint.x - startPoint.x
                val lineDy = endPoint.y - startPoint.y
                val lineLen = sqrt(lineDx * lineDx + lineDy * lineDy)

                if (lineLen < 0.001f) continue

                for (i in (startIdx + 1) until endIdx) {
                    val point = path[i]
                    val dist =
                        abs(
                            lineDy * point.x - lineDx * point.y +
                                endPoint.x * startPoint.y - endPoint.y * startPoint.x,
                        ) / lineLen

                    if (dist > maxDist) {
                        maxDist = dist
                        maxIdx = i
                    }
                }

                if (maxDist > epsilon) {
                    keep[maxIdx] = true
                    stack.addLast(startIdx to maxIdx)
                    stack.addLast(maxIdx to endIdx)
                }
            }

            for (i in path.indices) {
                if (keep[i]) result.add(i)
            }

            return result
        }

        private fun calculateSurroundingVelocity(
            velocityProfile: FloatArray,
            centerIndex: Int,
        ): Float {
            val windowSize = 3
            var sum = 0f
            var count = 0

            for (i in (centerIndex - windowSize)..(centerIndex + windowSize)) {
                if (i >= 0 && i < velocityProfile.size && i != centerIndex) {
                    sum += velocityProfile[i]
                    count++
                }
            }

            return if (count > 0) sum / count else 0f
        }

        private fun isDenseKeyboardArea(
            point: SwipeDetector.SwipePoint,
            keyPositions: Map<Char, PointF>,
        ): Boolean {
            val thresholdSq =
                VERTEX_DENSE_AREA_RADIUS_PX *
                    VERTEX_DENSE_AREA_RADIUS_PX
            var nearbyKeys = 0

            keyPositions.values.forEach { keyPos ->
                val dx = point.x - keyPos.x
                val dy = point.y - keyPos.y
                if (dx * dx + dy * dy < thresholdSq) {
                    nearbyKeys++
                }
            }

            return nearbyKeys >= 4
        }

        private fun addFlyByVertices(
            path: List<SwipeDetector.SwipePoint>,
            simplifiedIndices: List<Int>,
            keyPositions: Map<Char, PointF>,
            vertices: MutableList<PathVertex>,
        ) {
            if (simplifiedIndices.size < 2) return

            val gapThreshold = VERTEX_FLY_BY_GAP_THRESHOLD_PX
            val angleThreshold = VERTEX_ANGLE_THRESHOLD_RAD * 0.7f
            val keyRadius = GeometricScoringConstants.KEY_TRAVERSAL_RADIUS

            for (i in 0 until simplifiedIndices.size - 1) {
                val idx1 = simplifiedIndices[i]
                val idx2 = simplifiedIndices[i + 1]
                val p1 = path[idx1]
                val p2 = path[idx2]

                val segDx = p2.x - p1.x
                val segDy = p2.y - p1.y
                val segLen = sqrt(segDx * segDx + segDy * segDy)
                if (segLen < gapThreshold) continue

                val prevIdx = if (i > 0) simplifiedIndices[i - 1] else idx1
                val nextIdx = if (i + 2 < simplifiedIndices.size) simplifiedIndices[i + 2] else idx2
                val prev = path[prevIdx]
                val next = path[nextIdx]

                keyPositions.forEach { (key, keyPos) ->
                    val existingVertex = vertices.any { it.nearestKey == key }
                    if (existingVertex) return@forEach

                    val px = keyPos.x - p1.x
                    val py = keyPos.y - p1.y
                    val t = ((px * segDx + py * segDy) / (segLen * segLen)).coerceIn(0.1f, 0.9f)
                    val projX = p1.x + t * segDx
                    val projY = p1.y + t * segDy
                    val distToSeg = sqrt(
                        (keyPos.x - projX) * (keyPos.x - projX) +
                            (keyPos.y - projY) * (keyPos.y - projY),
                    )
                    if (distToSeg > keyRadius) return@forEach

                    val inDx = keyPos.x - prev.x
                    val inDy = keyPos.y - prev.y
                    val outDx = next.x - keyPos.x
                    val outDy = next.y - keyPos.y
                    val inLen = sqrt(inDx * inDx + inDy * inDy)
                    val outLen = sqrt(outDx * outDx + outDy * outDy)
                    if (inLen < GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX ||
                        outLen < GeometricScoringConstants.VERTEX_MIN_SEGMENT_LENGTH_PX
                    ) {
                        return@forEach
                    }

                    val dot = (inDx * outDx + inDy * outDy) / (inLen * outLen)
                    val angle = kotlin.math.acos(dot.coerceIn(-1f, 1f))
                    if (angle < angleThreshold) return@forEach

                    val midPathIdx = (idx1 + idx2) / 2
                    vertices.add(
                        PathVertex(
                            pathIndex = midPathIdx,
                            position = PointF(keyPos.x, keyPos.y),
                            angleChange = angle,
                            velocityRatio = 1.0f,
                            nearestKey = key,
                            isSignificant = angle > VERTEX_ANGLE_THRESHOLD_RAD,
                        ),
                    )
                }
            }
        }

        fun calculateVertexLengthPenalty(
            wordLength: Int,
            vertexAnalysis: VertexAnalysis,
        ): Float {
            if (vertexAnalysis.pathPointCount <= GeometricScoringConstants.VERTEX_FILTER_MIN_PATH_POINTS) {
                return 1.0f
            }
            if (vertexAnalysis.significantVertexCount < VERTEX_MINIMUM_FOR_FILTER) {
                return 1.0f
            }

            val minimumExpected = vertexAnalysis.minimumExpectedLength
            val deficit = minimumExpected - wordLength

            if (wordLength > VERTEX_LONG_WORD_PRUNE_THRESHOLD && deficit >= 5) {
                return VERTEX_LONG_WORD_DEFICIT_PENALTY
            }

            return when {
                deficit <= 0 -> 1.0f
                deficit == 1 -> VERTEX_MILD_MISMATCH_PENALTY
                else -> VERTEX_LENGTH_MISMATCH_PENALTY
            }
        }

        fun shouldPruneCandidate(
            wordLength: Int,
            vertexAnalysis: VertexAnalysis,
        ): Boolean {
            if (vertexAnalysis.pathPointCount <= GeometricScoringConstants.VERTEX_FILTER_MIN_PATH_POINTS) {
                return false
            }
            if (vertexAnalysis.significantVertexCount < VERTEX_MINIMUM_FOR_FILTER) {
                return false
            }

            if (wordLength > VERTEX_LONG_WORD_PRUNE_THRESHOLD) {
                return false
            }

            val minimumExpected = vertexAnalysis.minimumExpectedLength
            val deficit = minimumExpected - wordLength

            return deficit >= 5
        }

        fun computeKeyNeighborhoods(keyPositions: Map<Char, PointF>): Map<Char, KeyNeighborhood> {
            val result = HashMap<Char, KeyNeighborhood>(keyPositions.size)
            val tempChars = ArrayList<Char>(keyPositions.size)
            val tempDists = ArrayList<Float>(keyPositions.size)

            keyPositions.forEach { (key, keyPos) ->
                tempChars.clear()
                tempDists.clear()

                keyPositions.forEach { (otherKey, otherPos) ->
                    if (otherKey != key) {
                        val dx = keyPos.x - otherPos.x
                        val dy = keyPos.y - otherPos.y
                        val distSq = dx * dx + dy * dy
                        if (distSq < NEIGHBORHOOD_PROXIMITY_RADIUS_SQ) {
                            val dist = sqrt(distSq)
                            var insertIdx = tempDists.size
                            for (i in tempDists.indices) {
                                if (dist < tempDists[i]) {
                                    insertIdx = i
                                    break
                                }
                            }
                            tempChars.add(insertIdx, otherKey)
                            tempDists.add(insertIdx, dist)
                        }
                    }
                }

                val count = minOf(tempChars.size, MAX_KEY_NEIGHBORS)
                val chars = CharArray(count)
                val dists = FloatArray(count)
                for (i in 0 until count) {
                    chars[i] = tempChars[i]
                    dists[i] = tempDists[i]
                }

                result[key] = KeyNeighborhood(chars, dists)
            }

            return result
        }

        fun calculateNeighborhoodRescueScore(
            pathPointX: Float,
            pathPointY: Float,
            neighborhood: KeyNeighborhood,
            keyPositions: Map<Char, PointF>,
            sigma: Float,
        ): Float {
            val twoSigmaSq = 2f * sigma * sigma
            val maxNeighborDist = sqrt(NEIGHBORHOOD_PROXIMITY_RADIUS_SQ)
            var bestRescue = 0f

            for (i in neighborhood.neighborChars.indices) {
                val neighborChar = neighborhood.neighborChars[i]
                val interKeyDist = neighborhood.neighborDistances[i]
                val neighborPos = keyPositions[neighborChar] ?: continue
                val dx = pathPointX - neighborPos.x
                val dy = pathPointY - neighborPos.y
                val distToNeighborSq = dx * dx + dy * dy

                val neighborGaussian = exp(-distToNeighborSq / twoSigmaSq)
                val proximityTransfer = 1f - (interKeyDist / maxNeighborDist)
                val rescueScore =
                    neighborGaussian *
                        proximityTransfer *
                        NEIGHBORHOOD_DECAY_FACTOR

                if (rescueScore > bestRescue) {
                    bestRescue = rescueScore
                }
            }

            return bestRescue.coerceAtMost(NEIGHBORHOOD_RESCUE_CEILING)
        }

        fun calculateAnchorSigmaModifier(
            letterIndex: Int,
            wordLength: Int,
            closestPathIndex: Int,
            analysis: GeometricAnalysis,
        ): Float {
            if (letterIndex == 0 || letterIndex == wordLength - 1) {
                return ANCHOR_SIGMA_TIGHTENING
            }

            for (inflection in analysis.inflectionPoints) {
                if (inflection.isIntentional) {
                    val pathIndexDistance = abs(closestPathIndex - inflection.pathIndex)
                    if (pathIndexDistance < ANCHOR_INFLECTION_PROXIMITY_THRESHOLD) {
                        return INFLECTION_ANCHOR_SIGMA_TIGHTENING
                    }
                }
            }

            return if (wordLength > LONG_WORD_SIGMA_THRESHOLD) {
                LONG_WORD_MID_SIGMA_EXPANSION
            } else {
                MID_SWIPE_SIGMA_EXPANSION
            }
        }

        fun calculateLexicalCoherenceBonus(letterScores: ArrayList<Pair<Char, Float>>): Float {
            if (letterScores.size < LEXICAL_COHERENCE_MIN_LETTERS) {
                return 1.0f
            }

            var sum = 0f
            var nearMissCount = 0

            for ((_, score) in letterScores) {
                sum += score
                if (score in LEXICAL_NEAR_MISS_LOWER..LEXICAL_NEAR_MISS_UPPER) {
                    nearMissCount++
                }
            }

            val avgScore = sum / letterScores.size
            if (avgScore < LEXICAL_COHERENCE_AVG_THRESHOLD) return 1.0f

            val coherenceRatio = nearMissCount.toFloat() / letterScores.size.toFloat()

            return if (coherenceRatio > LEXICAL_COHERENCE_RATIO_THRESHOLD) {
                LEXICAL_COHERENCE_BONUS
            } else {
                1.0f
            }
        }

        private fun detectDwellClusters(
            path: List<SwipeDetector.SwipePoint>,
            velocityProfile: FloatArray,
            keyPositions: Map<Char, PointF>,
        ): List<DwellInterestPoint> {
            if (path.size < DWELL_CLUSTER_MIN_POINTS) return emptyList()

            val clusters = mutableListOf<DwellInterestPoint>()
            var runStart = -1

            for (i in velocityProfile.indices) {
                if (velocityProfile[i] < DWELL_CLUSTER_VELOCITY_THRESHOLD) {
                    if (runStart == -1) runStart = i
                } else {
                    if (runStart != -1) {
                        val runEnd = i - 1
                        val pointCount = runEnd - runStart + 1
                        if (pointCount >= DWELL_CLUSTER_MIN_POINTS) {
                            buildDwellCluster(path, runStart, runEnd, keyPositions)?.let { clusters.add(it) }
                        }
                        runStart = -1
                    }
                }
            }

            if (runStart != -1) {
                val runEnd = velocityProfile.size - 1
                val pointCount = runEnd - runStart + 1
                if (pointCount >= DWELL_CLUSTER_MIN_POINTS) {
                    buildDwellCluster(path, runStart, runEnd, keyPositions)?.let { clusters.add(it) }
                }
            }

            return clusters
        }

        private fun buildDwellCluster(
            path: List<SwipeDetector.SwipePoint>,
            startIdx: Int,
            endIdx: Int,
            keyPositions: Map<Char, PointF>,
        ): DwellInterestPoint? {
            var sumX = 0f
            var sumY = 0f
            val pointCount = endIdx - startIdx + 1

            for (i in startIdx..endIdx) {
                sumX += path[i].x
                sumY += path[i].y
            }

            val centroidX = sumX / pointCount
            val centroidY = sumY / pointCount

            for (i in startIdx..endIdx) {
                val dx = path[i].x - centroidX
                val dy = path[i].y - centroidY
                if (dx * dx + dy * dy > DWELL_CLUSTER_RADIUS_SQ) {
                    return null
                }
            }

            var nearestKey: Char? = null
            var minDist = Float.MAX_VALUE
            keyPositions.forEach { (key, pos) ->
                val dx = centroidX - pos.x
                val dy = centroidY - pos.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < minDist) {
                    minDist = dist
                    nearestKey = key
                }
            }

            if (minDist > DWELL_INTEREST_KEY_RADIUS) return null

            val confidence = (pointCount / 6.0f).coerceAtMost(1.0f)

            return DwellInterestPoint(
                pathIndexStart = startIdx,
                pathIndexEnd = endIdx,
                centroidX = centroidX,
                centroidY = centroidY,
                nearestKey = nearestKey,
                distanceToKey = minDist,
                confidence = confidence,
            )
        }

        fun getDwellInterestBoost(
            key: Char,
            closestPointIndex: Int,
            analysis: GeometricAnalysis,
        ): Float {
            for (dwell in analysis.dwellInterestPoints) {
                if (dwell.nearestKey == key &&
                    closestPointIndex in dwell.pathIndexStart..dwell.pathIndexEnd
                ) {
                    return 1.0f + (DWELL_INTEREST_BOOST - 1.0f) * dwell.confidence
                }
            }
            return 1.0f
        }

        fun getVelocityDwellBoost(
            closestPointIndex: Int,
            analysis: GeometricAnalysis,
        ): Float {
            if (analysis.velocityProfile.isEmpty()) return 1.0f
            val idx = closestPointIndex.coerceIn(0, analysis.velocityProfile.size - 1)
            val velocityAtPoint = analysis.velocityProfile[idx]
            val surroundingAvg = calculateSurroundingVelocity(analysis.velocityProfile, idx)
            if (surroundingAvg <= 0f) return 1.0f
            val dropRatio = velocityAtPoint / surroundingAvg
            val threshold = VERTEX_VELOCITY_DROP_RATIO * 2f
            return if (dropRatio < threshold) DWELL_INTEREST_BOOST else 1.0f
        }

        fun getVertexCurvatureBoost(
            key: Char,
            closestPointIndex: Int,
            keyPosition: PointF,
            analysis: GeometricAnalysis,
        ): Float {
            var bestBoost = 1.0f
            val pathIndexProximity = 8

            for (vertex in analysis.vertexAnalysis.vertices) {
                if (!vertex.isSignificant) continue
                if (abs(closestPointIndex - vertex.pathIndex) > pathIndexProximity) continue

                val keyMatch = vertex.nearestKey == key
                val dx = keyPosition.x - vertex.position.x
                val dy = keyPosition.y - vertex.position.y
                val distToKey = sqrt(dx * dx + dy * dy)
                val effectiveRadius = if (vertex.angleChange > VERTEX_WIDE_ANGLE_THRESHOLD_RAD) {
                    VERTEX_WIDE_ANGLE_RADIUS
                } else {
                    INFLECTION_BOOST_RADIUS
                }
                val positionMatch = distToKey < effectiveRadius

                if (keyMatch || positionMatch) {
                    val normalizedAngle = vertex.angleChange / VERTEX_ANGLE_THRESHOLD_RAD
                    val boost = (INFLECTION_BOOST_BASE +
                        normalizedAngle * (INFLECTION_BOOST_MAX - INFLECTION_BOOST_BASE))
                        .coerceAtMost(INFLECTION_BOOST_MAX)
                    if (boost > bestBoost) bestBoost = boost
                }
            }

            if (bestBoost == 1.0f) {
                for (inflection in analysis.inflectionPoints) {
                    if (!inflection.isIntentional || inflection.nearestKey != key) continue
                    if (inflection.distanceToKey >= INFLECTION_BOOST_RADIUS) continue
                    val angleBoost = (inflection.angle / MAX_EXPECTED_ANGLE)
                        .coerceIn(0.5f, 1.0f)
                    val boost = INFLECTION_BOOST_BASE +
                        (INFLECTION_BOOST_MAX - INFLECTION_BOOST_BASE) * angleBoost
                    if (boost > bestBoost) bestBoost = boost
                }
            }

            return bestBoost
        }

        fun getCornerCompensation(
            closestPointIndex: Int,
            analysis: GeometricAnalysis,
        ): PointF? {
            for (inflection in analysis.inflectionPoints) {
                if (inflection.compensatedPosition != null &&
                    abs(closestPointIndex - inflection.pathIndex) <= 2
                ) {
                    return inflection.compensatedPosition
                }
            }
            return null
        }

        fun calculatePathCoherenceScore(
            word: String,
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
            letterPathIndices: List<Int>,
        ): Float {
            if (word.length < GeometricScoringConstants.PATH_COHERENCE_MIN_WORD_LENGTH ||
                letterPathIndices.size < word.length
            ) {
                return GeometricScoringConstants.PATH_COHERENCE_NEUTRAL
            }

            val minSegPx = PATH_COHERENCE_MIN_SEGMENT_PX
            val magSigma = PATH_COHERENCE_MAGNITUDE_SIGMA
            val magSigmaSq2 = 2f * magSigma * magSigma
            val dirW = PATH_COHERENCE_DIRECTION_WEIGHT
            val magW = PATH_COHERENCE_MAGNITUDE_WEIGHT
            val vertW = PATH_COHERENCE_VERTICAL_WEIGHT

            var totalScore = 0f
            var segments = 0

            for (i in 0 until word.length - 1) {
                val c1 = word[i].lowercaseChar()
                val c2 = word[i + 1].lowercaseChar()
                if (c1 == c2) continue

                val key1 = keyPositions[c1] ?: continue
                val key2 = keyPositions[c2] ?: continue

                val eDx = key2.x - key1.x
                val eDy = (key2.y - key1.y) * vertW
                val eLen = sqrt(eDx * eDx + eDy * eDy)
                if (eLen < minSegPx) continue

                val idx1 = letterPathIndices[i]
                val idx2 = letterPathIndices[i + 1]
                if (idx1 >= idx2 || idx1 >= path.size || idx2 >= path.size) continue

                val p1 = path[idx1]
                val p2 = path[idx2]
                val aDx = p2.x - p1.x
                val aDy = (p2.y - p1.y) * vertW
                val aLen = sqrt(aDx * aDx + aDy * aDy)
                if (aLen < 5f) continue

                val dot = eDx * aDx + eDy * aDy
                val cosSim = (dot / (eLen * aLen)).coerceIn(-1f, 1f)
                val directionScore = (cosSim + 1f) / 2f

                val magDiff = abs(aLen - eLen)
                val magnitudeScore = exp(-magDiff * magDiff / magSigmaSq2)

                totalScore += directionScore * dirW + magnitudeScore * magW
                segments++
            }

            return if (segments > 0) totalScore / segments else GeometricScoringConstants.PATH_COHERENCE_NEUTRAL
        }

        private fun createEmptyAnalysis(): GeometricAnalysis =
            GeometricAnalysis(
                inflectionPoints = emptyList(),
                segments = emptyList(),
                pathConfidence = 0.5f,
                velocityProfile = FloatArray(0),
                curvatureProfile = FloatArray(0),
                traversedKeys = emptyMap(),
                vertexAnalysis =
                    VertexAnalysis(
                        vertices = emptyList(),
                        significantVertexCount = 0,
                        minimumExpectedLength = 2,
                        pathPointCount = 0,
                    ),
                dwellInterestPoints = emptyList(),
            )

        private companion object {
            const val MAX_PATH_POINTS = 500

            const val TIGHT_CLUSTER_SIGMA = 35f
            const val NORMAL_SIGMA = 42f
            const val EDGE_KEY_SIGMA = 55f

            const val NEIGHBOR_RADIUS_SQ = 10000f
            const val TIGHT_CLUSTER_THRESHOLD = 4
            const val NORMAL_CLUSTER_THRESHOLD = 2

            const val SLOW_VELOCITY_THRESHOLD = 0.3f
            const val SLOW_VELOCITY_BOOST = 1.35f

            const val INFLECTION_ANGLE_THRESHOLD = 0.52f
            const val INTENTIONAL_CORNER_THRESHOLD = 0.87f
            const val INTENTIONAL_CORNER_KEY_RADIUS = 60f
            const val INFLECTION_BOOST_RADIUS = 50f
            const val INFLECTION_BOOST_BASE = 1.0f
            const val INFLECTION_BOOST_MAX = 1.50f
            const val MAX_EXPECTED_ANGLE = 2.5f

            const val DWELL_DETECTION_RADIUS_SQ = 2500f
            const val DWELL_VELOCITY_THRESHOLD = 0.25f
            const val MAX_REPEATED_LETTER_BOOST = 1.25f

            const val MAX_CLUSTERED_WORD_LENGTH = 4
            const val CLUSTER_MAX_DISTANCE_SQ = 14400f

            const val PATH_COVERAGE_RADIUS = 45f

            const val CORNER_CURVATURE_THRESHOLD = 0.70f
            const val CURVE_CURVATURE_THRESHOLD = 0.30f
            const val SEGMENT_KEY_PROXIMITY_SQ = 3600f

            const val CONFIDENCE_INFLECTION_WEIGHT = 0.40f
            const val CONFIDENCE_VELOCITY_WEIGHT = 0.25f
            const val CONFIDENCE_SMOOTHNESS_WEIGHT = 0.35f
            const val MAX_EXPECTED_CURVATURE = 1.5f

            const val HIGH_CONFIDENCE_THRESHOLD = 0.80f
            const val MEDIUM_CONFIDENCE_THRESHOLD = 0.60f
            const val LOW_CONFIDENCE_THRESHOLD = 0.40f

            const val HIGH_CONFIDENCE_SPATIAL_WEIGHT = 0.85f
            const val HIGH_CONFIDENCE_FREQ_WEIGHT = 0.15f
            const val MEDIUM_CONFIDENCE_SPATIAL_WEIGHT = 0.72f
            const val MEDIUM_CONFIDENCE_FREQ_WEIGHT = 0.28f
            const val LOW_CONFIDENCE_SPATIAL_WEIGHT = 0.60f
            const val LOW_CONFIDENCE_FREQ_WEIGHT = 0.40f
            const val VERY_LOW_CONFIDENCE_SPATIAL_WEIGHT = 0.52f
            const val VERY_LOW_CONFIDENCE_FREQ_WEIGHT = 0.48f

            const val VERTEX_ANGLE_THRESHOLD_RAD = 1.22f
            const val VERTEX_VELOCITY_DROP_RATIO = 0.35f
            const val VERTEX_TOLERANCE_CONSTANT = 6
            const val VERTEX_MINIMUM_FOR_FILTER = 6
            const val VERTEX_LONG_WORD_PRUNE_THRESHOLD = 7
            const val VERTEX_LONG_WORD_DEFICIT_PENALTY = 0.55f
            const val VERTEX_LENGTH_MISMATCH_PENALTY = 0.40f
            const val VERTEX_MILD_MISMATCH_PENALTY = 0.75f
            const val VERTEX_DENSE_AREA_SENSITIVITY_BOOST = 0.90f
            const val VERTEX_PATH_SIMPLIFICATION_EPSILON = 15f
            const val VERTEX_DENSE_AREA_RADIUS_PX = 55f
            const val VERTEX_FLY_BY_GAP_THRESHOLD_PX = 35f
            const val VERTEX_WIDE_ANGLE_RADIUS = 65f
            const val VERTEX_WIDE_ANGLE_THRESHOLD_RAD = 1.40f

            const val NEIGHBORHOOD_PROXIMITY_RADIUS_SQ = 14400f
            const val MAX_KEY_NEIGHBORS = 6
            const val NEIGHBORHOOD_DECAY_FACTOR = 0.65f
            const val NEIGHBORHOOD_RESCUE_CEILING = 0.70f

            const val ANCHOR_SIGMA_TIGHTENING = 0.80f
            const val MID_SWIPE_SIGMA_EXPANSION = 1.20f
            const val INFLECTION_ANCHOR_SIGMA_TIGHTENING = 0.88f
            const val ANCHOR_INFLECTION_PROXIMITY_THRESHOLD = 5

            const val LONG_WORD_MID_SIGMA_EXPANSION = 1.40f
            const val LONG_WORD_SIGMA_THRESHOLD = 6

            const val LEXICAL_COHERENCE_MIN_LETTERS = 3
            const val LEXICAL_COHERENCE_AVG_THRESHOLD = 0.55f
            const val LEXICAL_COHERENCE_BONUS = 1.10f
            const val LEXICAL_NEAR_MISS_LOWER = 0.35f
            const val LEXICAL_NEAR_MISS_UPPER = 0.75f
            const val LEXICAL_COHERENCE_RATIO_THRESHOLD = 0.50f

            const val DWELL_CLUSTER_VELOCITY_THRESHOLD = 3.0f
            const val DWELL_CLUSTER_RADIUS_SQ = 2500f
            const val DWELL_CLUSTER_MIN_POINTS = 3
            const val DWELL_INTEREST_BOOST = 1.25f
            const val DWELL_INTEREST_KEY_RADIUS = 55f

            const val CORNER_COMPENSATION_VELOCITY_THRESHOLD = 8.0f
            const val CORNER_COMPENSATION_MAX_OFFSET_PX = 25f
            const val CORNER_COMPENSATION_VELOCITY_SCALE = 20f

            const val PATH_COHERENCE_VERTICAL_WEIGHT = 1.45f
            const val PATH_COHERENCE_MIN_SEGMENT_PX = 30f
            const val PATH_COHERENCE_DIRECTION_WEIGHT = 0.50f
            const val PATH_COHERENCE_MAGNITUDE_WEIGHT = 0.50f
            const val PATH_COHERENCE_MAGNITUDE_SIGMA = 80f
        }
    }
