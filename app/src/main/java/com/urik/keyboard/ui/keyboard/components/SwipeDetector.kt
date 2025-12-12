@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.MotionEvent
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.KeyboardConstants.SwipeDetectionConstants
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.SpellCheckManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Word candidate with scoring metrics.
 */
data class WordCandidate(
    val word: String,
    val spatialScore: Float,
    val frequencyScore: Float,
    val combinedScore: Float,
)

/**
 * Detects swipe gestures and converts to word candidates.
 *
 */
@Singleton
class SwipeDetector
    @Inject
    constructor(
        private val spellCheckManager: SpellCheckManager,
    ) {
        /**
         * Captured swipe point with metadata.
         */
        data class SwipePoint(
            val x: Float,
            val y: Float,
            val timestamp: Long,
            val pressure: Float = 1.0f,
            val velocity: Float = 0.0f,
        )

        /**
         * Complete swipe path with context.
         */
        data class SwipePath(
            val points: List<SwipePoint>,
            val keysTraversed: List<KeyboardKey.Character>,
            val scriptCode: Int,
            val isRtl: Boolean = false,
            val topCandidates: List<WordCandidate>,
        )

        /**
         * Callback interface for swipe events.
         */
        interface SwipeListener {
            fun onSwipeStart(startPoint: PointF)

            fun onSwipeUpdate(
                currentPath: SwipePath,
                currentPoint: PointF,
            )

            fun onSwipeEnd(finalPath: SwipePath)

            fun onSwipeResults(candidates: List<WordCandidate>)

            fun onTap(key: KeyboardKey)
        }

        private var swipeListener: SwipeListener? = null

        private var lastUpdateTime = 0L
        private var isSwiping = false
        private var swipePoints = mutableListOf<SwipePoint>()
        private var startTime = 0L
        private var pointCounter = 0
        private var firstPoint: SwipePoint? = null

        private var currentLocale: ULocale? = null

        @Volatile
        private var swipeEnabled = true

        @Volatile
        private var swipeStartDistancePx = 50f

        @Volatile
        private var keyCharacterPositions = emptyMap<Char, PointF>()

        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(Dispatchers.Default + scopeJob)
        private var scoringJob: Job? = null

        data class DictionaryEntry(
            val word: String,
            val frequencyScore: Float,
            val rawFrequency: Long,
            val firstChar: Char,
            val uniqueLetterCount: Int,
        )

        /**
         * Updates key position mapping for spatial scoring.
         *
         * Call when keyboard layout changes (mode switch, language change).
         */
        fun updateKeyPositions(positions: Map<KeyboardKey.Character, PointF>) {
            val newMap = mutableMapOf<Char, PointF>()
            positions.forEach { (key, pos) ->
                if (key.value.isNotEmpty()) {
                    newMap[key.value.first()] = pos
                }
            }
            keyCharacterPositions = newMap
        }

        /**
         * Updates script context when language changes.
         */
        fun updateScriptContext(locale: ULocale) {
            currentLocale = locale
        }

        /**
         * Enables or disables swipe typing.
         */
        fun setSwipeEnabled(enabled: Boolean) {
            swipeEnabled = enabled
            if (!enabled) {
                reset()
            }
        }

        /**
         * Updates swipe distance threshold based on screen density.
         */
        fun updateDisplayMetrics(density: Float) {
            swipeStartDistancePx = SwipeDetectionConstants.SWIPE_START_DISTANCE_DP * density
        }

        fun setSwipeListener(listener: SwipeListener?) {
            this.swipeListener = listener
        }

        /**
         * Processes touch events for swipe detection.
         *
         * @return true if event consumed (swipe in progress), false if should propagate
         */
        fun handleTouchEvent(
            event: MotionEvent,
            keyAt: (Float, Float) -> KeyboardKey?,
        ): Boolean {
            if (!swipeEnabled) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startTime = System.currentTimeMillis()
                        return false
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startTime
                        if (duration > 0 && duration <= SwipeDetectionConstants.TAP_DURATION_THRESHOLD_MS) {
                            val tappedKey = keyAt(event.x, event.y)
                            if (tappedKey != null) {
                                swipeListener?.onTap(tappedKey)
                                reset()
                                return true
                            }
                        }
                        reset()
                        return false
                    }
                    else -> return false
                }
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.pointerCount > 1) {
                        reset()
                        return false
                    }
                    val touchedKey = keyAt(event.x, event.y)
                    if (touchedKey !is KeyboardKey.Character) {
                        reset()
                        return false
                    }
                    startSwipeDetection(event)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isSwiping) {
                        updateSwipePath(event)
                        return true
                    } else {
                        checkForSwipeStart(event)
                        return isSwiping
                    }
                }

                MotionEvent.ACTION_UP -> {
                    return endSwipeDetection(event, keyAt)
                }

                MotionEvent.ACTION_CANCEL -> {
                    reset()
                    return false
                }
            }
            return false
        }

        private fun startSwipeDetection(event: MotionEvent) {
            scoringJob?.cancel()
            reset()
            startTime = System.currentTimeMillis()
            pointCounter = 0

            val point =
                SwipePoint(
                    x = event.x,
                    y = event.y,
                    timestamp = startTime,
                    pressure = event.pressure,
                    velocity = 0.0f,
                )
            firstPoint = point
            swipePoints.add(point)
        }

        private fun checkForSwipeStart(event: MotionEvent) {
            firstPoint?.let { start ->
                val timeSinceDown = System.currentTimeMillis() - startTime
                if (timeSinceDown < SwipeDetectionConstants.SWIPE_TIME_THRESHOLD_MS) {
                    return
                }

                val distance = calculateDistance(start.x, start.y, event.x, event.y)
                if (distance > swipeStartDistancePx) {
                    isSwiping = true
                    swipeListener?.onSwipeStart(PointF(start.x, start.y))
                    updateSwipePath(event)
                }
            }
        }

        private fun shouldSamplePoint(
            newPoint: SwipePoint,
            counter: Int,
            velocity: Float,
        ): Boolean {
            if (swipePoints.size < SwipeDetectionConstants.MIN_SWIPE_POINTS_FOR_SAMPLING) return true

            val lastPoint = swipePoints.lastOrNull() ?: return true

            val dx = newPoint.x - lastPoint.x
            val dy = newPoint.y - lastPoint.y
            val distanceSquared = dx * dx + dy * dy

            if (distanceSquared < SwipeDetectionConstants.MIN_POINT_DISTANCE * SwipeDetectionConstants.MIN_POINT_DISTANCE) return false

            val samplingInterval =
                when {
                    swipePoints.size < SwipeDetectionConstants.ADAPTIVE_THRESHOLD -> {
                        SwipeDetectionConstants.MIN_SAMPLING_INTERVAL
                    }

                    swipePoints.size < SwipeDetectionConstants.MAX_SWIPE_POINTS * SwipeDetectionConstants.ADAPTIVE_THRESHOLD_RATIO -> {
                        SwipeDetectionConstants.MIN_SAMPLING_INTERVAL +
                            2
                    }

                    else -> {
                        SwipeDetectionConstants.MAX_SAMPLING_INTERVAL
                    }
                }

            if (counter % samplingInterval != 0) return false

            val isSlowPreciseMovement = velocity < SwipeDetectionConstants.SLOW_MOVEMENT_VELOCITY_THRESHOLD && swipePoints.size > 10
            if (isSlowPreciseMovement) {
                return counter % SwipeDetectionConstants.MIN_SAMPLING_INTERVAL == 0
            }

            return true
        }

        private fun updateSwipePath(event: MotionEvent) {
            pointCounter++

            val velocity = calculateVelocity(event)
            val newPoint =
                SwipePoint(
                    x = event.x,
                    y = event.y,
                    timestamp = System.currentTimeMillis(),
                    pressure = event.pressure,
                    velocity = velocity,
                )

            val shouldAddPoint = shouldSamplePoint(newPoint, pointCounter, velocity)

            if (shouldAddPoint && swipePoints.size < SwipeDetectionConstants.MAX_SWIPE_POINTS) {
                swipePoints.add(newPoint)
            }

            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= SwipeDetectionConstants.UI_UPDATE_INTERVAL_MS) {
                lastUpdateTime = now

                val currentPath =
                    SwipePath(
                        points = swipePoints.toList(),
                        keysTraversed = emptyList(),
                        scriptCode = UScript.LATIN,
                        isRtl = false,
                        topCandidates = emptyList(),
                    )

                swipeListener?.onSwipeUpdate(currentPath, PointF(event.x, event.y))
            }
        }

        private fun endSwipeDetection(
            event: MotionEvent,
            keyAt: (Float, Float) -> KeyboardKey?,
        ): Boolean {
            if (isSwiping) {
                val finalPoint =
                    SwipePoint(
                        x = event.x,
                        y = event.y,
                        timestamp = System.currentTimeMillis(),
                        pressure = event.pressure,
                        velocity = calculateVelocity(event),
                    )
                swipePoints.add(finalPoint)

                val preliminaryPath =
                    SwipePath(
                        points = swipePoints.toList(),
                        keysTraversed = emptyList(),
                        scriptCode = UScript.LATIN,
                        isRtl = false,
                        topCandidates = emptyList(),
                    )

                swipeListener?.onSwipeEnd(preliminaryPath)

                val pathSnapshot = swipePoints.toList()

                scoringJob =
                    scope.launch {
                        try {
                            val topCandidates = performSpatialScoringAsync(pathSnapshot)

                            withContext(Dispatchers.Main) {
                                swipeListener?.onSwipeResults(topCandidates)
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                swipeListener?.onSwipeResults(emptyList())
                            }
                        }
                    }

                reset()
                return true
            } else {
                val duration = System.currentTimeMillis() - startTime
                if (duration <= SwipeDetectionConstants.TAP_DURATION_THRESHOLD_MS) {
                    val tappedKey = keyAt(event.x, event.y)
                    if (tappedKey != null) {
                        swipeListener?.onTap(tappedKey)
                        reset()
                        return true
                    }
                }
                reset()
                return false
            }
        }

        private suspend fun performSpatialScoringAsync(swipePath: List<SwipePoint>): List<WordCandidate> =
            withContext(Dispatchers.IO) {
                try {
                    if (swipePath.isEmpty()) return@withContext emptyList()

                    val keyPositionsSnapshot = keyCharacterPositions
                    val rawWords = spellCheckManager.getCommonWords()

                    if (keyPositionsSnapshot.isEmpty() || rawWords.isEmpty()) {
                        return@withContext emptyList()
                    }

                    val minLength = (swipePath.size / 7).coerceAtLeast(2)
                    val maxLength = (swipePath.size).coerceAtMost(20)

                    val velocities = swipePath.map { it.velocity }
                    val avgVelocity = if (velocities.isNotEmpty()) velocities.average() else 0.0
                    val maxVelocity = velocities.maxOrNull() ?: 0f

                    val durationMs =
                        if (swipePath.size >= 2) {
                            swipePath.last().timestamp - swipePath.first().timestamp
                        } else {
                            0L
                        }

                    val dictionaryByFirstChar =
                        rawWords
                            .filter { (word, _) -> word.length in minLength..maxLength }
                            .map { (word, frequency) ->
                                DictionaryEntry(
                                    word = word,
                                    frequencyScore = ln(frequency.toFloat() + 1f) / 20f,
                                    rawFrequency = frequency.toLong(),
                                    firstChar = word.first().lowercaseChar(),
                                    uniqueLetterCount = word.toSet().size,
                                )
                            }.groupBy { it.firstChar }

                    val candidatesMap = mutableMapOf<String, WordCandidate>()
                    val pathBounds = calculatePathBounds(swipePath)

                    val margin = SwipeDetectionConstants.PATH_BOUNDS_MARGIN_PX
                    val charsInBounds =
                        keyPositionsSnapshot.keys
                            .filter { char ->
                                val pos = keyPositionsSnapshot[char]!!
                                pos.x >= pathBounds.minX - margin &&
                                    pos.x <= pathBounds.maxX + margin &&
                                    pos.y >= pathBounds.minY - margin &&
                                    pos.y <= pathBounds.maxY + margin
                            }.toSet()

                    val candidateStartKeys =
                        swipePath.firstOrNull()?.let { firstPoint ->
                            val keysWithDist =
                                keyPositionsSnapshot.entries
                                    .map { (char, pos) ->
                                        val dx = pos.x - firstPoint.x
                                        val dy = pos.y - firstPoint.y
                                        val distSq = dx * dx + dy * dy
                                        Triple(char, distSq, kotlin.math.sqrt(distSq).toInt())
                                    }.sortedBy { it.second }
                                    .take(8)
                                    .filter { it.second < SwipeDetectionConstants.CLOSE_KEY_DISTANCE_THRESHOLD_SQ }

                            keysWithDist.map { it.first }
                        } ?: emptyList()

                    val relevantChars =
                        if (candidateStartKeys.isNotEmpty()) {
                            candidateStartKeys.toSet()
                        } else {
                            dictionaryByFirstChar.keys
                        }

                    val dictionarySnapshot =
                        relevantChars.flatMap { char ->
                            dictionaryByFirstChar[char] ?: emptyList()
                        }

                    val wordsToCheck = dictionarySnapshot.size

                    for (i in 0 until wordsToCheck) {
                        if (i % 50 == 0) {
                            yield()
                        }

                        val entry = dictionarySnapshot[i]

                        if (!couldMatchPath(entry.word, charsInBounds)) {
                            continue
                        }
                        val spatialScore =
                            calculateSpatialScore(
                                entry.word,
                                swipePath,
                                keyPositionsSnapshot,
                                entry.uniqueLetterCount,
                            )

                        val pointsPerLetter = swipePath.size.toFloat() / entry.word.length.toFloat()
                        val optimalRatio = if (entry.word.length <= 3) 3.0f else 4.0f
                        val ratioPenalty =
                            when {
                                pointsPerLetter < optimalRatio * 0.70f -> 0.75f
                                pointsPerLetter < optimalRatio * 0.75f -> 0.85f
                                pointsPerLetter > optimalRatio * 2.00f -> 0.60f
                                pointsPerLetter > optimalRatio * 1.60f -> 0.75f
                                pointsPerLetter > optimalRatio * 1.40f -> 0.85f
                                else -> 1.0f
                            }

                        val adjustedSpatialScore = spatialScore * ratioPenalty

                        val frequencyBoost =
                            when {
                                entry.rawFrequency > 10_000_000L -> 1.20f
                                entry.rawFrequency > 5_000_000L -> 1.15f
                                entry.rawFrequency > 2_000_000L -> 1.10f
                                else -> 1.0f
                            }
                        val boostedFrequencyScore = entry.frequencyScore * frequencyBoost

                        val combinedScore =
                            adjustedSpatialScore * SwipeDetectionConstants.SPATIAL_SCORE_WEIGHT +
                                boostedFrequencyScore * SwipeDetectionConstants.FREQUENCY_SCORE_WEIGHT

                        val candidate = WordCandidate(entry.word, adjustedSpatialScore, entry.frequencyScore, combinedScore)

                        val existing = candidatesMap[entry.word]
                        if (existing == null || combinedScore > existing.combinedScore) {
                            candidatesMap[entry.word] = candidate
                        }

                        if (combinedScore > SwipeDetectionConstants.EXCELLENT_CANDIDATE_THRESHOLD) {
                            val excellentCandidates = candidatesMap.values.count { it.combinedScore > 0.90f }
                            if (excellentCandidates >= SwipeDetectionConstants.MIN_EXCELLENT_CANDIDATES) {
                                break
                            }
                        }
                    }

                    val topCandidates = candidatesMap.values.sortedByDescending { it.combinedScore }.take(3)

                    return@withContext topCandidates
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }

        private fun calculateSpatialScore(
            word: String,
            swipePath: List<SwipePoint>,
            keyPositions: Map<Char, PointF>,
            uniqueLetterCount: Int,
        ): Float {
            if (swipePath.isEmpty()) return 0f

            var totalScore = 0f

            val expThresh50 = SwipeDetectionConstants.EXP_THRESHOLD_50
            val twoSigma50Sq = SwipeDetectionConstants.TWO_SIGMA_50_SQ

            val expThresh60 = SwipeDetectionConstants.EXP_THRESHOLD_60
            val twoSigma60Sq = SwipeDetectionConstants.TWO_SIGMA_60_SQ

            val letterPathIndices = mutableListOf<Int>()
            val letterScores = mutableListOf<Pair<Char, Float>>()

            word.forEachIndexed { letterIndex, char ->
                val keyPos = keyPositions[char.lowercaseChar()] ?: return 0f

                val isFirstLetter = letterIndex == 0
                val isLastLetter = letterIndex == word.length - 1
                val expThreshold = if (isLastLetter) expThresh60 else expThresh50
                val twoSigmaSquared = if (isLastLetter) twoSigma60Sq else twoSigma50Sq

                val searchRange =
                    when {
                        isFirstLetter -> (swipePath.size * 0.30).toInt().coerceAtLeast(3)
                        isLastLetter -> swipePath.size - (swipePath.size * 0.30).toInt().coerceAtLeast(swipePath.size - 3)
                        else -> swipePath.size
                    }

                var minDistanceSquared = Float.MAX_VALUE
                var closestPointIndex = -1

                val searchStart = if (isLastLetter) swipePath.size - searchRange else 0
                val searchEnd = if (isFirstLetter) searchRange else swipePath.size

                swipePath.subList(searchStart, searchEnd).forEachIndexed { relativeIndex, point ->
                    val pointIndex = searchStart + relativeIndex
                    val dx = keyPos.x - point.x
                    val dy = keyPos.y - point.y
                    val spatialDistanceSquared = dx * dx + dy * dy

                    if (spatialDistanceSquared < minDistanceSquared) {
                        minDistanceSquared = spatialDistanceSquared
                        closestPointIndex = pointIndex
                    }
                }

                val letterScore =
                    if (minDistanceSquared > expThreshold) {
                        0.0f
                    } else {
                        exp(-minDistanceSquared / twoSigmaSquared)
                    }

                letterPathIndices.add(closestPointIndex)
                letterScores.add(char to letterScore)
                totalScore += letterScore
            }

            var sequenceViolations = 0
            for (i in 1 until letterPathIndices.size) {
                val currentIndex = letterPathIndices[i]
                val previousIndex = letterPathIndices[i - 1]

                val isRepeatedLetter = i < word.length && word[i] == word[i - 1]
                val indexDiff = previousIndex - currentIndex

                if (isRepeatedLetter) {
                    if (indexDiff > 5) {
                        sequenceViolations++
                    }
                } else {
                    if (currentIndex < previousIndex) {
                        sequenceViolations++
                    }
                }
            }

            val baseTolerableViolations =
                when {
                    word.length <= 4 -> 0
                    word.length <= 6 -> 1
                    else -> 2
                }

            val repetitionCount = word.length - uniqueLetterCount
            val maxTolerableViolations = baseTolerableViolations + repetitionCount

            val sequencePenalty =
                when {
                    sequenceViolations <= maxTolerableViolations -> 1.0f
                    sequenceViolations == maxTolerableViolations + 1 -> 0.95f
                    sequenceViolations == maxTolerableViolations + 2 -> 0.85f
                    else -> 0.70f
                }

            val baseSpatialScore = totalScore / word.length.toFloat()

            val hasBadLetter = letterScores.any { it.second < 0.30f }
            val wrongLetterPenalty = if (hasBadLetter && word.length >= 4) 0.85f else 1.0f

            val lengthBonus =
                when {
                    word.length >= 8 -> 1.25f
                    word.length == 7 -> 1.18f
                    word.length == 6 -> 1.12f
                    word.length == 5 -> 1.06f
                    else -> 1.0f
                }

            val spatialWithBonuses = (baseSpatialScore * sequencePenalty * lengthBonus * wrongLetterPenalty).coerceAtMost(1.0f)

            val repetitionRatio = repetitionCount.toFloat() / word.length.toFloat()
            val repetitionPenalty =
                if (repetitionRatio > 0.30f) {
                    1.0f - ((repetitionCount - 1) * SwipeDetectionConstants.REPETITION_PENALTY_FACTOR).coerceAtMost(0.20f)
                } else {
                    1.0f
                }

            val finalScore = spatialWithBonuses * repetitionPenalty

            return finalScore
        }

        private fun couldMatchPath(
            word: String,
            charsInBounds: Set<Char>,
        ): Boolean {
            val charsInBoundsCount =
                word.count { char ->
                    char.lowercaseChar() in charsInBounds
                }

            val requiredChars = (word.length * SwipeDetectionConstants.MIN_CHARS_IN_BOUNDS_RATIO).toInt().coerceAtLeast(1)
            return charsInBoundsCount >= requiredChars
        }

        private fun calculatePathBounds(swipePath: List<SwipePoint>): PathBounds {
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE

            swipePath.forEach { point ->
                minX = min(minX, point.x)
                maxX = max(maxX, point.x)
                minY = min(minY, point.y)
                maxY = max(maxY, point.y)
            }

            return PathBounds(minX, maxX, minY, maxY)
        }

        private data class PathBounds(
            val minX: Float,
            val maxX: Float,
            val minY: Float,
            val maxY: Float,
        )

        private fun calculateVelocity(event: MotionEvent): Float {
            if (swipePoints.size < 2) return 0.0f

            val lastPoint = swipePoints.lastOrNull() ?: return 0.0f
            val distance = calculateDistance(lastPoint.x, lastPoint.y, event.x, event.y)
            val timeDelta = System.currentTimeMillis() - lastPoint.timestamp

            return if (timeDelta > 0) distance / timeDelta else 0.0f
        }

        private fun calculateDistance(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }

        private fun reset() {
            isSwiping = false
            swipePoints.clear()
            startTime = 0L
            pointCounter = 0
            firstPoint = null
            lastUpdateTime = 0L
        }

        /**
         * Cleans up resources and cancels pending operations.
         */
        fun cleanup() {
            scoringJob?.cancel()
            scopeJob.cancel()
            swipeListener = null
            keyCharacterPositions = emptyMap()
            reset()
        }
    }
