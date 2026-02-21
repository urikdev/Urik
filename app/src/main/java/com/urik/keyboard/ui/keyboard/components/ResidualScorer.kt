@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import com.urik.keyboard.KeyboardConstants.SwipeDetectionConstants
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Unified per-candidate scoring pipeline for swipe recognition.
 *
 * Computes a residual error (divergence between the swipe signal and a word
 * skeleton) then converts to a combined score. All structural, spatial, and
 * anchor penalties are resolved in a single [scoreCandidate] call.
 */
@Singleton
class ResidualScorer
    @Inject
    constructor(
        private val pathGeometryAnalyzer: PathGeometryAnalyzer,
    ) {
        data class CandidateResult(
            val word: String,
            val residual: Float,
            val spatialScore: Float,
            val frequencyScore: Float,
            val combinedScore: Float,
            val pathCoverage: Float,
            val elasticityApplied: Boolean,
            val dwellBonus: Float,
            val letterPathIndices: List<Int>,
            val isClusteredWord: Boolean,
            val lengthPenalty: Float,
            val traversalPenalty: Float,
            val orderPenalty: Float,
            val vertexLengthPenalty: Float,
            val passthroughPenalty: Float,
        )

        private val reuseLetterPathIndices = ArrayList<Int>(20)
        private val reuseLetterScores = ArrayList<Pair<Char, Float>>(20)
        private val reusableWordLetters = HashSet<Char>(10)

        /**
         * Scores a single dictionary entry against the pre-computed swipe signal.
         *
         * @return scored result, or null if the candidate was pruned by bounds or vertex filters
         */
        @Synchronized
        fun scoreCandidate(
            entry: SwipeDetector.DictionaryEntry,
            signal: SwipeSignal,
            keyPositions: Map<Char, PointF>,
            sigmaCache: Map<Char, PathGeometryAnalyzer.AdaptiveSigma>,
            neighborhoodCache: Map<Char, PathGeometryAnalyzer.KeyNeighborhood>,
            maxFrequencySeen: Long,
        ): CandidateResult? {
            val rawBoundsPenalty = calculateBoundsPenalty(entry.word, signal.charsInBounds)
            val boundsPenalty = if (rawBoundsPenalty == 0f && entry.rawFrequency > 5_000_000L) {
                SwipeDetectionConstants.BOUNDS_LONG_WORD_PENALTY
            } else {
                rawBoundsPenalty
            }
            if (boundsPenalty == 0f) return null

            if (pathGeometryAnalyzer.shouldPruneCandidate(
                    entry.word.length,
                    signal.geometricAnalysis.vertexAnalysis,
                )
            ) {
                return null
            }

            val isClusteredWord = pathGeometryAnalyzer.isClusteredWord(entry.word, keyPositions)

            val pointsPerLetter = signal.path.size.toFloat() / entry.word.length.toFloat()
            val optimalRatio = if (entry.word.length <= 3) 3.0f else 4.0f
            val ratioQuality = pointsPerLetter / optimalRatio

            val spatialScore = calculateGeometricSpatialScore(
                entry.word,
                signal,
                keyPositions,
                entry.uniqueLetterCount,
                ratioQuality,
                isClusteredWord,
                sigmaCache,
                neighborhoodCache,
            )

            val ratioPenalty = calculateRatioPenalty(pointsPerLetter, optimalRatio)
            val adjustedSpatialScore = spatialScore * ratioPenalty

            val frequencyBoost = calculateFrequencyBoost(entry.frequencyTier)
            val boostedFrequencyScore = entry.frequencyScore * frequencyBoost

            val (spatialWeight, frequencyWeight) = determineFinalWeights(
                entry, adjustedSpatialScore, maxFrequencySeen,
                signal.spatialWeight, signal.frequencyWeight, isClusteredWord,
            )

            val pathCoverage = pathGeometryAnalyzer.calculatePathCoverage(
                entry.word, signal.path, keyPositions, reuseLetterPathIndices,
            )

            var orderViolations = 0
            for (j in 0 until reuseLetterPathIndices.size - 1) {
                if (reuseLetterPathIndices[j + 1] < reuseLetterPathIndices[j]) {
                    orderViolations++
                }
            }
            val orderPenalty = when (orderViolations) {
                0 -> 1.0f
                1 -> 0.85f
                2 -> 0.70f
                else -> 0.50f
            }

            val coverageBonus = if (pathCoverage > GeometricScoringConstants.MIN_PATH_COVERAGE_THRESHOLD) {
                1.0f + (pathCoverage - GeometricScoringConstants.MIN_PATH_COVERAGE_THRESHOLD) * 0.25f
            } else {
                0.80f + pathCoverage * 0.35f
            }

            val lengthExcess = maxOf(0, entry.word.length - signal.expectedWordLength)
            val excessRate = if (signal.path.size <= 30) {
                GeometricScoringConstants.WORD_LENGTH_DEFICIT_PENALTY +
                    GeometricScoringConstants.WORD_LENGTH_EXCESS_PENALTY
            } else {
                GeometricScoringConstants.WORD_LENGTH_EXCESS_PENALTY
            }
            val lengthExcessPenalty = 1.0f - (lengthExcess * excessRate)

            val lengthDeficit = maxOf(0, signal.expectedWordLength - entry.word.length)
            val lengthDeficitPenalty = 1.0f - (lengthDeficit * GeometricScoringConstants.WORD_LENGTH_DEFICIT_PENALTY)
            val lengthPenalty = lengthExcessPenalty * lengthDeficitPenalty

            val startAnchor = signal.startAnchor
            val wordFirstChar = entry.word.first().lowercaseChar()
            val startKeyBonus = calculateStartKeyBonus(
                wordFirstChar, startAnchor, signal.path, keyPositions,
            )

            val startDirectionPenalty = calculateStartDirectionPenalty(
                entry.word, signal.path, keyPositions,
            )

            val wordLastChar = entry.word.last().lowercaseChar()
            val endKeyBonus = calculateEndKeyBonus(wordLastChar, signal.endAnchor)

            reusableWordLetters.clear()
            for (c in entry.word) {
                reusableWordLetters.add(c.lowercaseChar())
            }
            var missingLetters = 0
            for (letter in reusableWordLetters) {
                if (letter !in signal.traversedKeys) missingLetters++
            }
            val traversalPenalty = when (missingLetters) {
                0 -> 1.0f
                1 -> 0.75f
                else -> 0.5f
            }

            val vertexLengthPenalty = pathGeometryAnalyzer.calculateVertexLengthPenalty(
                entry.word.length, signal.geometricAnalysis.vertexAnalysis,
            )

            val pathCoherence = pathGeometryAnalyzer.calculatePathCoherenceScore(
                entry.word, signal.path, keyPositions, reuseLetterPathIndices,
            )
            val coherenceSensitivity =
                if (entry.word.length > GeometricScoringConstants.PATH_COHERENCE_MIN_WORD_LENGTH) {
                    GeometricScoringConstants.PATH_COHERENCE_SENSITIVITY +
                        GeometricScoringConstants.PATH_COHERENCE_SENSITIVITY * 0.25f
                } else {
                    GeometricScoringConstants.PATH_COHERENCE_SENSITIVITY
                }
            val pathCoherenceMultiplier =
                (1.0f + (pathCoherence - GeometricScoringConstants.PATH_COHERENCE_NEUTRAL) *
                    coherenceSensitivity)
                    .coerceIn(
                        GeometricScoringConstants.PATH_COHERENCE_MIN_MULTIPLIER,
                        GeometricScoringConstants.PATH_COHERENCE_MAX_MULTIPLIER,
                    )

            val expectedPathLen = calculateExpectedWordPathLength(entry.word, keyPositions)
            val pathLengthMultiplier = calculatePathLengthMultiplier(
                signal.pathLength, expectedPathLen, entry.word.length,
            )
            val pathResidualPenalty = calculatePathResidualPenalty(
                signal.pathLength, expectedPathLen,
            )

            val passthroughPenalty = calculatePassthroughPenalty(entry.word, signal)

            @Suppress("ktlint:standard:max-line-length")
            val combinedScore =
                (adjustedSpatialScore * spatialWeight + boostedFrequencyScore * frequencyWeight) *
                    coverageBonus * lengthPenalty *
                    startKeyBonus * startDirectionPenalty * endKeyBonus *
                    traversalPenalty * orderPenalty * vertexLengthPenalty *
                    pathCoherenceMultiplier * boundsPenalty *
                    pathLengthMultiplier * pathResidualPenalty *
                    passthroughPenalty

            val residual = 1.0f - combinedScore.coerceIn(0f, 1f)

            val elasticityApplied = signal.offRowKeys.isNotEmpty()
            var maxDwellBonus = 0f
            for (i in reuseLetterPathIndices.indices) {
                val idx = reuseLetterPathIndices[i]
                val char = if (i < entry.word.length) entry.word[i].lowercaseChar() else continue
                val boost = pathGeometryAnalyzer.getDwellInterestBoost(char, idx, signal.geometricAnalysis)
                if (boost > maxDwellBonus) maxDwellBonus = boost
            }

            return CandidateResult(
                word = entry.word,
                residual = residual,
                spatialScore = adjustedSpatialScore,
                frequencyScore = entry.frequencyScore,
                combinedScore = combinedScore,
                pathCoverage = pathCoverage,
                elasticityApplied = elasticityApplied,
                dwellBonus = maxDwellBonus,
                letterPathIndices = ArrayList(reuseLetterPathIndices),
                isClusteredWord = isClusteredWord,
                lengthPenalty = lengthPenalty,
                traversalPenalty = traversalPenalty,
                orderPenalty = orderPenalty,
                vertexLengthPenalty = vertexLengthPenalty,
                passthroughPenalty = passthroughPenalty,
            )
        }

        private fun calculateGeometricSpatialScore(
            word: String,
            signal: SwipeSignal,
            keyPositions: Map<Char, PointF>,
            uniqueLetterCount: Int,
            ratioQuality: Float,
            isClusteredWord: Boolean,
            sigmaCache: Map<Char, PathGeometryAnalyzer.AdaptiveSigma>,
            neighborhoodCache: Map<Char, PathGeometryAnalyzer.KeyNeighborhood>,
        ): Float {
            val swipePath = signal.path
            if (swipePath.isEmpty()) return 0f

            reuseLetterPathIndices.clear()
            reuseLetterScores.clear()

            var totalScore = 0f
            val geometricAnalysis = signal.geometricAnalysis

            for (letterIndex in word.indices) {
                val char = word[letterIndex]
                val lowerChar = char.lowercaseChar()
                val keyPos = keyPositions[lowerChar] ?: return 0f

                val isFirstLetter = letterIndex == 0
                val isLastLetter = letterIndex == word.length - 1

                val adaptiveSigma = sigmaCache[lowerChar]?.sigma ?: GeometricScoringConstants.DEFAULT_SIGMA
                val baseSigma = if (isClusteredWord) {
                    adaptiveSigma * GeometricScoringConstants.CLUSTERED_SEQUENCE_TOLERANCE_MULTIPLIER
                } else {
                    adaptiveSigma
                }

                val searchRange = when {
                    isFirstLetter -> (swipePath.size * 0.30).toInt().coerceAtLeast(3)
                    isLastLetter -> swipePath.size - (swipePath.size * 0.30).toInt().coerceAtLeast(swipePath.size - 3)
                    else -> swipePath.size
                }

                var minTotalDistance = Float.MAX_VALUE
                var minDistanceSquared = Float.MAX_VALUE
                var closestPointIndex = -1
                var velocityAtClosest = 0f
                var closestPointX = 0f
                var closestPointY = 0f

                val searchStart = if (isLastLetter) swipePath.size - searchRange else 0
                val searchEnd = if (isFirstLetter) searchRange else swipePath.size

                val expectedPathProgress = if (word.length > 1) {
                    letterIndex.toFloat() / (word.length - 1).toFloat()
                } else {
                    0.5f
                }
                val expectedPathIndex = (expectedPathProgress * (swipePath.size - 1)).toInt()
                val positionPenaltyFactor = if (isClusteredWord) 200f else 150f

                val verticalElasticity = if (lowerChar in signal.offRowKeys) {
                    GeometricScoringConstants.FAST_VELOCITY_DISCOUNT
                } else {
                    1.0f
                }

                for (relativeIndex in 0 until (searchEnd - searchStart)) {
                    val pointIndex = searchStart + relativeIndex
                    val point = swipePath[pointIndex]
                    val dx = keyPos.x - point.x
                    val dy = (keyPos.y - point.y) * verticalElasticity
                    val spatialDistanceSquared =
                        dx * dx + dy * dy * GeometricScoringConstants.VERTICAL_EMPHASIS_FACTOR

                    val positionDeviation = abs(pointIndex - expectedPathIndex).toFloat()
                    val positionPenalty = positionDeviation * positionPenaltyFactor
                    val totalDistance = spatialDistanceSquared + positionPenalty

                    if (totalDistance < minTotalDistance) {
                        minTotalDistance = totalDistance
                        minDistanceSquared = spatialDistanceSquared
                        closestPointIndex = pointIndex
                        velocityAtClosest = point.velocity
                        closestPointX = point.x
                        closestPointY = point.y

                        if (spatialDistanceSquared < 100f && positionDeviation < 2f) {
                            break
                        }
                    }
                }

                if (isFirstLetter && signal.startAnchor.backprojected != null) {
                    val bp = signal.startAnchor.backprojected
                    val bpDx = keyPos.x - bp.x
                    val bpDy = keyPos.y - bp.y
                    val bpDistSq = bpDx * bpDx + bpDy * bpDy * GeometricScoringConstants.VERTICAL_EMPHASIS_FACTOR
                    if (bpDistSq < minDistanceSquared) {
                        minDistanceSquared = bpDistSq
                        closestPointIndex = 0
                        closestPointX = bp.x
                        closestPointY = bp.y
                    }
                }

                val compensation = pathGeometryAnalyzer.getCornerCompensation(closestPointIndex, geometricAnalysis)
                if (compensation != null) {
                    val compDx = keyPos.x - compensation.x
                    val compDy = keyPos.y - compensation.y
                    val compDistSq = compDx * compDx + compDy * compDy * GeometricScoringConstants.VERTICAL_EMPHASIS_FACTOR
                    if (compDistSq < minDistanceSquared) {
                        minDistanceSquared = compDistSq
                    }
                }

                val anchorModifier = pathGeometryAnalyzer.calculateAnchorSigmaModifier(
                    letterIndex, word.length, closestPointIndex, geometricAnalysis,
                )
                val effectiveSigma = baseSigma * anchorModifier
                val twoSigmaSquared = 2f * effectiveSigma * effectiveSigma
                val expThreshold = (2.5f * effectiveSigma) * (2.5f * effectiveSigma)

                var letterScore = if (minDistanceSquared > expThreshold) {
                    0.0f
                } else {
                    exp(-minDistanceSquared / twoSigmaSquared)
                }

                if (letterScore < GeometricScoringConstants.NEIGHBORHOOD_RESCUE_THRESHOLD) {
                    val neighborhood = neighborhoodCache[lowerChar]
                    if (neighborhood != null) {
                        val rescueScore = pathGeometryAnalyzer.calculateNeighborhoodRescueScore(
                            closestPointX, closestPointY, neighborhood, keyPositions, effectiveSigma,
                        )
                        letterScore = maxOf(letterScore, rescueScore)
                    }
                }

                val velocityWeight = pathGeometryAnalyzer.calculateVelocityWeight(velocityAtClosest)
                letterScore *= velocityWeight

                val curvatureBoost = pathGeometryAnalyzer.getVertexCurvatureBoost(
                    lowerChar, closestPointIndex, keyPos, geometricAnalysis,
                )
                letterScore *= curvatureBoost

                val dwellBoost = pathGeometryAnalyzer.getDwellInterestBoost(lowerChar, closestPointIndex, geometricAnalysis)
                letterScore *= dwellBoost

                if (swipePath.size <= GeometricScoringConstants.VERTEX_FILTER_MIN_PATH_POINTS) {
                    val velocityDwellBoost = pathGeometryAnalyzer.getVelocityDwellBoost(closestPointIndex, geometricAnalysis)
                    letterScore *= velocityDwellBoost
                }

                if (pathGeometryAnalyzer.didPathTraverseKey(lowerChar, geometricAnalysis) &&
                    lowerChar !in signal.passthroughKeys
                ) {
                    letterScore = maxOf(letterScore, GeometricScoringConstants.TRAVERSAL_FLOOR_SCORE)
                }

                if (letterIndex > 0 && word[letterIndex] == word[letterIndex - 1]) {
                    val repeatedLetterBoost = pathGeometryAnalyzer.detectRepeatedLetterSignal(
                        swipePath, keyPos, reuseLetterPathIndices.lastOrNull() ?: 0, closestPointIndex,
                    )
                    letterScore *= (1f + repeatedLetterBoost)
                }

                reuseLetterPathIndices.add(closestPointIndex)
                reuseLetterScores.add(char to letterScore)
                totalScore += letterScore
            }

            val lexicalCoherenceBonus = pathGeometryAnalyzer.calculateLexicalCoherenceBonus(reuseLetterScores)

            val sequencePenalty = calculateSequencePenalty(
                word, reuseLetterPathIndices, uniqueLetterCount, isClusteredWord,
            )

            val baseSpatialScore = totalScore / word.length.toFloat()

            val rawWrongLetterPenalty = calculateWrongLetterPenalty(reuseLetterScores, word.length)
            val wrongLetterPenalty = if (word.length >= GeometricScoringConstants.ANCHOR_KEY_MIN_WORD_LENGTH) {
                val highProximityCount = reuseLetterScores.count { it.second >= GeometricScoringConstants.ANCHOR_KEY_HIGH_PROXIMITY_THRESHOLD }
                val anchorRatio = highProximityCount.toFloat() / word.length.toFloat()
                if (anchorRatio >= GeometricScoringConstants.ANCHOR_KEY_COVERAGE_RATIO) {
                    maxOf(rawWrongLetterPenalty, GeometricScoringConstants.ANCHOR_KEY_PROTECTED_FLOOR)
                } else {
                    rawWrongLetterPenalty
                }
            } else {
                rawWrongLetterPenalty
            }

            val pathExhaustionPenalty = calculatePathExhaustionPenalty(
                word, reuseLetterPathIndices, signal.path.size,
            )

            val lengthBonus = calculateLengthBonus(word.length, ratioQuality)

            val spatialWithBonuses =
                (baseSpatialScore * sequencePenalty * lengthBonus * wrongLetterPenalty * pathExhaustionPenalty * lexicalCoherenceBonus)
                    .coerceAtMost(1.0f)

            val repetitionCount = word.length - uniqueLetterCount
            val repetitionRatio = repetitionCount.toFloat() / word.length.toFloat()
            val repetitionPenalty = if (repetitionRatio > 0.30f) {
                1.0f - ((repetitionCount - 1) * SwipeDetectionConstants.REPETITION_PENALTY_FACTOR).coerceAtMost(0.20f)
            } else {
                1.0f
            }

            return spatialWithBonuses * repetitionPenalty
        }

        private fun calculateStartKeyBonus(
            wordFirstChar: Char,
            startAnchor: SwipeSignal.StartAnchor,
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
        ): Float {
            val matches = wordFirstChar == startAnchor.closestKey ||
                wordFirstChar == startAnchor.pointZeroNearest ||
                (startAnchor.isAmbiguous && wordFirstChar == startAnchor.pointZeroSecond)

            if (matches) {
                return if (wordFirstChar == startAnchor.pointZeroNearest && startAnchor.isAnchorLocked) {
                    GeometricScoringConstants.END_KEY_MATCH_BONUS
                } else {
                    GeometricScoringConstants.START_KEY_MATCH_BONUS
                }
            }

            if (startAnchor.isAnchorLocked && wordFirstChar != startAnchor.pointZeroNearest) {
                val distToWordStart = startAnchor.keyDistances[wordFirstChar] ?: Float.MAX_VALUE
                val distToClosest = startAnchor.keyDistances[startAnchor.closestKey] ?: 1f
                val distanceRatio = (distToWordStart / distToClosest.coerceAtLeast(1f)).coerceAtMost(3f)
                return 1.0f / (1.0f + (distanceRatio - 1.0f) * GeometricScoringConstants.END_KEY_DISTANCE_PENALTY_FACTOR * 2f)
            }

            val distToWordStart = startAnchor.keyDistances[wordFirstChar] ?: Float.MAX_VALUE
            val distToClosest = startAnchor.keyDistances[startAnchor.closestKey] ?: 1f
            val distanceRatio = (distToWordStart / distToClosest.coerceAtLeast(1f)).coerceAtMost(3f)
            return 1.0f / (1.0f + (distanceRatio - 1.0f) * GeometricScoringConstants.START_KEY_DISTANCE_PENALTY_FACTOR)
        }

        private fun calculateStartDirectionPenalty(
            word: String,
            path: List<SwipeDetector.SwipePoint>,
            keyPositions: Map<Char, PointF>,
        ): Float {
            if (word.length < 2 || path.size < 5) return 1.0f

            val wordFirstChar = word[0].lowercaseChar()
            val secondCharPos = keyPositions[word[1].lowercaseChar()] ?: return 1.0f
            val firstCharPos = keyPositions[wordFirstChar] ?: return 1.0f

            val expectedDx = secondCharPos.x - firstCharPos.x
            val expectedDy = secondCharPos.y - firstCharPos.y
            val earlyIdx = minOf(4, path.size - 1)
            val actualDx = path[earlyIdx].x - path[0].x
            val actualDy = path[earlyIdx].y - path[0].y
            val dot = expectedDx * actualDx + expectedDy * actualDy

            return if (dot < 0f) GeometricScoringConstants.FAST_VELOCITY_DISCOUNT else 1.0f
        }

        private fun calculateEndKeyBonus(
            wordLastChar: Char,
            endAnchor: SwipeSignal.EndAnchor,
        ): Float {
            if (wordLastChar == endAnchor.closestKey) {
                return GeometricScoringConstants.END_KEY_MATCH_BONUS
            }
            val distToWordEnd = endAnchor.keyDistances[wordLastChar] ?: Float.MAX_VALUE
            val distToClosestEnd = endAnchor.keyDistances[endAnchor.closestKey] ?: 1f
            val distanceRatio = (distToWordEnd / distToClosestEnd.coerceAtLeast(1f)).coerceAtMost(3f)
            return 1.0f / (1.0f + (distanceRatio - 1.0f) * GeometricScoringConstants.END_KEY_DISTANCE_PENALTY_FACTOR)
        }

        private fun calculateSequencePenalty(
            word: String,
            letterPathIndices: ArrayList<Int>,
            uniqueLetterCount: Int,
            isClusteredWord: Boolean,
        ): Float {
            var sequenceViolations = 0
            for (i in 1 until letterPathIndices.size) {
                val currentIndex = letterPathIndices[i]
                val previousIndex = letterPathIndices[i - 1]
                val isRepeatedLetter = i < word.length && word[i] == word[i - 1]
                val indexAdvancement = currentIndex - previousIndex
                if (isRepeatedLetter) {
                    if (indexAdvancement > SwipeDetectionConstants.REPEATED_LETTER_MAX_INDEX_GAP || indexAdvancement < 1) {
                        sequenceViolations++
                    }
                } else {
                    if (currentIndex < previousIndex) {
                        sequenceViolations++
                    }
                }
            }
            val baseTolerableViolations = when {
                word.length <= 4 -> 0
                word.length <= 6 -> 1
                else -> 1
            }
            val adjustedTolerance = if (isClusteredWord) {
                (baseTolerableViolations * GeometricScoringConstants.CLUSTERED_SEQUENCE_TOLERANCE_MULTIPLIER).toInt()
            } else {
                val repetitionCount = word.length - uniqueLetterCount
                val repetitionPenaltyFactor = if (word.length >= 6) 0 else 1
                baseTolerableViolations + (repetitionCount * repetitionPenaltyFactor)
            }
            return when {
                sequenceViolations <= adjustedTolerance -> 1.0f
                sequenceViolations == adjustedTolerance + 1 -> 0.92f
                sequenceViolations == adjustedTolerance + 2 -> 0.80f
                else -> 0.65f
            }
        }

        private fun calculateWrongLetterPenalty(
            letterScores: ArrayList<Pair<Char, Float>>,
            wordLength: Int,
        ): Float {
            val badLetterCount = letterScores.count { it.second < 0.30f }
            val veryBadLetterCount = letterScores.count { it.second < 0.15f }
            return when {
                veryBadLetterCount > 0 -> 0.40f
                badLetterCount >= 2 && wordLength <= 4 -> 0.45f
                badLetterCount >= 1 && wordLength == 2 -> 0.35f
                badLetterCount >= 1 && wordLength == 3 -> 0.50f
                badLetterCount >= 1 && wordLength >= 4 -> 0.70f
                else -> 1.0f
            }
        }

        private fun calculatePathExhaustionPenalty(
            word: String,
            letterPathIndices: ArrayList<Int>,
            pathSize: Int,
        ): Float {
            if (word.length < SwipeDetectionConstants.PATH_EXHAUSTION_MIN_WORD_LENGTH || letterPathIndices.isEmpty()) {
                return 1.0f
            }
            val lastQuartileThreshold = (pathSize * SwipeDetectionConstants.PATH_EXHAUSTION_QUARTILE_THRESHOLD).toInt()
            val tailLetterCount =
                (word.length * SwipeDetectionConstants.PATH_EXHAUSTION_TAIL_RATIO)
                    .toInt()
                    .coerceAtLeast(SwipeDetectionConstants.PATH_EXHAUSTION_MIN_LETTERS_CHECK)
            val startIndex = letterPathIndices.size - tailLetterCount
            var lettersInLastQuartile = 0
            for (i in startIndex until letterPathIndices.size) {
                if (letterPathIndices[i] >= lastQuartileThreshold) {
                    lettersInLastQuartile++
                }
            }
            return when {
                lettersInLastQuartile >= 3 -> 0.60f
                lettersInLastQuartile == 2 -> 0.80f
                else -> 1.0f
            }
        }

        private fun calculateLengthBonus(
            wordLength: Int,
            ratioQuality: Float,
        ): Float {
            if (ratioQuality < SwipeDetectionConstants.LENGTH_BONUS_MIN_RATIO_QUALITY) return 1.0f
            return when {
                wordLength >= 8 -> 1.25f
                wordLength == 7 -> 1.18f
                wordLength == 6 -> 1.12f
                wordLength == 5 -> 1.06f
                else -> 1.0f
            }
        }

        private fun calculateBoundsPenalty(
            word: String,
            charsInBounds: Set<Char>,
        ): Float {
            var charsInBoundsCount = 0
            for (char in word) {
                if (char.lowercaseChar() in charsInBounds) charsInBoundsCount++
            }
            val boundsRatio = charsInBoundsCount.toFloat() / word.length.toFloat()
            val minThreshold = if (word.length > SwipeDetectionConstants.BOUNDS_LONG_WORD_LENGTH_THRESHOLD) {
                SwipeDetectionConstants.BOUNDS_LONG_WORD_MINIMUM_COVERAGE
            } else {
                SwipeDetectionConstants.BOUNDS_MINIMUM_COVERAGE_THRESHOLD
            }
            return when {
                boundsRatio >= SwipeDetectionConstants.BOUNDS_FULL_COVERAGE_THRESHOLD -> 1.0f
                boundsRatio >= SwipeDetectionConstants.BOUNDS_PARTIAL_COVERAGE_THRESHOLD -> SwipeDetectionConstants.BOUNDS_PARTIAL_PENALTY
                boundsRatio >= SwipeDetectionConstants.BOUNDS_MINIMUM_COVERAGE_THRESHOLD -> SwipeDetectionConstants.BOUNDS_LOW_PENALTY
                boundsRatio >= minThreshold -> SwipeDetectionConstants.BOUNDS_LONG_WORD_PENALTY
                else -> 0f
            }
        }

        private fun calculateRatioPenalty(
            pointsPerLetter: Float,
            optimalRatio: Float,
        ): Float = when {
            pointsPerLetter < optimalRatio * 0.50f -> 0.50f
            pointsPerLetter < optimalRatio * 0.65f -> 0.60f
            pointsPerLetter < optimalRatio * 0.75f -> 0.75f
            pointsPerLetter < optimalRatio * 0.85f -> 0.90f
            pointsPerLetter > optimalRatio * 2.00f -> 0.60f
            pointsPerLetter > optimalRatio * 1.60f -> 0.75f
            pointsPerLetter > optimalRatio * 1.40f -> 0.85f
            else -> 1.0f
        }

        private fun calculateFrequencyBoost(tier: SwipeDetector.FrequencyTier): Float = when (tier) {
            SwipeDetector.FrequencyTier.TOP_100 -> GeometricScoringConstants.FREQ_TIER_TOP100_BOOST
            SwipeDetector.FrequencyTier.TOP_1000 -> GeometricScoringConstants.FREQ_TIER_TOP1000_BOOST
            SwipeDetector.FrequencyTier.TOP_5000 -> GeometricScoringConstants.FREQ_TIER_TOP5000_BOOST
            SwipeDetector.FrequencyTier.COMMON -> 1.0f
        }

        private fun determineFinalWeights(
            entry: SwipeDetector.DictionaryEntry,
            adjustedSpatialScore: Float,
            maxFrequencySeen: Long,
            baselineSpatialWeight: Float,
            baselineFreqWeight: Float,
            isClusteredWord: Boolean,
        ): Pair<Float, Float> {
            if (isClusteredWord) {
                return GeometricScoringConstants.CLUSTERED_WORD_SPATIAL_WEIGHT to
                    GeometricScoringConstants.CLUSTERED_WORD_FREQ_WEIGHT
            }
            val frequencyRatio = if (maxFrequencySeen > 0) {
                entry.rawFrequency.toFloat() / maxFrequencySeen.toFloat()
            } else {
                1.0f
            }
            return when {
                entry.word.length == 2 && adjustedSpatialScore > 0.75f -> 0.88f to 0.12f
                frequencyRatio >= 10.0f -> minOf(baselineSpatialWeight, 0.50f) to maxOf(baselineFreqWeight, 0.50f)
                frequencyRatio >= 5.0f -> minOf(baselineSpatialWeight, 0.55f) to maxOf(baselineFreqWeight, 0.45f)
                frequencyRatio >= 3.0f -> minOf(baselineSpatialWeight, 0.58f) to maxOf(baselineFreqWeight, 0.42f)
                else -> baselineSpatialWeight to baselineFreqWeight
            }
        }

        private fun calculateExpectedWordPathLength(
            word: String,
            keyPositions: Map<Char, PointF>,
        ): Float {
            var totalLength = 0f
            var prevPos: PointF? = null
            var prevChar = '\u0000'
            for (char in word) {
                val lowerChar = char.lowercaseChar()
                if (lowerChar == prevChar) continue
                val pos = keyPositions[lowerChar] ?: continue
                if (prevPos != null) {
                    val dx = pos.x - prevPos.x
                    val dy = pos.y - prevPos.y
                    totalLength += sqrt(dx * dx + dy * dy)
                }
                prevPos = pos
                prevChar = lowerChar
            }
            return totalLength
        }

        private fun calculatePathLengthMultiplier(
            physicalPathLength: Float,
            expectedWordPathLength: Float,
            wordLength: Int,
        ): Float {
            if (wordLength < SwipeDetectionConstants.PATH_LENGTH_RATIO_MIN_WORD_LENGTH) return 1.0f
            if (expectedWordPathLength < SwipeDetectionConstants.PATH_LENGTH_RATIO_MIN_EXPECTED_PX) return 1.0f
            val ratio = physicalPathLength / expectedWordPathLength
            val sigma = SwipeDetectionConstants.PATH_LENGTH_RATIO_SIGMA
            val deviation = ratio - 1.0f
            val score = exp(-(deviation * deviation) / (2f * sigma * sigma))
            return SwipeDetectionConstants.PATH_LENGTH_RATIO_MIN_MULTIPLIER +
                (1.0f - SwipeDetectionConstants.PATH_LENGTH_RATIO_MIN_MULTIPLIER) * score
        }

        private fun calculatePathResidualPenalty(
            physicalPathLength: Float,
            expectedWordPathLength: Float,
        ): Float {
            if (expectedWordPathLength < SwipeDetectionConstants.PATH_RESIDUAL_MIN_EXPECTED_PX) return 1.0f
            val excessRatio = physicalPathLength / expectedWordPathLength
            if (excessRatio <= SwipeDetectionConstants.PATH_RESIDUAL_ACTIVATION_RATIO) return 1.0f
            return when {
                excessRatio > 3.0f -> SwipeDetectionConstants.PATH_RESIDUAL_SEVERE_PENALTY
                excessRatio > 2.5f -> SwipeDetectionConstants.PATH_RESIDUAL_HEAVY_PENALTY
                excessRatio > 2.0f -> SwipeDetectionConstants.PATH_RESIDUAL_MODERATE_PENALTY
                else -> SwipeDetectionConstants.PATH_RESIDUAL_MILD_PENALTY
            }
        }

        private fun calculatePassthroughPenalty(word: String, signal: SwipeSignal): Float {
            if (signal.passthroughKeys.isEmpty()) return 1.0f
            val intentionalKeys = signal.traversedKeys - signal.passthroughKeys
            var passthroughOnlyCount = 0
            val seen = HashSet<Char>(word.length)
            for (char in word) {
                val lc = char.lowercaseChar()
                if (!seen.add(lc)) continue
                if (lc in signal.passthroughKeys && lc !in intentionalKeys) {
                    passthroughOnlyCount++
                }
            }
            return when (passthroughOnlyCount) {
                0 -> 1.0f
                1 -> GeometricScoringConstants.PASSTHROUGH_PENALTY_ONE
                2 -> GeometricScoringConstants.PASSTHROUGH_PENALTY_TWO
                else -> GeometricScoringConstants.PASSTHROUGH_PENALTY_THREE_PLUS
            }
        }
    }
