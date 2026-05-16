package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import kotlin.math.abs
import kotlin.math.sqrt

class VertexAnalyzer {
    fun calculateVertexLengthPenalty(
        wordLength: Int,
        vertexAnalysis: PathGeometryAnalyzer.VertexAnalysis,
        rawPointCount: Int = vertexAnalysis.pathPointCount
    ): Float {
        if (rawPointCount <= GeometricScoringConstants.VERTEX_FILTER_MIN_PATH_POINTS) {
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
        vertexAnalysis: PathGeometryAnalyzer.VertexAnalysis,
        rawPointCount: Int = vertexAnalysis.pathPointCount
    ): Boolean {
        if (rawPointCount <= GeometricScoringConstants.VERTEX_FILTER_MIN_PATH_POINTS) {
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

    fun getVertexCurvatureBoost(
        key: Char,
        closestPointIndex: Int,
        keyPosition: PointF,
        analysis: PathGeometryAnalyzer.GeometricAnalysis,
        pathSize: Int = 50
    ): Float {
        var bestBoost = 1.0f
        val pathIndexProximity = maxOf(8, pathSize / 6)

        for (vertex in analysis.vertexAnalysis.vertices) {
            if (!vertex.isSignificant) continue
            if (abs(closestPointIndex - vertex.pathIndex) > pathIndexProximity) continue

            val keyMatch = vertex.nearestKey == key
            val dx = keyPosition.x - vertex.position.x
            val dy = keyPosition.y - vertex.position.y
            val distToKey = sqrt(dx * dx + dy * dy)
            val effectiveRadius =
                if (vertex.angleChange > VERTEX_WIDE_ANGLE_THRESHOLD_RAD) {
                    VERTEX_WIDE_ANGLE_RADIUS
                } else {
                    INFLECTION_BOOST_RADIUS
                }
            val positionMatch = distToKey < effectiveRadius

            if (keyMatch || positionMatch) {
                val normalizedAngle = vertex.angleChange / VERTEX_ANGLE_THRESHOLD_RAD
                val boost =
                    (
                        INFLECTION_BOOST_BASE +
                            normalizedAngle * (INFLECTION_BOOST_MAX - INFLECTION_BOOST_BASE)
                        ).coerceAtMost(INFLECTION_BOOST_MAX)
                if (boost > bestBoost) bestBoost = boost
            }
        }

        if (bestBoost == 1.0f) {
            for (inflection in analysis.inflectionPoints) {
                if (!inflection.isIntentional || inflection.nearestKey != key) continue
                if (inflection.distanceToKey >= INFLECTION_BOOST_RADIUS) continue
                val angleBoost =
                    (inflection.angle / MAX_EXPECTED_ANGLE)
                        .coerceIn(0.5f, 1.0f)
                val boost =
                    INFLECTION_BOOST_BASE +
                        (INFLECTION_BOOST_MAX - INFLECTION_BOOST_BASE) * angleBoost
                if (boost > bestBoost) bestBoost = boost
            }
        }

        return bestBoost
    }

    internal fun detectVertices(
        path: List<SwipeDetector.SwipePoint>,
        velocityProfile: FloatArray,
        keyPositions: Map<Char, PointF>
    ): PathGeometryAnalyzer.VertexAnalysis {
        if (path.size < 5) {
            return PathGeometryAnalyzer.VertexAnalysis(
                vertices = emptyList(),
                significantVertexCount = 0,
                minimumExpectedLength = 2,
                pathPointCount = path.size
            )
        }

        val simplifiedIndices = douglasPeuckerSimplify(path)
        val vertices = mutableListOf<PathGeometryAnalyzer.PathVertex>()

        for (i in 1 until simplifiedIndices.size - 1) {
            run {
                val prevIdx = simplifiedIndices[i - 1]
                val currIdx = simplifiedIndices[i]
                val nextIdx = simplifiedIndices[i + 1]

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
                    return@run
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
                        PathGeometryAnalyzer.PathVertex(
                            pathIndex = currIdx,
                            position = vertexPosition,
                            angleChange = angleChange,
                            velocityRatio = velocityRatio,
                            nearestKey = nearestKey,
                            isSignificant = isSignificant
                        )
                    )
                }
            }
        }

        addFlyByVertices(path, simplifiedIndices, keyPositions, vertices)

        val significantCount = vertices.count { it.isSignificant }
        val minimumExpected =
            maxOf(
                2,
                significantCount - VERTEX_TOLERANCE_CONSTANT
            )

        return PathGeometryAnalyzer.VertexAnalysis(
            vertices = vertices,
            significantVertexCount = significantCount,
            minimumExpectedLength = minimumExpected,
            pathPointCount = path.size
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

            for (i in startIdx + 1 until endIdx) {
                val point = path[i]
                val dist =
                    abs(
                        lineDy * point.x - lineDx * point.y +
                            endPoint.x * startPoint.y - endPoint.y * startPoint.x
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

    private fun calculateSurroundingVelocity(velocityProfile: FloatArray, centerIndex: Int): Float {
        val windowSize = maxOf(3, velocityProfile.size / 15)
        var sum = 0f
        var count = 0

        for (i in centerIndex - windowSize..centerIndex + windowSize) {
            if (i >= 0 && i < velocityProfile.size && i != centerIndex) {
                sum += velocityProfile[i]
                count++
            }
        }

        return if (count > 0) sum / count else 0f
    }

    private fun isDenseKeyboardArea(point: SwipeDetector.SwipePoint, keyPositions: Map<Char, PointF>): Boolean {
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
        vertices: MutableList<PathGeometryAnalyzer.PathVertex>
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
                val distToSeg =
                    sqrt(
                        (keyPos.x - projX) * (keyPos.x - projX) +
                            (keyPos.y - projY) * (keyPos.y - projY)
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
                    PathGeometryAnalyzer.PathVertex(
                        pathIndex = midPathIdx,
                        position = PointF(keyPos.x, keyPos.y),
                        angleChange = angle,
                        velocityRatio = 1.0f,
                        nearestKey = key,
                        isSignificant = angle > VERTEX_ANGLE_THRESHOLD_RAD
                    )
                )
            }
        }
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

    private companion object {
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
        const val INFLECTION_BOOST_RADIUS = 50f
        const val INFLECTION_BOOST_BASE = 1.0f
        const val INFLECTION_BOOST_MAX = 1.50f
        const val MAX_EXPECTED_ANGLE = 2.5f
    }
}
