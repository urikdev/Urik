@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.MotionEvent
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.KeyboardConstants.SwipeDetectionConstants
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
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
        private val wordLearningEngine: WordLearningEngine,
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
        private var startingKey: KeyboardKey? = null
        private var lastDeltaX = 0f
        private var directionReversals = 0
        private var lastCheckX = 0f

        private var currentLocale: ULocale? = null

        @Volatile
        private var currentIsRTL = false

        @Volatile
        private var currentScriptCode = UScript.LATIN

        @Volatile
        private var activeLanguages = listOf("en")

        @Volatile
        private var swipeEnabled = true

        @Volatile
        private var swipeStartDistancePx = 50f

        @Volatile
        private var keyCharacterPositions = emptyMap<Char, PointF>()

        @Volatile
        private var cachedSwipeDictionary = emptyMap<String, Int>()

        @Volatile
        private var cachedLanguageCombination = emptyList<String>()

        @Volatile
        private var cachedScriptCode = UScript.LATIN

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
         * Updates script context when language or layout changes.
         */
        fun updateScriptContext(
            locale: ULocale,
            isRTL: Boolean = false,
            scriptCode: Int = UScript.LATIN,
        ) {
            currentLocale = locale
            currentIsRTL = isRTL
            currentScriptCode = scriptCode
        }

        fun updateActiveLanguages(languages: List<String>) {
            activeLanguages = languages
        }

        private fun getScriptCodeForLanguage(languageCode: String): Int =
            when (languageCode) {
                "en", "es", "pl", "pt", "de", "cs", "sv" -> UScript.LATIN
                "ru", "uk" -> UScript.CYRILLIC
                "fa" -> UScript.ARABIC
                else -> UScript.LATIN
            }

        private fun areLayoutsCompatible(
            script1: Int,
            script2: Int,
        ): Boolean {
            val latinLikeScripts = setOf(UScript.LATIN, UScript.CYRILLIC)
            val arabicScripts = setOf(UScript.ARABIC)

            return when (script1) {
                in latinLikeScripts if script2 in latinLikeScripts -> true
                in arabicScripts if script2 in arabicScripts -> true
                else -> false
            }
        }

        private fun getCompatibleLanguagesForSwipe(
            activeLanguages: List<String>,
            currentScriptCode: Int,
        ): List<String> =
            activeLanguages.filter { lang ->
                val layoutScript = getScriptCodeForLanguage(lang)
                areLayoutsCompatible(currentScriptCode, layoutScript)
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

                    else -> {
                        return false
                    }
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
                    if (touchedKey.value.isEmpty() || !touchedKey.value.first().isLetter()) {
                        reset()
                        return false
                    }
                    startSwipeDetection(event, touchedKey)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isSwiping) {
                        updateSwipePath(event)
                        return true
                    } else {
                        trackSwipeGesture(event, keyAt)
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

        private fun startSwipeDetection(
            event: MotionEvent,
            key: KeyboardKey,
        ) {
            scoringJob?.cancel()
            reset()
            startTime = System.currentTimeMillis()
            pointCounter = 0
            startingKey = key

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
            lastCheckX = event.x
        }

        private fun trackSwipeGesture(
            event: MotionEvent,
            keyAt: (Float, Float) -> KeyboardKey?,
        ) {
            firstPoint?.let { start ->
                val now = System.currentTimeMillis()
                val timeSinceDown = now - startTime

                if (lastCheckX != 0f) {
                    val deltaX = event.x - lastCheckX
                    if (lastDeltaX != 0f && deltaX != 0f) {
                        if ((lastDeltaX > 0) != (deltaX > 0)) {
                            directionReversals++
                        }
                    }
                    lastDeltaX = deltaX
                }
                lastCheckX = event.x

                if (timeSinceDown < SwipeDetectionConstants.SWIPE_TIME_THRESHOLD_MS) {
                    return
                }

                if (directionReversals >= 3) {
                    reset()
                    return
                }

                val distance = calculateDistance(start.x, start.y, event.x, event.y)
                if (distance > swipeStartDistancePx) {
                    val currentKey = keyAt(event.x, event.y)
                    if (currentKey == startingKey) {
                        return
                    }

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
                        scriptCode = currentScriptCode,
                        isRtl = currentIsRTL,
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
                        scriptCode = currentScriptCode,
                        isRtl = currentIsRTL,
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

                    val minLength = (swipePath.size / 7).coerceAtLeast(2)
                    val maxLength = (swipePath.size).coerceAtMost(20)

                    val compatibleLanguages = getCompatibleLanguagesForSwipe(activeLanguages, currentScriptCode)

                    val wordFrequencyMap =
                        if (compatibleLanguages == cachedLanguageCombination &&
                            currentScriptCode == cachedScriptCode &&
                            cachedSwipeDictionary.isNotEmpty()
                        ) {
                            cachedSwipeDictionary
                        } else {
                            val dictionaryWordsMap = spellCheckManager.getCommonWordsForLanguages(compatibleLanguages)
                            val learnedWordsMap =
                                wordLearningEngine.getLearnedWordsForSwipeAllLanguages(
                                    compatibleLanguages,
                                    minLength,
                                    maxLength,
                                )

                            val mergedMap = HashMap<String, Int>(dictionaryWordsMap.size + learnedWordsMap.size)
                            dictionaryWordsMap.forEach { (word, freq) -> mergedMap[word] = freq }
                            learnedWordsMap.forEach { (word, freq) ->
                                mergedMap[word] = maxOf(mergedMap[word] ?: 0, freq)
                            }

                            cachedSwipeDictionary = mergedMap
                            cachedLanguageCombination = compatibleLanguages
                            cachedScriptCode = currentScriptCode

                            mergedMap
                        }

                    if (keyPositionsSnapshot.isEmpty() || wordFrequencyMap.isEmpty()) {
                        return@withContext emptyList()
                    }

                    val dictionaryByFirstChar =
                        wordFrequencyMap.entries
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
                    var maxFrequencySeen = 0

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
                                        Triple(char, distSq, sqrt(distSq).toInt())
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

                    val reuseLetterPathIndices = ArrayList<Int>(20)
                    val reuseLetterScores = ArrayList<Pair<Char, Float>>(20)

                    for (i in 0 until wordsToCheck) {
                        if (i % 50 == 0) {
                            yield()
                        }

                        val entry = dictionarySnapshot[i]

                        if (!couldMatchPath(entry.word, charsInBounds)) {
                            continue
                        }

                        val pointsPerLetter = swipePath.size.toFloat() / entry.word.length.toFloat()
                        val optimalRatio = if (entry.word.length <= 3) 3.0f else 4.0f
                        val ratioQuality = pointsPerLetter / optimalRatio

                        val spatialScore =
                            calculateSpatialScore(
                                entry.word,
                                swipePath,
                                keyPositionsSnapshot,
                                entry.uniqueLetterCount,
                                reuseLetterPathIndices,
                                reuseLetterScores,
                                ratioQuality,
                            )

                        val ratioPenalty =
                            when {
                                pointsPerLetter < optimalRatio * 0.50f -> 0.50f
                                pointsPerLetter < optimalRatio * 0.65f -> 0.60f
                                pointsPerLetter < optimalRatio * 0.75f -> 0.75f
                                pointsPerLetter < optimalRatio * 0.85f -> 0.90f
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

                        if (entry.rawFrequency > maxFrequencySeen) {
                            maxFrequencySeen = entry.rawFrequency.toInt()
                        }

                        val frequencyRatio =
                            if (maxFrequencySeen > 0) {
                                entry.rawFrequency.toFloat() / maxFrequencySeen.toFloat()
                            } else {
                                1.0f
                            }

                        val (spatialWeight, frequencyWeight) =
                            when {
                                entry.word.length == 2 && adjustedSpatialScore > 0.75f -> 0.85f to 0.15f
                                frequencyRatio >= 10.0f -> 0.45f to 0.55f
                                frequencyRatio >= 5.0f -> 0.50f to 0.50f
                                frequencyRatio >= 3.0f -> 0.55f to 0.45f
                                else -> SwipeDetectionConstants.SPATIAL_SCORE_WEIGHT to SwipeDetectionConstants.FREQUENCY_SCORE_WEIGHT
                            }

                        val combinedScore = adjustedSpatialScore * spatialWeight + boostedFrequencyScore * frequencyWeight

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
            letterPathIndices: ArrayList<Int>,
            letterScores: ArrayList<Pair<Char, Float>>,
            ratioQuality: Float = 1.0f,
        ): Float {
            if (swipePath.isEmpty()) return 0f

            letterPathIndices.clear()
            letterScores.clear()

            var totalScore = 0f

            val expThresh50 = SwipeDetectionConstants.EXP_THRESHOLD_50
            val twoSigma50Sq = SwipeDetectionConstants.TWO_SIGMA_50_SQ

            val expThresh60 = SwipeDetectionConstants.EXP_THRESHOLD_60
            val twoSigma60Sq = SwipeDetectionConstants.TWO_SIGMA_60_SQ

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

                var minTotalDistance = Float.MAX_VALUE
                var minDistanceSquared = Float.MAX_VALUE
                var closestPointIndex = -1

                val searchStart = if (isLastLetter) swipePath.size - searchRange else 0
                val searchEnd = if (isFirstLetter) searchRange else swipePath.size

                val expectedPathProgress =
                    if (word.length > 1) {
                        letterIndex.toFloat() / (word.length - 1).toFloat()
                    } else {
                        0.5f
                    }
                val expectedPathIndex = (expectedPathProgress * (swipePath.size - 1)).toInt()

                for (relativeIndex in 0 until (searchEnd - searchStart)) {
                    val pointIndex = searchStart + relativeIndex
                    val point = swipePath[pointIndex]
                    val dx = keyPos.x - point.x
                    val dy = keyPos.y - point.y
                    val spatialDistanceSquared = dx * dx + dy * dy

                    val positionDeviation = kotlin.math.abs(pointIndex - expectedPathIndex).toFloat()
                    val positionPenalty = positionDeviation * 150f

                    val totalDistance = spatialDistanceSquared + positionPenalty

                    if (totalDistance < minTotalDistance) {
                        minTotalDistance = totalDistance
                        minDistanceSquared = spatialDistanceSquared
                        closestPointIndex = pointIndex

                        if (spatialDistanceSquared < 100f && positionDeviation < 2f) {
                            break
                        }
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

            val baseTolerableViolations =
                when {
                    word.length <= 4 -> 0
                    word.length <= 6 -> 1
                    else -> 1
                }

            val repetitionCount = word.length - uniqueLetterCount
            val repetitionPenaltyFactor = if (word.length >= 6) 0 else 1
            val maxTolerableViolations = baseTolerableViolations + (repetitionCount * repetitionPenaltyFactor)

            val sequencePenalty =
                when {
                    sequenceViolations <= maxTolerableViolations -> 1.0f
                    sequenceViolations == maxTolerableViolations + 1 -> 0.95f
                    sequenceViolations == maxTolerableViolations + 2 -> 0.85f
                    else -> 0.70f
                }

            val baseSpatialScore = totalScore / word.length.toFloat()

            val badLetterCount = letterScores.count { it.second < 0.30f }
            val veryBadLetterCount = letterScores.count { it.second < 0.15f }
            val wrongLetterPenalty =
                when {
                    veryBadLetterCount > 0 -> 0.40f
                    badLetterCount >= 2 && word.length <= 4 -> 0.45f
                    badLetterCount >= 1 && word.length == 2 -> 0.35f
                    badLetterCount >= 1 && word.length == 3 -> 0.50f
                    badLetterCount >= 1 && word.length >= 4 -> 0.70f
                    else -> 1.0f
                }

            val pathExhaustionPenalty =
                if (word.length >= SwipeDetectionConstants.PATH_EXHAUSTION_MIN_WORD_LENGTH && letterPathIndices.isNotEmpty()) {
                    val lastQuartileThreshold = (swipePath.size * SwipeDetectionConstants.PATH_EXHAUSTION_QUARTILE_THRESHOLD).toInt()
                    val tailLetterCount =
                        (word.length * SwipeDetectionConstants.PATH_EXHAUSTION_TAIL_RATIO).toInt().coerceAtLeast(
                            SwipeDetectionConstants.PATH_EXHAUSTION_MIN_LETTERS_CHECK,
                        )
                    val startIndex = letterPathIndices.size - tailLetterCount
                    var lettersInLastQuartile = 0
                    for (i in startIndex until letterPathIndices.size) {
                        if (letterPathIndices[i] >= lastQuartileThreshold) {
                            lettersInLastQuartile++
                        }
                    }
                    when {
                        lettersInLastQuartile >= 3 -> 0.60f
                        lettersInLastQuartile == 2 -> 0.80f
                        else -> 1.0f
                    }
                } else {
                    1.0f
                }

            val lengthBonus =
                if (ratioQuality >= SwipeDetectionConstants.LENGTH_BONUS_MIN_RATIO_QUALITY) {
                    when {
                        word.length >= 8 -> 1.25f
                        word.length == 7 -> 1.18f
                        word.length == 6 -> 1.12f
                        word.length == 5 -> 1.06f
                        else -> 1.0f
                    }
                } else {
                    1.0f
                }

            val spatialWithBonuses =
                (baseSpatialScore * sequencePenalty * lengthBonus * wrongLetterPenalty * pathExhaustionPenalty)
                    .coerceAtMost(
                        1.0f,
                    )

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
            startingKey = null
            lastDeltaX = 0f
            directionReversals = 0
            lastCheckX = 0f
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
