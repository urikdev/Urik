package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import kotlin.math.sqrt

class DwellDetector {
    fun detectRepeatedLetterSignal(
        path: List<SwipeDetector.SwipePoint>,
        keyPosition: PointF,
        startIndex: Int,
        endIndex: Int
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

    fun getDwellInterestBoost(
        key: Char,
        closestPointIndex: Int,
        analysis: PathGeometryAnalyzer.GeometricAnalysis
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

    fun getVelocityDwellBoost(closestPointIndex: Int, analysis: PathGeometryAnalyzer.GeometricAnalysis): Float {
        if (analysis.velocityProfile.isEmpty()) return 1.0f
        val idx = closestPointIndex.coerceIn(0, analysis.velocityProfile.size - 1)
        val velocityAtPoint = analysis.velocityProfile[idx]
        val windowSize = maxOf(3, analysis.velocityProfile.size / 15)
        var sum = 0f
        var count = 0
        for (i in idx - windowSize..idx + windowSize) {
            if (i >= 0 && i < analysis.velocityProfile.size && i != idx) {
                sum += analysis.velocityProfile[i]
                count++
            }
        }
        val surroundingAvg = if (count > 0) sum / count else 0f
        if (surroundingAvg <= 0f) return 1.0f
        val dropRatio = velocityAtPoint / surroundingAvg
        val threshold = VERTEX_VELOCITY_DROP_RATIO * 2f
        return if (dropRatio < threshold) DWELL_INTEREST_BOOST else 1.0f
    }

    internal fun detectDwellClusters(
        path: List<SwipeDetector.SwipePoint>,
        velocityProfile: FloatArray,
        keyPositions: Map<Char, PointF>
    ): List<PathGeometryAnalyzer.DwellInterestPoint> {
        if (path.size < DWELL_CLUSTER_MIN_POINTS) return emptyList()

        val clusters = mutableListOf<PathGeometryAnalyzer.DwellInterestPoint>()
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
        keyPositions: Map<Char, PointF>
    ): PathGeometryAnalyzer.DwellInterestPoint? {
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

        return PathGeometryAnalyzer.DwellInterestPoint(
            pathIndexStart = startIdx,
            pathIndexEnd = endIdx,
            centroidX = centroidX,
            centroidY = centroidY,
            nearestKey = nearestKey,
            distanceToKey = minDist,
            confidence = confidence
        )
    }

    private fun detectOscillation(path: List<SwipeDetector.SwipePoint>, startIndex: Int, endIndex: Int): Float {
        if (endIndex - startIndex < 3) return 0f

        var directionChanges = 0
        var lastDx = 0f

        for (i in startIndex + 1 until minOf(endIndex, path.size)) {
            val dx = path[i].x - path[i - 1].x

            if (lastDx != 0f && dx != 0f && lastDx > 0 != dx > 0) {
                directionChanges++
            }
            lastDx = dx
        }

        return if (directionChanges >= 2) {
            minOf(directionChanges * 0.1f, 0.3f)
        } else {
            0f
        }
    }

    private companion object {
        const val DWELL_CLUSTER_VELOCITY_THRESHOLD = 3.0f
        const val DWELL_CLUSTER_RADIUS_SQ = 2500f
        const val DWELL_CLUSTER_MIN_POINTS = 3
        const val DWELL_INTEREST_BOOST = 1.25f
        const val DWELL_INTEREST_KEY_RADIUS = 55f
        const val DWELL_VELOCITY_THRESHOLD = 0.25f
        const val MAX_REPEATED_LETTER_BOOST = 1.25f
        const val DWELL_DETECTION_RADIUS_SQ = 2500f
        const val VERTEX_VELOCITY_DROP_RATIO = 0.35f
    }
}
