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
        DWELL
    }

    data class InflectionPoint(
        val pathIndex: Int,
        val position: PointF,
        val angle: Float,
        val nearestKey: Char?,
        val distanceToKey: Float,
        val isIntentional: Boolean,
        val compensatedPosition: PointF? = null,
        val velocityAtInflection: Float = 0f
    )

    data class DwellInterestPoint(
        val pathIndexStart: Int,
        val pathIndexEnd: Int,
        val centroidX: Float,
        val centroidY: Float,
        val nearestKey: Char?,
        val distanceToKey: Float,
        val confidence: Float
    )

    data class PathSegment(
        val startIndex: Int,
        val endIndex: Int,
        val type: SegmentType,
        val averageVelocity: Float,
        val curvature: Float,
        val traversedKeys: Set<Char>
    )

    data class GeometricAnalysis(
        val inflectionPoints: List<InflectionPoint>,
        val segments: List<PathSegment>,
        val pathConfidence: Float,
        val velocityProfile: FloatArray,
        val curvatureProfile: FloatArray,
        val traversedKeys: Map<Char, KeyTraversal>,
        val vertexAnalysis: VertexAnalysis,
        val dwellInterestPoints: List<DwellInterestPoint> = emptyList()
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
        val confidence: Float
    )

    data class PathVertex(
        val pathIndex: Int,
        val position: PointF,
        val angleChange: Float,
        val velocityRatio: Float,
        val nearestKey: Char?,
        val isSignificant: Boolean
    )

    data class VertexAnalysis(
        val vertices: List<PathVertex>,
        val significantVertexCount: Int,
        val minimumExpectedLength: Int,
        val pathPointCount: Int = 0
    )

    data class AdaptiveSigma(val sigma: Float, val neighborCount: Int)

    data class KeyNeighborhood(val neighborChars: CharArray, val neighborDistances: FloatArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as KeyNeighborhood

            if (!neighborChars.contentEquals(other.neighborChars)) return false
            if (!neighborDistances.contentEquals(other.neighborDistances)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = neighborChars.contentHashCode()
            result = 31 * result + neighborDistances.contentHashCode()
            return result
        }
    }

    private val vertexAnalyzer = VertexAnalyzer()
    private val dwellDetector = DwellDetector()
    private val sigmaCalculator = SigmaCalculator()

    private val reusableCurvatureArray = FloatArray(MAX_PATH_POINTS)
    private val reusableVelocityArray = FloatArray(MAX_PATH_POINTS)
    private val reusableCoverageFlags = BooleanArray(MAX_PATH_POINTS)
    private val cachedIntersectionPoint = PointF()

    /**
     * Performs comprehensive geometric analysis on a swipe path.
     */
    fun analyze(path: List<SwipeDetector.SwipePoint>, keyPositions: Map<Char, PointF>): GeometricAnalysis {
        if (path.size < 3) {
            return createEmptyAnalysis()
        }

        val curvatureProfile = calculateCurvatureProfile(path)
        val velocityProfile = calculateVelocityProfile(path)
        val inflectionPoints = detectInflectionPoints(path, curvatureProfile, velocityProfile, keyPositions)
        val traversedKeys = detectKeyTraversals(path, velocityProfile, keyPositions)
        val segments = classifySegments(path, curvatureProfile, velocityProfile, traversedKeys)
        val pathConfidence = calculatePathConfidence(path, inflectionPoints, velocityProfile, curvatureProfile)
        val vertexAnalysis = vertexAnalyzer.detectVertices(path, velocityProfile, keyPositions)
        val dwellInterestPoints = dwellDetector.detectDwellClusters(path, velocityProfile, keyPositions)

        return GeometricAnalysis(
            inflectionPoints = inflectionPoints,
            segments = segments,
            pathConfidence = pathConfidence,
            velocityProfile = velocityProfile,
            curvatureProfile = curvatureProfile,
            traversedKeys = traversedKeys,
            vertexAnalysis = vertexAnalysis,
            dwellInterestPoints = dwellInterestPoints
        )
    }

    /**
     * Calculates adaptive Gaussian sigma based on key neighborhood density.
     */
    fun calculateAdaptiveSigma(key: Char, keyPositions: Map<Char, PointF>): AdaptiveSigma =
        sigmaCalculator.calculateAdaptiveSigma(key, keyPositions)

    /**
     * Calculates velocity-based weight for letter scoring.
     */
    fun calculateVelocityWeight(velocity: Float): Float = when {
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
    fun didPathTraverseKey(key: Char, analysis: GeometricAnalysis): Boolean = analysis.traversedKeys.containsKey(key)

    /**
     * Calculates dynamic spatial/frequency weights based on path confidence.
     */
    fun calculateDynamicWeights(pathConfidence: Float): Pair<Float, Float> = when {
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
        endIndex: Int
    ): Float = dwellDetector.detectRepeatedLetterSignal(path, keyPosition, startIndex, endIndex)

    /**
     * Determines if a word is in a tight geometric cluster.
     */
    fun isClusteredWord(word: String, keyPositions: Map<Char, PointF>): Boolean {
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
        letterPathIndices: List<Int>
    ): Float {
        if (path.isEmpty() || letterPathIndices.isEmpty()) return 0f

        val size = path.size
        val flags = reusableCoverageFlags
        flags.fill(false, 0, minOf(size, flags.size))
        val coverageRadius = PATH_COVERAGE_RADIUS
        val radiusSq = coverageRadius * coverageRadius
        val windowRadius = (size / 15).coerceIn(3, 20)

        letterPathIndices.forEachIndexed { letterIdx, pathIdx ->
            if (pathIdx in 0..<size && letterIdx < word.length) {
                val keyPos = keyPositions[word[letterIdx].lowercaseChar()] ?: return@forEachIndexed

                for (i in maxOf(0, pathIdx - windowRadius)..minOf(size - 1, pathIdx + windowRadius)) {
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
        p2: SwipeDetector.SwipePoint
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
        keyPositions: Map<Char, PointF>
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
                    if (velocity > CORNER_COMPENSATION_VELOCITY_THRESHOLD && i < path.size - 1) {
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
                        velocityAtInflection = velocity
                    )
                )
            }
        }

        return inflections
    }

    private fun computeCornerCompensation(path: List<SwipeDetector.SwipePoint>, index: Int, velocity: Float): PointF {
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

        val offset =
            (velocity * CORNER_COMPENSATION_VELOCITY_SCALE)
                .coerceAtMost(CORNER_COMPENSATION_MAX_OFFSET_PX)

        return PointF(
            curr.x - bisectX / bisectLen * offset,
            curr.y - bisectY / bisectLen * offset
        )
    }

    private fun findNearestKey(point: SwipeDetector.SwipePoint, keyPositions: Map<Char, PointF>): Pair<Char?, Float> {
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
        keyPositions: Map<Char, PointF>
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
        keyPos: PointF
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
                    cachedIntersectionPoint
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
                        velocity
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
                confidence = bestConfidence
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
        outPoint: PointF
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
        velocity: Float
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
        traversedKeys: Map<Char, KeyTraversal>
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
                        traversedKeys = traversedInSegment
                    )
                )

                segmentStart = i
                currentType = pointType
            }
        }

        return segments
    }

    private fun classifyPoint(index: Int, curvatureProfile: FloatArray, velocityProfile: FloatArray): SegmentType {
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
        traversedKeys: Map<Char, KeyTraversal>
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

    private fun calculateAverageVelocity(velocityProfile: FloatArray, startIndex: Int, endIndex: Int): Float {
        if (startIndex >= endIndex || startIndex >= velocityProfile.size) return 0f

        var sum = 0f
        var count = 0
        for (i in startIndex..minOf(endIndex, velocityProfile.size - 1)) {
            sum += velocityProfile[i]
            count++
        }

        return if (count > 0) sum / count else 0f
    }

    private fun calculateAverageCurvature(curvatureProfile: FloatArray, startIndex: Int, endIndex: Int): Float {
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
        curvatureProfile: FloatArray
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

    private fun calculateVelocityConsistency(velocityProfile: FloatArray, size: Int): Float {
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
        val variance = sumSq / actualSize - mean * mean
        val stdDev = sqrt(maxOf(0f, variance))
        val coeffOfVariation = if (mean > 0) stdDev / mean else 1f

        return 1f - coeffOfVariation.coerceIn(0f, 1f)
    }

    private fun calculatePathSmoothness(curvatureProfile: FloatArray, size: Int): Float {
        if (size < 3) return 1f

        val actualSize = minOf(size, curvatureProfile.size)
        var totalCurvature = 0f

        for (i in 0 until actualSize) {
            totalCurvature += abs(curvatureProfile[i])
        }

        val avgCurvature = totalCurvature / actualSize
        val normalizedCurvature = avgCurvature / MAX_EXPECTED_CURVATURE

        return 1f - normalizedCurvature.coerceIn(0f, 1f)
    }

    fun calculateVertexLengthPenalty(
        wordLength: Int,
        vertexAnalysis: VertexAnalysis,
        rawPointCount: Int = vertexAnalysis.pathPointCount
    ): Float = vertexAnalyzer.calculateVertexLengthPenalty(wordLength, vertexAnalysis, rawPointCount)

    fun shouldPruneCandidate(
        wordLength: Int,
        vertexAnalysis: VertexAnalysis,
        rawPointCount: Int = vertexAnalysis.pathPointCount
    ): Boolean = vertexAnalyzer.shouldPruneCandidate(wordLength, vertexAnalysis, rawPointCount)

    fun computeKeyNeighborhoods(keyPositions: Map<Char, PointF>): Map<Char, KeyNeighborhood> =
        sigmaCalculator.computeKeyNeighborhoods(keyPositions)

    fun calculateNeighborhoodRescueScore(
        pathPointX: Float,
        pathPointY: Float,
        neighborhood: KeyNeighborhood,
        keyPositions: Map<Char, PointF>,
        sigma: Float
    ): Float =
        sigmaCalculator.calculateNeighborhoodRescueScore(pathPointX, pathPointY, neighborhood, keyPositions, sigma)

    fun calculateAnchorSigmaModifier(
        letterIndex: Int,
        wordLength: Int,
        closestPathIndex: Int,
        analysis: GeometricAnalysis,
        pathSize: Int = 50
    ): Float =
        sigmaCalculator.calculateAnchorSigmaModifier(letterIndex, wordLength, closestPathIndex, analysis, pathSize)

    fun calculateLexicalCoherenceBonus(scores: FloatArray, count: Int): Float {
        if (count < LEXICAL_COHERENCE_MIN_LETTERS) {
            return 1.0f
        }

        var sum = 0f
        var nearMissCount = 0

        for (i in 0 until count) {
            val score = scores[i]
            sum += score
            if (score in LEXICAL_NEAR_MISS_LOWER..LEXICAL_NEAR_MISS_UPPER) {
                nearMissCount++
            }
        }

        val avgScore = sum / count
        if (avgScore < LEXICAL_COHERENCE_AVG_THRESHOLD) return 1.0f

        val coherenceRatio = nearMissCount.toFloat() / count.toFloat()

        return if (coherenceRatio > LEXICAL_COHERENCE_RATIO_THRESHOLD) {
            LEXICAL_COHERENCE_BONUS
        } else {
            1.0f
        }
    }

    fun getDwellInterestBoost(key: Char, closestPointIndex: Int, analysis: GeometricAnalysis): Float =
        dwellDetector.getDwellInterestBoost(key, closestPointIndex, analysis)

    fun getVelocityDwellBoost(closestPointIndex: Int, analysis: GeometricAnalysis): Float =
        dwellDetector.getVelocityDwellBoost(closestPointIndex, analysis)

    fun getVertexCurvatureBoost(
        key: Char,
        closestPointIndex: Int,
        keyPosition: PointF,
        analysis: GeometricAnalysis,
        pathSize: Int = 50
    ): Float = vertexAnalyzer.getVertexCurvatureBoost(key, closestPointIndex, keyPosition, analysis, pathSize)

    fun getCornerCompensation(closestPointIndex: Int, analysis: GeometricAnalysis, pathSize: Int = 50): PointF? {
        val proximityThreshold = maxOf(2, pathSize / 22)
        for (inflection in analysis.inflectionPoints) {
            if (inflection.compensatedPosition != null &&
                abs(closestPointIndex - inflection.pathIndex) <= proximityThreshold
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
        letterPathIndices: List<Int>
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
            val score = scoreCoherenceSegment(
                word, i, path, keyPositions, letterPathIndices,
                minSegPx, magSigmaSq2, dirW, magW, vertW
            ) ?: continue

            totalScore += score
            segments++
        }

        return if (segments > 0) totalScore / segments else GeometricScoringConstants.PATH_COHERENCE_NEUTRAL
    }

    private fun scoreCoherenceSegment(
        word: String,
        i: Int,
        path: List<SwipeDetector.SwipePoint>,
        keyPositions: Map<Char, PointF>,
        letterPathIndices: List<Int>,
        minSegPx: Float,
        magSigmaSq2: Float,
        dirW: Float,
        magW: Float,
        vertW: Float
    ): Float? {
        val c1 = word[i].lowercaseChar()
        val c2 = word[i + 1].lowercaseChar()
        if (c1 == c2) return null

        val key1 = keyPositions[c1] ?: return null
        val key2 = keyPositions[c2] ?: return null

        val eDx = key2.x - key1.x
        val eDy = (key2.y - key1.y) * vertW
        val eLen = sqrt(eDx * eDx + eDy * eDy)
        if (eLen < minSegPx) return null

        val idx1 = letterPathIndices[i]
        val idx2 = letterPathIndices[i + 1]
        if (idx1 >= idx2 || idx1 >= path.size || idx2 >= path.size) return null

        val p1 = path[idx1]
        val p2 = path[idx2]
        val aDx = p2.x - p1.x
        val aDy = (p2.y - p1.y) * vertW
        val aLen = sqrt(aDx * aDx + aDy * aDy)
        if (aLen < 5f) return null

        val dot = eDx * aDx + eDy * aDy
        val cosSim = (dot / (eLen * aLen)).coerceIn(-1f, 1f)
        val directionScore = (cosSim + 1f) / 2f

        val magDiff = abs(aLen - eLen)
        val magnitudeScore = exp(-magDiff * magDiff / magSigmaSq2)

        return directionScore * dirW + magnitudeScore * magW
    }

    private fun createEmptyAnalysis(): GeometricAnalysis = GeometricAnalysis(
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
            pathPointCount = 0
        ),
        dwellInterestPoints = emptyList()
    )

    private companion object {
        const val MAX_PATH_POINTS = 500

        const val SLOW_VELOCITY_THRESHOLD = 0.3f
        const val SLOW_VELOCITY_BOOST = 1.35f

        const val INFLECTION_ANGLE_THRESHOLD = 0.52f
        const val INTENTIONAL_CORNER_THRESHOLD = 0.87f
        const val INTENTIONAL_CORNER_KEY_RADIUS = 60f

        const val DWELL_VELOCITY_THRESHOLD = 0.25f

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

        const val LEXICAL_COHERENCE_MIN_LETTERS = 3
        const val LEXICAL_COHERENCE_AVG_THRESHOLD = 0.55f
        const val LEXICAL_COHERENCE_BONUS = 1.10f
        const val LEXICAL_NEAR_MISS_LOWER = 0.35f
        const val LEXICAL_NEAR_MISS_UPPER = 0.75f
        const val LEXICAL_COHERENCE_RATIO_THRESHOLD = 0.50f

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
