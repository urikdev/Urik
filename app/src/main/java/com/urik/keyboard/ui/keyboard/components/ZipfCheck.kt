@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import com.urik.keyboard.service.SpellCheckManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Standalone Zipf-based tie-breaking module for swipe candidate arbitration.
 *
 * When the top two candidates are within δ ≤ 0.05 of each other, the arbiter
 * applies a three-tier step-function weighting system with a Dynamic Leader Bonus
 * that scales to zero as the gap narrows below 1%, creating a Neutral Zone
 * where raw Zipf frequency holds absolute authority.
 */
@Singleton
class ZipfCheck
    @Inject
    constructor(
        private val spellCheckManager: SpellCheckManager,
    ) {
        data class ArbitrationResult(
            val candidates: List<WordCandidate>,
            val arbiterFired: Boolean,
            val winReason: String,
        )

        /**
         * Arbitrates scored candidates into a final ranked list.
         *
         * Filters blacklisted words, applies geometric disambiguation when
         * the top-2 gap is within the similarity threshold, and enriches
         * with prefix completions when confidence is low.
         */
        fun arbitrate(
            scoredCandidates: List<ResidualScorer.CandidateResult>,
            geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
            keyPositions: Map<Char, PointF>,
            bigramPredictions: Set<String>,
            wordFrequencyMap: Map<String, Int>,
            pathSize: Int,
        ): ArbitrationResult {
            if (scoredCandidates.isEmpty()) {
                return ArbitrationResult(emptyList(), false, "empty")
            }

            val filtered = scoredCandidates.filter { !spellCheckManager.isWordBlacklisted(it.word) }
            val sorted = filtered.sortedByDescending { it.combinedScore }
            val top = sorted.take(10)

            if (top.size < 2) {
                val candidates =
                    top.map {
                        WordCandidate(it.word, it.spatialScore, it.frequencyScore, it.combinedScore)
                    }
                val enriched = enrichWithPrefixCompletions(candidates, wordFrequencyMap, pathSize)
                return ArbitrationResult(enriched, false, "spatial")
            }

            val leader = top[0]
            val runnerUp = top[1]
            val scoreDelta = leader.combinedScore - runnerUp.combinedScore

            if (scoreDelta <= GeometricScoringConstants.GEOMETRIC_SIMILARITY_THRESHOLD) {
                val (winner, loser, winReason) =
                    disambiguateCloseCompetitors(
                        leader,
                        runnerUp,
                        geometricAnalysis,
                        keyPositions,
                        bigramPredictions,
                    )
                val reordered = mutableListOf(winner, loser)
                reordered.addAll(top.drop(2))
                val candidates =
                    reordered.take(3).map {
                        WordCandidate(it.word, it.spatialScore, it.frequencyScore, it.combinedScore)
                    }
                val enriched = enrichWithPrefixCompletions(candidates, wordFrequencyMap, pathSize)
                return ArbitrationResult(enriched, true, winReason)
            }

            val candidates =
                top.take(3).map {
                    WordCandidate(it.word, it.spatialScore, it.frequencyScore, it.combinedScore)
                }
            val enriched = enrichWithPrefixCompletions(candidates, wordFrequencyMap, pathSize)
            return ArbitrationResult(enriched, false, "spatial")
        }

        private data class DisambiguationResult(
            val winner: ResidualScorer.CandidateResult,
            val loser: ResidualScorer.CandidateResult,
            val winReason: String,
        )

        private fun disambiguateCloseCompetitors(
            candidate1: ResidualScorer.CandidateResult,
            candidate2: ResidualScorer.CandidateResult,
            geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
            keyPositions: Map<Char, PointF>,
            bigramPredictions: Set<String>,
        ): DisambiguationResult {
            val inflectionScore1 = calculateInflectionAlignment(candidate1.word, geometricAnalysis)
            val inflectionScore2 = calculateInflectionAlignment(candidate2.word, geometricAnalysis)

            val coverageScore1 = candidate1.pathCoverage
            val coverageScore2 = candidate2.pathCoverage

            val freqScore1 = candidate1.frequencyScore
            val freqScore2 = candidate2.frequencyScore

            val gap = abs(candidate1.combinedScore - candidate2.combinedScore)
            val leaderScale = (gap / GeometricScoringConstants.GEOMETRIC_SIMILARITY_THRESHOLD).coerceIn(0f, 1f)
            val originalLeaderBonus1 = if (candidate1.combinedScore > candidate2.combinedScore) 0.02f * leaderScale else 0f
            val originalLeaderBonus2 = if (candidate2.combinedScore > candidate1.combinedScore) 0.02f * leaderScale else 0f

            val c1InBigram = candidate1.word.lowercase() in bigramPredictions
            val c2InBigram = candidate2.word.lowercase() in bigramPredictions

            val bigramBoost1 =
                when {
                    c1InBigram && !c2InBigram -> GeometricScoringConstants.BIGRAM_TIEBREAKER_WEIGHT
                    !c1InBigram && c2InBigram -> -GeometricScoringConstants.BIGRAM_TIEBREAKER_PENALTY
                    else -> 0f
                }
            val bigramBoost2 =
                when {
                    c2InBigram && !c1InBigram -> GeometricScoringConstants.BIGRAM_TIEBREAKER_WEIGHT
                    !c2InBigram && c1InBigram -> -GeometricScoringConstants.BIGRAM_TIEBREAKER_PENALTY
                    else -> 0f
                }

            val razorThin = GeometricScoringConstants.GEOMETRIC_SIMILARITY_THRESHOLD * 0.4f
            val inflectionWeight: Float
            val coverageWeight: Float
            val freqWeight: Float
            when {
                gap < razorThin -> {
                    inflectionWeight = 0.05f
                    coverageWeight = 0.05f
                    freqWeight = 0.80f
                }

                gap < GeometricScoringConstants.GEOMETRIC_SIMILARITY_THRESHOLD -> {
                    inflectionWeight = 0.15f
                    coverageWeight = 0.10f
                    freqWeight =
                        0.50f
                }

                else -> {
                    inflectionWeight = 0.30f
                    coverageWeight = 0.20f
                    freqWeight = 0.10f
                }
            }

            val tiebreaker1 =
                inflectionScore1 * inflectionWeight + coverageScore1 * coverageWeight +
                    freqScore1 * freqWeight + originalLeaderBonus1 + bigramBoost1
            val tiebreaker2 =
                inflectionScore2 * inflectionWeight + coverageScore2 * coverageWeight +
                    freqScore2 * freqWeight + originalLeaderBonus2 + bigramBoost2

            val winner: ResidualScorer.CandidateResult
            val loser: ResidualScorer.CandidateResult
            if (tiebreaker1 >= tiebreaker2) {
                val boostedScore = maxOf(candidate1.combinedScore, candidate2.combinedScore) + 0.001f
                winner = candidate1.copy(combinedScore = boostedScore)
                loser = candidate2
            } else {
                val boostedScore = maxOf(candidate1.combinedScore, candidate2.combinedScore) + 0.001f
                winner = candidate2.copy(combinedScore = boostedScore)
                loser = candidate1
            }

            val winReason =
                determineWinReason(
                    tiebreaker1,
                    tiebreaker2,
                    bigramBoost1,
                    bigramBoost2,
                    originalLeaderBonus1,
                    originalLeaderBonus2,
                    freqWeight,
                    freqScore1,
                    freqScore2,
                    winner.word == candidate1.word,
                )

            return DisambiguationResult(winner, loser, winReason)
        }

        private fun determineWinReason(
            tiebreaker1: Float,
            tiebreaker2: Float,
            bigramBoost1: Float,
            bigramBoost2: Float,
            leaderBonus1: Float,
            leaderBonus2: Float,
            freqWeight: Float,
            freqScore1: Float,
            freqScore2: Float,
            candidate1Won: Boolean,
        ): String {
            if ((candidate1Won && bigramBoost1 > 0f) || (!candidate1Won && bigramBoost2 > 0f)) {
                val withoutBigram1 = tiebreaker1 - bigramBoost1
                val withoutBigram2 = tiebreaker2 - bigramBoost2
                if ((candidate1Won && withoutBigram1 < withoutBigram2) ||
                    (!candidate1Won && withoutBigram2 < withoutBigram1)
                ) {
                    return "bigram"
                }
            }

            if (freqWeight >= 0.50f) {
                val freqContrib1 = freqScore1 * freqWeight
                val freqContrib2 = freqScore2 * freqWeight
                if ((candidate1Won && freqContrib1 > freqContrib2) ||
                    (!candidate1Won && freqContrib2 > freqContrib1)
                ) {
                    return "frequency"
                }
            }

            val activeLeaderBonus = if (candidate1Won) leaderBonus1 else leaderBonus2
            if (activeLeaderBonus > 0f) {
                val margin = abs(tiebreaker1 - tiebreaker2)
                if (margin <= activeLeaderBonus * 1.5f) {
                    return "leader_bonus"
                }
            }

            return "spatial"
        }

        private fun calculateInflectionAlignment(
            word: String,
            geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
        ): Float {
            if (geometricAnalysis.inflectionPoints.isEmpty()) return 0.5f

            var alignedInflections = 0
            var totalRelevantInflections = 0

            for (inflection in geometricAnalysis.inflectionPoints) {
                if (inflection.isIntentional && inflection.nearestKey != null) {
                    totalRelevantInflections++
                    if (word.contains(inflection.nearestKey, ignoreCase = true)) {
                        alignedInflections++
                    }
                }
            }

            return if (totalRelevantInflections > 0) {
                alignedInflections.toFloat() / totalRelevantInflections.toFloat()
            } else {
                0.5f
            }
        }

        private fun enrichWithPrefixCompletions(
            candidates: List<WordCandidate>,
            wordFrequencyMap: Map<String, Int>,
            pathSize: Int,
        ): List<WordCandidate> {
            if (candidates.isEmpty()) return candidates

            val topCandidate = candidates[0]
            if (topCandidate.combinedScore >= GeometricScoringConstants.PREFIX_COMPLETION_SCORE_THRESHOLD) {
                return candidates
            }
            if (topCandidate.word.length < GeometricScoringConstants.PREFIX_COMPLETION_MIN_PREFIX_LENGTH) {
                return candidates
            }

            val prefix = topCandidate.word.lowercase()
            val existingWords = candidates.map { it.word.lowercase() }.toSet()

            val completions =
                wordFrequencyMap.entries
                    .asSequence()
                    .filter { (word, _) ->
                        word.startsWith(prefix) &&
                            word.length >= prefix.length + GeometricScoringConstants.PREFIX_COMPLETION_MIN_EXTENSION_LENGTH &&
                            word.lowercase() !in existingWords
                    }.sortedByDescending { it.value }
                    .take(GeometricScoringConstants.PREFIX_COMPLETION_MAX_RESULTS)
                    .toList()

            if (completions.isEmpty()) return candidates

            val estimatedLetters = pathSize / 4
            val result = ArrayList<WordCandidate>(candidates.size + completions.size)
            result.addAll(candidates)

            for ((word, _) in completions) {
                val pathRatio = estimatedLetters.toFloat() / word.length.toFloat()
                if (pathRatio > GeometricScoringConstants.PREFIX_COMPLETION_PATH_LENGTH_RATIO) continue

                result.add(
                    WordCandidate(
                        word = word,
                        spatialScore = topCandidate.spatialScore * 0.3f,
                        frequencyScore = topCandidate.frequencyScore,
                        combinedScore = topCandidate.combinedScore * 0.3f,
                    ),
                )
            }

            return result
        }
    }
