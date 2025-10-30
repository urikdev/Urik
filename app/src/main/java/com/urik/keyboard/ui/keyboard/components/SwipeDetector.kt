@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.MotionEvent
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.SpellCheckManager
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

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
        private companion object {
            const val MAX_SWIPE_POINTS = 75
            const val MIN_SAMPLING_INTERVAL = 2
            const val MAX_SAMPLING_INTERVAL = 8
            const val ADAPTIVE_THRESHOLD = 40
            const val MIN_POINT_DISTANCE = 8f
            const val MIN_CHARS_IN_BOUNDS_RATIO = 0.6f
            const val MIN_EXCELLENT_CANDIDATES = 3
        }

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
        private var keyCharacterPositions = emptyMap<Char, PointF>()

        @Volatile
        private var frequencyDictionary = emptyList<DictionaryEntry>()

        @Volatile
        private var dictionaryLanguage = ""

        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(Dispatchers.Default + scopeJob)
        private var scoringJob: Job? = null
        private var dictionaryLoadJob: Job? = null

        data class DictionaryEntry(
            val word: String,
            val frequencyScore: Float,
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
         *
         * Reconfigures gesture parameters and reloads frequency dictionary.
         * Cancels in-progress dictionary loads to prevent race conditions.
         */
        fun updateScriptContext(locale: ULocale) {
            currentLocale = locale
            val languageCode = locale.language
            if (languageCode != dictionaryLanguage) {
                dictionaryLoadJob?.cancel()
                loadFrequencyDictionary(languageCode)
            }
        }

        private fun loadFrequencyDictionary(languageCode: String) {
            dictionaryLoadJob =
                scope.launch {
                    try {
                        val rawWords = spellCheckManager.getCommonWords()

                        val wordFrequencyMap = mutableMapOf<String, Int>()
                        rawWords.forEach { (word, frequency) ->
                            if (word.isNotEmpty()) {
                                val existing = wordFrequencyMap[word]
                                if (existing == null || frequency > existing) {
                                    wordFrequencyMap[word] = frequency
                                }
                            }
                        }

                        val newDictionary =
                            wordFrequencyMap.entries
                                .map { (word, frequency) ->
                                    DictionaryEntry(
                                        word = word,
                                        frequencyScore = ln(frequency.toFloat() + 1f) / 20f,
                                        firstChar = word.first().lowercaseChar(),
                                        uniqueLetterCount = word.toSet().size,
                                    )
                                }.sortedByDescending { it.frequencyScore }

                        frequencyDictionary = newDictionary
                        dictionaryLanguage = languageCode
                    } catch (_: Exception) {
                        frequencyDictionary = emptyList()
                    }
                }
        }

        fun setSwipeListener(listener: SwipeListener) {
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
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
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
                if (timeSinceDown < 100L) {
                    return
                }

                val distance = calculateDistance(start.x, start.y, event.x, event.y)
                if (distance > 35f) {
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
            if (swipePoints.size < 3) return true

            val lastPoint = swipePoints.lastOrNull() ?: return true

            val dx = newPoint.x - lastPoint.x
            val dy = newPoint.y - lastPoint.y
            val distanceSquared = dx * dx + dy * dy

            if (distanceSquared < MIN_POINT_DISTANCE * MIN_POINT_DISTANCE) return false

            val samplingInterval =
                when {
                    swipePoints.size < ADAPTIVE_THRESHOLD -> MIN_SAMPLING_INTERVAL
                    swipePoints.size < MAX_SWIPE_POINTS * 0.75 -> MIN_SAMPLING_INTERVAL + 2
                    else -> MAX_SAMPLING_INTERVAL
                }

            if (counter % samplingInterval != 0) return false

            val isSlowPreciseMovement = velocity < 0.5f && swipePoints.size > 10
            if (isSlowPreciseMovement) {
                return counter % MIN_SAMPLING_INTERVAL == 0
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

            if (shouldAddPoint && swipePoints.size < MAX_SWIPE_POINTS) {
                swipePoints.add(newPoint)
            }

            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= 16) {
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
                if (duration <= 350L) {
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
            withContext(Dispatchers.Default) {
                try {
                    if (swipePath.isEmpty()) return@withContext emptyList()

                    val keyPositionsSnapshot = keyCharacterPositions
                    val dictionarySnapshot = frequencyDictionary

                    if (keyPositionsSnapshot.isEmpty() || dictionarySnapshot.isEmpty()) {
                        return@withContext emptyList()
                    }

                    val candidatesMap = mutableMapOf<String, WordCandidate>()
                    val pathBounds = calculatePathBounds(swipePath)

                    val margin = 100f
                    val charsInBounds =
                        keyPositionsSnapshot.keys
                            .filter { char ->
                                val pos = keyPositionsSnapshot[char]!!
                                pos.x >= pathBounds.minX - margin &&
                                    pos.x <= pathBounds.maxX + margin &&
                                    pos.y >= pathBounds.minY - margin &&
                                    pos.y <= pathBounds.maxY + margin
                            }.toSet()

                    val startingKey =
                        swipePath.firstOrNull()?.let { firstPoint ->
                            keyPositionsSnapshot.entries
                                .minByOrNull { (_, pos) ->
                                    val dx = pos.x - firstPoint.x
                                    val dy = pos.y - firstPoint.y
                                    dx * dx + dy * dy
                                }?.key
                        }

                    val wordsToCheck = dictionarySnapshot.size

                    for (i in 0 until wordsToCheck) {
                        if (i % 50 == 0) {
                            yield()
                        }

                        val entry = dictionarySnapshot[i]

                        if (startingKey != null) {
                            val startsWithCorrectLetter = entry.firstChar == startingKey
                            if (!startsWithCorrectLetter) {
                                val firstLetterPos = keyPositionsSnapshot[entry.firstChar]
                                val startingPos = keyPositionsSnapshot[startingKey]

                                val isCloseKey =
                                    if (firstLetterPos != null && startingPos != null) {
                                        val dx = firstLetterPos.x - startingPos.x
                                        val dy = firstLetterPos.y - startingPos.y
                                        dx * dx + dy * dy < 3600f
                                    } else {
                                        false
                                    }

                                if (!isCloseKey) {
                                    continue
                                }
                            }
                        }

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

                        val pathLengthRatio = entry.word.length.toFloat() / swipePath.size.toFloat()
                        val lengthPenalty =
                            when {
                                pathLengthRatio < 0.06f -> 0.80f
                                entry.word.length <= 2 && swipePath.size >= 15 -> 0.60f
                                entry.word.length == 3 && swipePath.size >= 20 -> 0.85f
                                entry.word.length == 4 && swipePath.size >= 50 -> 0.70f
                                entry.word.length == 5 && swipePath.size >= 55 -> 0.80f
                                else -> 1.0f
                            }

                        val adjustedSpatialScore = spatialScore * lengthPenalty
                        val combinedScore = adjustedSpatialScore * 0.7f + entry.frequencyScore * 0.3f

                        val candidate = WordCandidate(entry.word, adjustedSpatialScore, entry.frequencyScore, combinedScore)

                        val existing = candidatesMap[entry.word]
                        if (existing == null || combinedScore > existing.combinedScore) {
                            candidatesMap[entry.word] = candidate
                        }

                        if (combinedScore > 0.95f) {
                            val excellentCandidates = candidatesMap.values.count { it.combinedScore > 0.90f }
                            if (excellentCandidates >= MIN_EXCELLENT_CANDIDATES) {
                                break
                            }
                        }
                    }

                    val topCandidates = candidatesMap.values.sortedByDescending { it.combinedScore }.take(10)

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

            val expThresh50 = 25000f
            val twoSigma50Sq = 5000f

            val expThresh60 = 36000f
            val twoSigma60Sq = 7200f

            val basePenalty =
                when {
                    word.length <= 4 -> 400f
                    word.length >= 8 -> 0f
                    else -> 400f - (word.length - 4) * 100f
                }

            val penaltyStrength =
                if (basePenalty > 0f) {
                    val pathLengthFactor = (swipePath.size.toFloat() / 15f).coerceIn(1.0f, 2.5f)
                    basePenalty / pathLengthFactor
                } else {
                    0f
                }

            val swipePathLastIndex = swipePath.size - 1
            val pathProgressValues = FloatArray(swipePath.size) { it.toFloat() / swipePathLastIndex }

            val wordLastIndex = (word.length - 1).coerceAtLeast(1)

            val letterPathIndices = mutableListOf<Int>()

            word.forEachIndexed { letterIndex, char ->
                val keyPos = keyPositions[char.lowercaseChar()] ?: return 0f

                val isLastLetter = letterIndex == word.length - 1
                val expThreshold = if (isLastLetter) expThresh60 else expThresh50
                val twoSigmaSquared = if (isLastLetter) twoSigma60Sq else twoSigma50Sq

                var minDistanceSquared = Float.MAX_VALUE
                var closestPointIndex = -1

                val expectedProgress = letterIndex.toFloat() / wordLastIndex

                swipePath.forEachIndexed { pointIndex, point ->
                    val dx = keyPos.x - point.x
                    val dy = keyPos.y - point.y
                    val spatialDistanceSquared = dx * dx + dy * dy

                    val pathProgress = pathProgressValues[pointIndex]
                    val progressDiff = abs(pathProgress - expectedProgress)

                    val temporalPenalty = 1.0f + (progressDiff * penaltyStrength)
                    val adjustedDistance = spatialDistanceSquared * temporalPenalty

                    if (adjustedDistance < minDistanceSquared) {
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

            val maxTolerableViolations =
                when {
                    word.length <= 4 -> 0
                    word.length <= 6 -> 1
                    else -> 2
                }

            val sequencePenalty =
                when {
                    sequenceViolations <= maxTolerableViolations -> 1.0f
                    sequenceViolations == maxTolerableViolations + 1 -> 0.95f
                    sequenceViolations == maxTolerableViolations + 2 -> 0.85f
                    else -> 0.70f
                }

            val baseSpatialScore = totalScore / word.length.toFloat()

            val lengthBonus =
                when {
                    word.length >= 8 -> 1.08f
                    word.length == 7 -> 1.05f
                    word.length == 6 -> 1.03f
                    else -> 1.0f
                }

            val spatialWithBonuses = (baseSpatialScore * sequencePenalty * lengthBonus).coerceAtMost(1.0f)

            val repetitionPenalty = 1.0f - ((word.length - uniqueLetterCount) * 0.08f).coerceAtMost(0.20f)

            return spatialWithBonuses * repetitionPenalty
        }

        private fun couldMatchPath(
            word: String,
            charsInBounds: Set<Char>,
        ): Boolean {
            val charsInBoundsCount =
                word.count { char ->
                    char.lowercaseChar() in charsInBounds
                }

            val requiredChars = (word.length * MIN_CHARS_IN_BOUNDS_RATIO).toInt().coerceAtLeast(1)
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
         *
         * Cancels scoring and dictionary loading jobs, clears listeners.
         */
        fun cleanup() {
            scoringJob?.cancel()
            dictionaryLoadJob?.cancel()
            scopeJob.cancel()
            swipeListener = null
            keyCharacterPositions = emptyMap()
            frequencyDictionary = emptyList()
            reset()
        }
    }
