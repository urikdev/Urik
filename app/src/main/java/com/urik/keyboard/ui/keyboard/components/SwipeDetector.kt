@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.MotionEvent
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
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
        private val pathGeometryAnalyzer: PathGeometryAnalyzer,
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

        @Suppress("ktlint:standard:backing-property-naming")
        private var _swipeListener: SwipeListener? = null

        private var lastUpdateTime = 0L
        private var isSwiping = false
        private var swipePoints = ArrayList<SwipePoint>(SwipeDetectionConstants.MAX_SWIPE_POINTS)
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
        private var layoutScaleFactor = 1.0f

        @Volatile
        private var layoutOffsetX = 0f

        @Volatile
        private var keyCharacterPositions = emptyMap<Char, PointF>()

        @Volatile
        private var cachedSwipeDictionary = emptyMap<String, Int>()

        @Volatile
        private var cachedLanguageCombination = emptyList<String>()

        @Volatile
        private var cachedScriptCode = UScript.LATIN

        @Volatile
        private var cachedAdaptiveSigmas = emptyMap<Char, PathGeometryAnalyzer.AdaptiveSigma>()

        @Volatile
        private var lastKeyPositionsHash = 0

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
         * Updates layout transform for adaptive keyboard modes.
         *
         * @param scaleFactor Width scaling factor (e.g., 0.7 for one-handed mode)
         * @param offsetX Horizontal offset in pixels (e.g., for right-aligned one-handed mode)
         */
        fun updateLayoutTransform(
            scaleFactor: Float,
            offsetX: Float,
        ) {
            layoutScaleFactor = scaleFactor
            layoutOffsetX = offsetX
        }

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

        private fun transformTouchCoordinate(
            x: Float,
            y: Float,
        ): PointF =
            PointF(
                (x - layoutOffsetX) / layoutScaleFactor,
                y,
            )

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
            this._swipeListener = listener
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
                                _swipeListener?.onTap(tappedKey)
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

            val transformed = transformTouchCoordinate(event.x, event.y)
            val point =
                SwipePoint(
                    x = transformed.x,
                    y = transformed.y,
                    timestamp = startTime,
                    pressure = event.pressure,
                    velocity = 0.0f,
                )
            firstPoint = point
            swipePoints.add(point)
            lastCheckX = transformed.x
        }

        private fun trackSwipeGesture(
            event: MotionEvent,
            keyAt: (Float, Float) -> KeyboardKey?,
        ) {
            firstPoint?.let { start ->
                val now = System.currentTimeMillis()
                val timeSinceDown = now - startTime
                val distance = calculateDistance(start.x, start.y, event.x, event.y)

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

                for (h in 0 until event.historySize) {
                    val histX = event.getHistoricalX(h)
                    val histY = event.getHistoricalY(h)
                    val histTime = event.getHistoricalEventTime(h)
                    val histTransformed = transformTouchCoordinate(histX, histY)
                    val histLastPoint = swipePoints.lastOrNull()

                    val histVelocity =
                        if (histLastPoint != null) {
                            val dx = histTransformed.x - histLastPoint.x
                            val dy = histTransformed.y - histLastPoint.y
                            val dt = (histTime - histLastPoint.timestamp).coerceAtLeast(1L).toFloat()
                            sqrt(dx * dx + dy * dy) / dt
                        } else {
                            0f
                        }

                    val histDist =
                        if (histLastPoint != null) {
                            calculateDistance(histLastPoint.x, histLastPoint.y, histTransformed.x, histTransformed.y)
                        } else {
                            Float.MAX_VALUE
                        }

                    if (histDist > 4f) {
                        swipePoints.add(
                            SwipePoint(
                                x = histTransformed.x,
                                y = histTransformed.y,
                                timestamp = histTime,
                                pressure = event.getHistoricalPressure(h),
                                velocity = histVelocity,
                            ),
                        )
                    }
                }

                val transformed = transformTouchCoordinate(event.x, event.y)
                val lastPoint = swipePoints.lastOrNull()
                val velocityFromLast =
                    if (lastPoint != null && timeSinceDown > 0) {
                        val dx = transformed.x - lastPoint.x
                        val dy = transformed.y - lastPoint.y
                        sqrt(dx * dx + dy * dy) / (now - lastPoint.timestamp).coerceAtLeast(1L).toFloat()
                    } else {
                        0f
                    }

                val distFromLast =
                    if (lastPoint != null) {
                        calculateDistance(lastPoint.x, lastPoint.y, transformed.x, transformed.y)
                    } else {
                        Float.MAX_VALUE
                    }

                if (distFromLast > 4f) {
                    swipePoints.add(
                        SwipePoint(
                            x = transformed.x,
                            y = transformed.y,
                            timestamp = now,
                            pressure = event.pressure,
                            velocity = velocityFromLast,
                        ),
                    )
                }

                if (swipePoints.size >= 3) {
                    val largeGapCount =
                        swipePoints.zipWithNext().count { (prev, curr) ->
                            calculateDistance(prev.x, prev.y, curr.x, curr.y) > SwipeDetectionConstants.MAX_CONSECUTIVE_GAP_PX
                        }

                    val gapRatio = largeGapCount.toFloat() / (swipePoints.size - 1)
                    if (gapRatio > 0.5f) {
                        reset()
                        return
                    }
                }

                if (timeSinceDown < SwipeDetectionConstants.SWIPE_TIME_THRESHOLD_MS) {
                    return
                }

                if (directionReversals >= 3) {
                    reset()
                    return
                }

                if (distance > swipeStartDistancePx) {
                    val currentKey = keyAt(event.x, event.y)
                    if (currentKey == startingKey) {
                        return
                    }

                    val avgVelocity = distance / timeSinceDown.toFloat()
                    if (avgVelocity > SwipeDetectionConstants.MAX_SWIPE_VELOCITY_PX_PER_MS) {
                        return
                    }

                    isSwiping = true
                    pointCounter = swipePoints.size
                    _swipeListener?.onSwipeStart(PointF(start.x, start.y))
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
            for (h in 0 until event.historySize) {
                pointCounter++
                val histX = event.getHistoricalX(h)
                val histY = event.getHistoricalY(h)
                val histTime = event.getHistoricalEventTime(h)
                val histTransformed = transformTouchCoordinate(histX, histY)
                val histLastPoint = swipePoints.lastOrNull()

                val histVelocity =
                    if (histLastPoint != null) {
                        val dx = histTransformed.x - histLastPoint.x
                        val dy = histTransformed.y - histLastPoint.y
                        val dt = (histTime - histLastPoint.timestamp).coerceAtLeast(1L).toFloat()
                        sqrt(dx * dx + dy * dy) / dt
                    } else {
                        0f
                    }

                val histPoint =
                    SwipePoint(
                        x = histTransformed.x,
                        y = histTransformed.y,
                        timestamp = histTime,
                        pressure = event.getHistoricalPressure(h),
                        velocity = histVelocity,
                    )

                if (shouldSamplePoint(histPoint, pointCounter, histVelocity)) {
                    swipePoints.add(histPoint)
                }
            }

            pointCounter++
            val transformed = transformTouchCoordinate(event.x, event.y)
            val velocity = calculateVelocity(event)
            val newPoint =
                SwipePoint(
                    x = transformed.x,
                    y = transformed.y,
                    timestamp = System.currentTimeMillis(),
                    pressure = event.pressure,
                    velocity = velocity,
                )

            val shouldAddPoint = shouldSamplePoint(newPoint, pointCounter, velocity)

            if (shouldAddPoint) {
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

                _swipeListener?.onSwipeUpdate(currentPath, transformed)
            }
        }

        private fun endSwipeDetection(
            event: MotionEvent,
            keyAt: (Float, Float) -> KeyboardKey?,
        ): Boolean {
            if (isSwiping) {
                val transformed = transformTouchCoordinate(event.x, event.y)
                val finalPoint =
                    SwipePoint(
                        x = transformed.x,
                        y = transformed.y,
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

                _swipeListener?.onSwipeEnd(preliminaryPath)

                val pathSnapshot = swipePoints.toList()

                scoringJob =
                    scope.launch {
                        try {
                            val topCandidates = performSpatialScoringAsync(pathSnapshot)

                            withContext(Dispatchers.Main) {
                                _swipeListener?.onSwipeResults(topCandidates)
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                _swipeListener?.onSwipeResults(emptyList())
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
                        _swipeListener?.onTap(tappedKey)
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

                    if (keyPositionsSnapshot.isEmpty()) {
                        return@withContext emptyList()
                    }

                    val interpolatedPath = interpolatePathForFastSegments(swipePath, keyPositionsSnapshot)

                    val minLength = 2
                    val maxLength = (interpolatedPath.size / 5).coerceIn(5, 20)

                    val compatibleLanguages = getCompatibleLanguagesForSwipe(activeLanguages, currentScriptCode)

                    val wordFrequencyMap = loadOrCacheDictionary(compatibleLanguages, minLength, maxLength)

                    if (wordFrequencyMap.isEmpty()) {
                        return@withContext emptyList()
                    }

                    updateAdaptiveSigmaCache(keyPositionsSnapshot)

                    val geometricAnalysis = pathGeometryAnalyzer.analyze(interpolatedPath, keyPositionsSnapshot)

                    val (baselineSpatialWeight, baselineFreqWeight) =
                        pathGeometryAnalyzer.calculateDynamicWeights(geometricAnalysis.pathConfidence)

                    val dictionaryByFirstChar = buildDictionaryIndex(wordFrequencyMap, minLength, maxLength)

                    val candidatesMap = mutableMapOf<String, ScoredCandidate>()
                    val pathBounds = calculatePathBounds(interpolatedPath)
                    var maxFrequencySeen = 0L

                    val charsInBounds = filterCharsByBounds(keyPositionsSnapshot, pathBounds)
                    val candidateStartKeys = findCandidateStartKeys(interpolatedPath, keyPositionsSnapshot)
                    val firstPoint = interpolatedPath.first()
                    val startKeyDistances =
                        candidateStartKeys.associateWith { char ->
                            val keyPos = keyPositionsSnapshot[char] ?: return@associateWith Float.MAX_VALUE
                            val dx = keyPos.x - firstPoint.x
                            val dy = keyPos.y - firstPoint.y
                            sqrt(dx * dx + dy * dy)
                        }
                    val closestStartKey = startKeyDistances.minByOrNull { it.value }?.key

                    val lastPoint = interpolatedPath.last()
                    val endKeyDistances =
                        keyPositionsSnapshot.mapValues { (_, keyPos) ->
                            val dx = keyPos.x - lastPoint.x
                            val dy = keyPos.y - lastPoint.y
                            sqrt(dx * dx + dy * dy)
                        }
                    val closestEndKey = endKeyDistances.minByOrNull { it.value }?.key

                    val relevantChars = candidateStartKeys.ifEmpty { dictionaryByFirstChar.keys }
                    val dictionarySnapshot = relevantChars.flatMap { dictionaryByFirstChar[it] ?: emptyList() }

                    val traversedKeySet = HashSet<Char>(geometricAnalysis.traversedKeys.size)
                    for (key in geometricAnalysis.traversedKeys.keys) {
                        traversedKeySet.add(key.lowercaseChar())
                    }

                    var intentionalInflectionCount = 0
                    for (inflection in geometricAnalysis.inflectionPoints) {
                        if (inflection.isIntentional) intentionalInflectionCount++
                    }

                    val reuseLetterPathIndices = ArrayList<Int>(20)
                    val reuseLetterScores = ArrayList<Pair<Char, Float>>(20)
                    val reusableWordLetters = HashSet<Char>(10)

                    for (i in dictionarySnapshot.indices) {
                        if (i % 50 == 0) yield()

                        val entry = dictionarySnapshot[i]

                        if (!couldMatchPath(entry.word, charsInBounds)) continue

                        val isClusteredWord = pathGeometryAnalyzer.isClusteredWord(entry.word, keyPositionsSnapshot)

                        val pointsPerLetter = interpolatedPath.size.toFloat() / entry.word.length.toFloat()
                        val optimalRatio = if (entry.word.length <= 3) 3.0f else 4.0f
                        val ratioQuality = pointsPerLetter / optimalRatio

                        val spatialScore =
                            calculateGeometricSpatialScore(
                                entry.word,
                                interpolatedPath,
                                keyPositionsSnapshot,
                                entry.uniqueLetterCount,
                                reuseLetterPathIndices,
                                reuseLetterScores,
                                ratioQuality,
                                geometricAnalysis,
                                isClusteredWord,
                            )

                        val ratioPenalty = calculateRatioPenalty(pointsPerLetter, optimalRatio)
                        val adjustedSpatialScore = spatialScore * ratioPenalty

                        val frequencyBoost = calculateFrequencyBoost(entry.rawFrequency)
                        val boostedFrequencyScore = entry.frequencyScore * frequencyBoost

                        if (entry.rawFrequency > maxFrequencySeen) {
                            maxFrequencySeen = entry.rawFrequency
                        }

                        val (spatialWeight, frequencyWeight) =
                            determineFinalWeights(
                                entry,
                                adjustedSpatialScore,
                                maxFrequencySeen,
                                baselineSpatialWeight,
                                baselineFreqWeight,
                                isClusteredWord,
                            )

                        val pathCoverage =
                            pathGeometryAnalyzer.calculatePathCoverage(
                                entry.word,
                                interpolatedPath,
                                keyPositionsSnapshot,
                                reuseLetterPathIndices,
                            )

                        var orderViolations = 0
                        for (j in 0 until reuseLetterPathIndices.size - 1) {
                            if (reuseLetterPathIndices[j + 1] < reuseLetterPathIndices[j]) {
                                orderViolations++
                            }
                        }
                        val orderPenalty =
                            when {
                                orderViolations == 0 -> 1.0f
                                orderViolations == 1 -> 0.85f
                                orderViolations == 2 -> 0.70f
                                else -> 0.50f
                            }

                        val coverageBonus =
                            if (pathCoverage > GeometricScoringConstants.MIN_PATH_COVERAGE_THRESHOLD) {
                                1.0f + (pathCoverage - GeometricScoringConstants.MIN_PATH_COVERAGE_THRESHOLD) * 0.25f
                            } else {
                                0.80f + pathCoverage * 0.35f
                            }

                        val maxInflectionLength =
                            when {
                                interpolatedPath.size < 35 -> 3
                                interpolatedPath.size < 50 -> 4
                                interpolatedPath.size < 70 -> 6
                                interpolatedPath.size < 100 -> 8
                                interpolatedPath.size < 150 -> 12
                                interpolatedPath.size < 200 -> 16
                                else -> 20
                            }
                        val inflectionBasedLength = (intentionalInflectionCount + 2).coerceIn(2, maxInflectionLength)
                        val pathPointBasedLength = (interpolatedPath.size / 14).coerceIn(2, 20)
                        val expectedWordLength = maxOf(inflectionBasedLength, pathPointBasedLength)

                        val lengthExcess = maxOf(0, entry.word.length - expectedWordLength)
                        val lengthExcessPenalty = 1.0f - (lengthExcess * GeometricScoringConstants.WORD_LENGTH_EXCESS_PENALTY)

                        val lengthDeficit = maxOf(0, expectedWordLength - entry.word.length)
                        val lengthDeficitPenalty = 1.0f - (lengthDeficit * GeometricScoringConstants.WORD_LENGTH_DEFICIT_PENALTY)

                        val lengthPenalty = lengthExcessPenalty * lengthDeficitPenalty

                        val wordFirstChar = entry.word.first().lowercaseChar()
                        val startKeyBonus =
                            if (wordFirstChar == closestStartKey) {
                                GeometricScoringConstants.START_KEY_MATCH_BONUS
                            } else {
                                val distToWordStart = startKeyDistances[wordFirstChar] ?: Float.MAX_VALUE
                                val distToClosest = startKeyDistances[closestStartKey] ?: 1f
                                val distanceRatio = (distToWordStart / distToClosest.coerceAtLeast(1f)).coerceAtMost(3f)
                                1.0f / (1.0f + (distanceRatio - 1.0f) * GeometricScoringConstants.START_KEY_DISTANCE_PENALTY_FACTOR)
                            }

                        val wordLastChar = entry.word.last().lowercaseChar()
                        val endKeyBonus =
                            if (wordLastChar == closestEndKey) {
                                GeometricScoringConstants.END_KEY_MATCH_BONUS
                            } else {
                                val distToWordEnd = endKeyDistances[wordLastChar] ?: Float.MAX_VALUE
                                val distToClosestEnd = endKeyDistances[closestEndKey] ?: 1f
                                val distanceRatio = (distToWordEnd / distToClosestEnd.coerceAtLeast(1f)).coerceAtMost(3f)
                                1.0f / (1.0f + (distanceRatio - 1.0f) * GeometricScoringConstants.END_KEY_DISTANCE_PENALTY_FACTOR)
                            }

                        reusableWordLetters.clear()
                        for (c in entry.word) {
                            reusableWordLetters.add(c.lowercaseChar())
                        }
                        var missingLetters = 0
                        for (letter in reusableWordLetters) {
                            if (letter !in traversedKeySet) missingLetters++
                        }
                        val traversalPenalty =
                            when (missingLetters) {
                                0 -> 1.0f
                                1 -> 0.75f
                                else -> 0.5f
                            }

                        @Suppress("ktlint:standard:max-line-length")
                        val combinedScore =
                            (adjustedSpatialScore * spatialWeight + boostedFrequencyScore * frequencyWeight) * coverageBonus * lengthPenalty *
                                startKeyBonus *
                                endKeyBonus *
                                traversalPenalty *
                                orderPenalty

                        val scored =
                            ScoredCandidate(
                                word = entry.word,
                                spatialScore = adjustedSpatialScore,
                                frequencyScore = entry.frequencyScore,
                                combinedScore = combinedScore,
                                pathCoverage = pathCoverage,
                                letterPathIndices = ArrayList(reuseLetterPathIndices),
                                isClusteredWord = isClusteredWord,
                                lengthPenalty = lengthPenalty,
                                traversalPenalty = traversalPenalty,
                                orderPenalty = orderPenalty,
                            )

                        val existing = candidatesMap[entry.word]
                        if (existing == null || combinedScore > existing.combinedScore) {
                            candidatesMap[entry.word] = scored
                        }

                        if (combinedScore > SwipeDetectionConstants.EXCELLENT_CANDIDATE_THRESHOLD) {
                            var excellentCount = 0
                            for (candidate in candidatesMap.values) {
                                if (candidate.combinedScore > 0.90f) excellentCount++
                            }
                            if (excellentCount >= SwipeDetectionConstants.MIN_EXCELLENT_CANDIDATES) break
                        }
                    }

                    val topCandidates =
                        selectTopCandidatesWithGeometricDisambiguation(
                            candidatesMap.values.toList(),
                            geometricAnalysis,
                            keyPositionsSnapshot,
                        )

                    return@withContext topCandidates
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }

        private data class ScoredCandidate(
            val word: String,
            val spatialScore: Float,
            val frequencyScore: Float,
            val combinedScore: Float,
            val pathCoverage: Float,
            val letterPathIndices: List<Int>,
            val isClusteredWord: Boolean,
            val lengthPenalty: Float = 1.0f,
            val traversalPenalty: Float = 1.0f,
            val orderPenalty: Float = 1.0f,
        )

        private fun interpolatePathForFastSegments(
            path: List<SwipePoint>,
            keyPositions: Map<Char, PointF>,
        ): List<SwipePoint> {
            if (path.size < 2) return path

            val result = ArrayList<SwipePoint>(path.size + 20)
            result.add(path[0])

            for (i in 1 until path.size) {
                val prev = path[i - 1]
                val curr = path[i]

                val dx = curr.x - prev.x
                val dy = curr.y - prev.y
                val distance = sqrt(dx * dx + dy * dy)

                val avgVelocity = (prev.velocity + curr.velocity) / 2f

                val shouldInterpolate =
                    (
                        avgVelocity > GeometricScoringConstants.VELOCITY_INTERPOLATION_THRESHOLD &&
                            distance > GeometricScoringConstants.INTERPOLATION_MIN_GAP_PX
                    ) ||
                        distance > GeometricScoringConstants.LARGE_GAP_INTERPOLATION_THRESHOLD_PX

                if (shouldInterpolate) {
                    val keysOnSegment = findKeysOnSegment(prev, curr, keyPositions)

                    keysOnSegment.take(GeometricScoringConstants.MAX_INTERPOLATED_POINTS).forEach { (_, _, t) ->
                        val interpX = prev.x + dx * t
                        val interpY = prev.y + dy * t
                        val interpTime = prev.timestamp + ((curr.timestamp - prev.timestamp) * t).toLong()
                        val interpVelocity = prev.velocity + (curr.velocity - prev.velocity) * t

                        result.add(
                            SwipePoint(
                                x = interpX,
                                y = interpY,
                                timestamp = interpTime,
                                pressure = (prev.pressure + curr.pressure) / 2f,
                                velocity = interpVelocity,
                            ),
                        )
                    }
                }

                result.add(curr)
            }

            return result
        }

        private fun findKeysOnSegment(
            p1: SwipePoint,
            p2: SwipePoint,
            keyPositions: Map<Char, PointF>,
        ): List<Triple<Char, PointF, Float>> {
            val keysOnPath = mutableListOf<Triple<Char, PointF, Float>>()

            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val segmentLengthSq = dx * dx + dy * dy

            if (segmentLengthSq < 1f) return keysOnPath

            keyPositions.forEach { (char, keyPos) ->
                val t = ((keyPos.x - p1.x) * dx + (keyPos.y - p1.y) * dy) / segmentLengthSq

                if (t in 0.1f..0.9f) {
                    val projX = p1.x + t * dx
                    val projY = p1.y + t * dy
                    val distToKey = sqrt((projX - keyPos.x).let { it * it } + (projY - keyPos.y).let { it * it })

                    if (distToKey < GeometricScoringConstants.KEY_TRAVERSAL_RADIUS) {
                        keysOnPath.add(Triple(char, keyPos, t))
                    }
                }
            }

            return keysOnPath.sortedBy { it.third }
        }

        private suspend fun loadOrCacheDictionary(
            compatibleLanguages: List<String>,
            minLength: Int,
            maxLength: Int,
        ): Map<String, Int> =
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

        private fun updateAdaptiveSigmaCache(keyPositions: Map<Char, PointF>) {
            val positionsHash = keyPositions.hashCode()
            if (positionsHash != lastKeyPositionsHash) {
                val newSigmas = mutableMapOf<Char, PathGeometryAnalyzer.AdaptiveSigma>()
                keyPositions.keys.forEach { char ->
                    newSigmas[char] = pathGeometryAnalyzer.calculateAdaptiveSigma(char, keyPositions)
                }
                cachedAdaptiveSigmas = newSigmas
                lastKeyPositionsHash = positionsHash
            }
        }

        private fun buildDictionaryIndex(
            wordFrequencyMap: Map<String, Int>,
            minLength: Int,
            maxLength: Int,
        ): Map<Char, List<DictionaryEntry>> =
            wordFrequencyMap.entries
                .asSequence()
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

        private fun filterCharsByBounds(
            keyPositions: Map<Char, PointF>,
            pathBounds: PathBounds,
        ): Set<Char> {
            val margin = SwipeDetectionConstants.PATH_BOUNDS_MARGIN_PX
            return keyPositions.keys.filterTo(mutableSetOf()) { char ->
                val pos = keyPositions[char]!!
                pos.x >= pathBounds.minX - margin &&
                    pos.x <= pathBounds.maxX + margin &&
                    pos.y >= pathBounds.minY - margin &&
                    pos.y <= pathBounds.maxY + margin
            }
        }

        private fun findCandidateStartKeys(
            path: List<SwipePoint>,
            keyPositions: Map<Char, PointF>,
        ): Set<Char> {
            val firstPoint = path.firstOrNull() ?: return emptySet()

            return keyPositions.entries
                .map { (char, pos) ->
                    val dx = pos.x - firstPoint.x
                    val dy = pos.y - firstPoint.y
                    char to (dx * dx + dy * dy)
                }.sortedBy { it.second }
                .take(8)
                .filter { it.second < SwipeDetectionConstants.CLOSE_KEY_DISTANCE_THRESHOLD_SQ }
                .map { it.first }
                .toSet()
        }

        private fun calculateRatioPenalty(
            pointsPerLetter: Float,
            optimalRatio: Float,
        ): Float =
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

        private fun calculateFrequencyBoost(rawFrequency: Long): Float =
            when {
                rawFrequency > 10_000_000L -> 1.20f
                rawFrequency > 5_000_000L -> 1.15f
                rawFrequency > 2_000_000L -> 1.10f
                else -> 1.0f
            }

        private fun determineFinalWeights(
            entry: DictionaryEntry,
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

            val frequencyRatio =
                if (maxFrequencySeen > 0) {
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

        private fun selectTopCandidatesWithGeometricDisambiguation(
            candidates: List<ScoredCandidate>,
            geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
            keyPositions: Map<Char, PointF>,
        ): List<WordCandidate> {
            if (candidates.isEmpty()) return emptyList()

            val sorted = candidates.sortedByDescending { it.combinedScore }
            val top = sorted.take(10)

            if (top.size < 2) {
                return top.map { WordCandidate(it.word, it.spatialScore, it.frequencyScore, it.combinedScore) }
            }

            val leader = top[0]
            val runnerUp = top[1]
            val scoreDelta = leader.combinedScore - runnerUp.combinedScore

            if (scoreDelta < GeometricScoringConstants.GEOMETRIC_SIMILARITY_THRESHOLD) {
                val disambiguated =
                    disambiguateCloseCompetitors(
                        leader,
                        runnerUp,
                        geometricAnalysis,
                        keyPositions,
                    )
                val reordered = mutableListOf(disambiguated.first, disambiguated.second)
                reordered.addAll(top.drop(2))

                return reordered.take(3).map {
                    WordCandidate(it.word, it.spatialScore, it.frequencyScore, it.combinedScore)
                }
            }

            return top.take(3).map {
                WordCandidate(it.word, it.spatialScore, it.frequencyScore, it.combinedScore)
            }
        }

        private fun disambiguateCloseCompetitors(
            candidate1: ScoredCandidate,
            candidate2: ScoredCandidate,
            geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
            keyPositions: Map<Char, PointF>,
        ): Pair<ScoredCandidate, ScoredCandidate> {
            val inflectionScore1 = calculateInflectionAlignment(candidate1.word, geometricAnalysis, keyPositions)
            val inflectionScore2 = calculateInflectionAlignment(candidate2.word, geometricAnalysis, keyPositions)

            val coverageScore1 = candidate1.pathCoverage
            val coverageScore2 = candidate2.pathCoverage

            val freqScore1 = candidate1.frequencyScore
            val freqScore2 = candidate2.frequencyScore

            val originalLeaderBonus1 = if (candidate1.combinedScore > candidate2.combinedScore) 0.1f else 0f
            val originalLeaderBonus2 = if (candidate2.combinedScore > candidate1.combinedScore) 0.1f else 0f

            val tiebreaker1 = inflectionScore1 * 0.4f + coverageScore1 * 0.2f + freqScore1 * 0.3f + originalLeaderBonus1
            val tiebreaker2 = inflectionScore2 * 0.4f + coverageScore2 * 0.2f + freqScore2 * 0.3f + originalLeaderBonus2

            return if (tiebreaker1 >= tiebreaker2) {
                val boostedWinner = candidate1.copy(combinedScore = maxOf(candidate1.combinedScore, candidate2.combinedScore) + 0.001f)
                boostedWinner to candidate2
            } else {
                val boostedWinner = candidate2.copy(combinedScore = maxOf(candidate1.combinedScore, candidate2.combinedScore) + 0.001f)
                boostedWinner to candidate1
            }
        }

        private fun calculateInflectionAlignment(
            word: String,
            geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
            keyPositions: Map<Char, PointF>,
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

        private fun calculateGeometricSpatialScore(
            word: String,
            swipePath: List<SwipePoint>,
            keyPositions: Map<Char, PointF>,
            uniqueLetterCount: Int,
            letterPathIndices: ArrayList<Int>,
            letterScores: ArrayList<Pair<Char, Float>>,
            ratioQuality: Float,
            geometricAnalysis: PathGeometryAnalyzer.GeometricAnalysis,
            isClusteredWord: Boolean,
        ): Float {
            if (swipePath.isEmpty()) return 0f

            letterPathIndices.clear()
            letterScores.clear()

            var totalScore = 0f
            val sigmaCache = cachedAdaptiveSigmas

            for (letterIndex in word.indices) {
                val char = word[letterIndex]
                val lowerChar = char.lowercaseChar()
                val keyPos = keyPositions[lowerChar] ?: return 0f

                val isFirstLetter = letterIndex == 0
                val isLastLetter = letterIndex == word.length - 1

                val adaptiveSigma = sigmaCache[lowerChar]?.sigma ?: GeometricScoringConstants.DEFAULT_SIGMA
                val effectiveSigma =
                    if (isClusteredWord) {
                        adaptiveSigma * GeometricScoringConstants.CLUSTERED_SEQUENCE_TOLERANCE_MULTIPLIER
                    } else {
                        adaptiveSigma
                    }

                val twoSigmaSquared = 2f * effectiveSigma * effectiveSigma
                val expThreshold = (2.5f * effectiveSigma) * (2.5f * effectiveSigma)

                val searchRange =
                    when {
                        isFirstLetter -> (swipePath.size * 0.30).toInt().coerceAtLeast(3)
                        isLastLetter -> swipePath.size - (swipePath.size * 0.30).toInt().coerceAtLeast(swipePath.size - 3)
                        else -> swipePath.size
                    }

                var minTotalDistance = Float.MAX_VALUE
                var minDistanceSquared = Float.MAX_VALUE
                var closestPointIndex = -1
                var velocityAtClosest = 0f

                val searchStart = if (isLastLetter) swipePath.size - searchRange else 0
                val searchEnd = if (isFirstLetter) searchRange else swipePath.size

                val expectedPathProgress =
                    if (word.length > 1) {
                        letterIndex.toFloat() / (word.length - 1).toFloat()
                    } else {
                        0.5f
                    }
                val expectedPathIndex = (expectedPathProgress * (swipePath.size - 1)).toInt()

                val positionPenaltyFactor = if (isClusteredWord) 200f else 150f

                for (relativeIndex in 0 until (searchEnd - searchStart)) {
                    val pointIndex = searchStart + relativeIndex
                    val point = swipePath[pointIndex]
                    val dx = keyPos.x - point.x
                    val dy = keyPos.y - point.y
                    val spatialDistanceSquared = dx * dx + dy * dy

                    val positionDeviation = kotlin.math.abs(pointIndex - expectedPathIndex).toFloat()
                    val positionPenalty = positionDeviation * positionPenaltyFactor

                    val totalDistance = spatialDistanceSquared + positionPenalty

                    if (totalDistance < minTotalDistance) {
                        minTotalDistance = totalDistance
                        minDistanceSquared = spatialDistanceSquared
                        closestPointIndex = pointIndex
                        velocityAtClosest = point.velocity

                        if (spatialDistanceSquared < 100f && positionDeviation < 2f) {
                            break
                        }
                    }
                }

                var letterScore =
                    if (minDistanceSquared > expThreshold) {
                        0.0f
                    } else {
                        exp(-minDistanceSquared / twoSigmaSquared)
                    }

                val velocityWeight = pathGeometryAnalyzer.calculateVelocityWeight(velocityAtClosest)
                letterScore *= velocityWeight

                val inflectionBoost =
                    pathGeometryAnalyzer.getInflectionBoost(
                        lowerChar,
                        geometricAnalysis,
                    )
                letterScore *= inflectionBoost

                if (pathGeometryAnalyzer.didPathTraverseKey(lowerChar, geometricAnalysis)) {
                    letterScore = maxOf(letterScore, GeometricScoringConstants.TRAVERSAL_FLOOR_SCORE)
                }

                if (letterIndex > 0 && word[letterIndex] == word[letterIndex - 1]) {
                    val repeatedLetterBoost =
                        pathGeometryAnalyzer.detectRepeatedLetterSignal(
                            swipePath,
                            keyPos,
                            letterPathIndices.lastOrNull() ?: 0,
                            closestPointIndex,
                        )
                    letterScore *= (1f + repeatedLetterBoost)
                }

                letterPathIndices.add(closestPointIndex)
                letterScores.add(char to letterScore)
                totalScore += letterScore
            }

            val sequencePenalty =
                calculateSequencePenalty(
                    word,
                    letterPathIndices,
                    uniqueLetterCount,
                    isClusteredWord,
                )

            val baseSpatialScore = totalScore / word.length.toFloat()

            val wrongLetterPenalty = calculateWrongLetterPenalty(letterScores, word.length)

            val pathExhaustionPenalty =
                calculatePathExhaustionPenalty(
                    word,
                    letterPathIndices,
                    swipePath.size,
                )

            val lengthBonus = calculateLengthBonus(word.length, ratioQuality)

            val spatialWithBonuses =
                (baseSpatialScore * sequencePenalty * lengthBonus * wrongLetterPenalty * pathExhaustionPenalty)
                    .coerceAtMost(1.0f)

            val repetitionCount = word.length - uniqueLetterCount
            val repetitionRatio = repetitionCount.toFloat() / word.length.toFloat()
            val repetitionPenalty =
                if (repetitionRatio > 0.30f) {
                    1.0f - ((repetitionCount - 1) * SwipeDetectionConstants.REPETITION_PENALTY_FACTOR).coerceAtMost(0.20f)
                } else {
                    1.0f
                }

            return spatialWithBonuses * repetitionPenalty
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

            val baseTolerableViolations =
                when {
                    word.length <= 4 -> 0
                    word.length <= 6 -> 1
                    else -> 1
                }

            val adjustedTolerance =
                if (isClusteredWord) {
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
            if (ratioQuality < SwipeDetectionConstants.LENGTH_BONUS_MIN_RATIO_QUALITY) {
                return 1.0f
            }

            return when {
                wordLength >= 8 -> 1.25f
                wordLength == 7 -> 1.18f
                wordLength == 6 -> 1.12f
                wordLength == 5 -> 1.06f
                else -> 1.0f
            }
        }

        private fun couldMatchPath(
            word: String,
            charsInBounds: Set<Char>,
        ): Boolean {
            var charsInBoundsCount = 0
            for (char in word) {
                if (char.lowercaseChar() in charsInBounds) {
                    charsInBoundsCount++
                }
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
            _swipeListener = null
            keyCharacterPositions = emptyMap()
            reset()
        }
    }
