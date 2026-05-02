package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class SigmaCalculator {
    fun calculateAdaptiveSigma(key: Char, keyPositions: Map<Char, PointF>): PathGeometryAnalyzer.AdaptiveSigma {
        val keyPos =
            keyPositions[key] ?: return PathGeometryAnalyzer.AdaptiveSigma(
                GeometricScoringConstants.DEFAULT_SIGMA,
                0
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

        return PathGeometryAnalyzer.AdaptiveSigma(sigma, neighborCount)
    }

    fun computeKeyNeighborhoods(keyPositions: Map<Char, PointF>): Map<Char, PathGeometryAnalyzer.KeyNeighborhood> {
        val result = HashMap<Char, PathGeometryAnalyzer.KeyNeighborhood>(keyPositions.size)
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

            result[key] = PathGeometryAnalyzer.KeyNeighborhood(chars, dists)
        }

        return result
    }

    fun calculateNeighborhoodRescueScore(
        pathPointX: Float,
        pathPointY: Float,
        neighborhood: PathGeometryAnalyzer.KeyNeighborhood,
        keyPositions: Map<Char, PointF>,
        sigma: Float
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
            val proximityTransfer = 1f - interKeyDist / maxNeighborDist
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
        analysis: PathGeometryAnalyzer.GeometricAnalysis,
        pathSize: Int = 50
    ): Float {
        if (letterIndex == 0 || letterIndex == wordLength - 1) {
            return ANCHOR_SIGMA_TIGHTENING
        }

        val proximityThreshold = maxOf(ANCHOR_INFLECTION_PROXIMITY_THRESHOLD, pathSize / 10)
        for (inflection in analysis.inflectionPoints) {
            if (inflection.isIntentional) {
                val pathIndexDistance = abs(closestPathIndex - inflection.pathIndex)
                if (pathIndexDistance < proximityThreshold) {
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

    private companion object {
        const val TIGHT_CLUSTER_SIGMA = 35f
        const val NORMAL_SIGMA = 42f
        const val EDGE_KEY_SIGMA = 55f
        const val NEIGHBOR_RADIUS_SQ = 10000f
        const val TIGHT_CLUSTER_THRESHOLD = 4
        const val NORMAL_CLUSTER_THRESHOLD = 2
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
    }
}
