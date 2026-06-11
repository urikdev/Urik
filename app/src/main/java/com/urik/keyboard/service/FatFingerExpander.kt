package com.urik.keyboard.service

import android.graphics.PointF
import com.urik.keyboard.service.FatFingerExpander.Companion.ADJACENT_KEY_THRESHOLD_MULTIPLIER
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Stateless helper that derives an adjacent-key map from key position data and generates
 * single-character substitution variants of a word for fat-finger spell-check expansion.
 */
@Singleton
class FatFingerExpander @Inject constructor() {
    /**
     * Derives a map of adjacent keys for each character using Euclidean distance with a
     * hard cutoff of [avgKeySpacing] * [ADJACENT_KEY_THRESHOLD_MULTIPLIER].
     */
    fun buildAdjacentKeyMap(positions: Map<Char, PointF>, avgKeySpacing: Double): Map<Char, Set<Char>> {
        if (positions.isEmpty() || avgKeySpacing <= 0) return emptyMap()
        val threshold = avgKeySpacing * ADJACENT_KEY_THRESHOLD_MULTIPLIER
        return positions.entries.associate { (c1, p1) ->
            c1 to positions.entries.mapNotNullTo(mutableSetOf()) { (c2, p2) ->
                if (c2 == c1) return@mapNotNullTo null
                val dx = (p1.x - p2.x).toDouble()
                val dy = (p1.y - p2.y).toDouble()
                val distance = sqrt(dx * dx + dy * dy)
                if (distance <= threshold) c2 else null
            }
        }
    }

    /**
     * Generates single-character substitution variants of [word] using [adjacentKeyMap].
     * Each variant substitutes exactly one character with one of its adjacent-key alternatives.
     * The original word is never included in the result.
     */
    fun generateVariants(word: String, adjacentKeyMap: Map<Char, Set<Char>>): List<String> {
        if (adjacentKeyMap.isEmpty() || word.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        for (i in word.indices) {
            val adjacents = adjacentKeyMap[word[i]] ?: continue
            for (adjacent in adjacents) {
                result.add(word.substring(0, i) + adjacent + word.substring(i + 1))
            }
        }
        return result
    }

    internal companion object {
        const val ADJACENT_KEY_THRESHOLD_MULTIPLIER = 1.5
    }
}
