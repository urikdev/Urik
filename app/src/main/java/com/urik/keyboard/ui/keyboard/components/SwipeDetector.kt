@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.util.Log
import android.view.MotionEvent
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.KeyboardConstants.GeometricScoringConstants
import com.urik.keyboard.KeyboardConstants.SwipeDetectionConstants
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.service.WordNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
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
 * Detects swipe gestures and orchestrates the scoring pipeline.
 *
 * Touch handling and path collection live here. Scoring is delegated to
 * [ResidualScorer], tie-breaking to [ZipfCheck], and path feature
 * extraction to [SwipeSignal].
 */
@Singleton
class SwipeDetector
    @Inject
    constructor(
        private val spellCheckManager: SpellCheckManager,
        private val wordLearningEngine: WordLearningEngine,
        private val pathGeometryAnalyzer: PathGeometryAnalyzer,
        private val wordFrequencyRepository: WordFrequencyRepository,
        private val residualScorer: ResidualScorer,
        private val zipfCheck: ZipfCheck,
        private val wordNormalizer: WordNormalizer,
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
         * Callback interface for swipe events.
         */
        interface SwipeListener {
            fun onSwipeStart(startPoint: PointF)

            fun onSwipeUpdate(currentPoint: PointF)

            fun onSwipeEnd()

            fun onSwipeResults(candidates: List<WordCandidate>)

            fun onTap(key: KeyboardKey)
        }

        enum class FrequencyTier {
            TOP_100,
            TOP_1000,
            TOP_5000,
            COMMON;

            companion object {
                fun fromRank(rank: Int): FrequencyTier = when {
                    rank < 100 -> TOP_100
                    rank < 1000 -> TOP_1000
                    rank < 5000 -> TOP_5000
                    else -> COMMON
                }
            }
        }

        data class DictionaryEntry(
            val word: String,
            val frequencyScore: Float,
            val rawFrequency: Long,
            val firstChar: Char,
            val uniqueLetterCount: Int,
            val frequencyTier: FrequencyTier = FrequencyTier.COMMON,
        )

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
        private var cachedKeyNeighborhoods = emptyMap<Char, PathGeometryAnalyzer.KeyNeighborhood>()

        @Volatile
        private var lastKeyPositionsHash = 0

        @Volatile
        private var lastCommittedWord: String = ""

        @Volatile
        private var currentLanguageTag: String = "en"

        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(Dispatchers.Default + scopeJob)
        private var scoringJob: Job? = null

        private val cachedTransformPoint = PointF()

        /**
         * Updates layout transform for adaptive keyboard modes.
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
        ): PointF {
            cachedTransformPoint.set(
                (x - layoutOffsetX) / layoutScaleFactor,
                y,
            )
            return cachedTransformPoint
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
            this._swipeListener = listener
        }

        fun updateLastCommittedWord(word: String) {
            lastCommittedWord = word
        }

        fun updateCurrentLanguage(tag: String) {
            currentLanguageTag = tag
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

                if (swipePoints.size >= 3 && timeSinceDown >= SwipeDetectionConstants.SWIPE_TIME_THRESHOLD_MS) {
                    var largeGapCount = 0
                    for (i in 0 until swipePoints.size - 1) {
                        val prev = swipePoints[i]
                        val curr = swipePoints[i + 1]
                        if (calculateDistance(prev.x, prev.y, curr.x, curr.y) > SwipeDetectionConstants.MAX_CONSECUTIVE_GAP_PX) {
                            largeGapCount++
                        }
                    }

                    val gapRatio = largeGapCount.toFloat() / (swipePoints.size - 1)
                    if (gapRatio > 0.5f) {
                        reset()
                        return
                    }
                }

                if (directionReversals >= 3) {
                    reset()
                    return
                }

                val isHighVelocity = timeSinceDown < SwipeDetectionConstants.SWIPE_TIME_THRESHOLD_MS
                val effectiveDistance =
                    if (isHighVelocity) {
                        swipeStartDistancePx * SwipeDetectionConstants.HIGH_VELOCITY_DISTANCE_MULTIPLIER
                    } else {
                        swipeStartDistancePx
                    }

                if (distance > effectiveDistance) {
                    val currentKey = keyAt(event.x, event.y)
                    if (currentKey == startingKey) {
                        return
                    }

                    val avgVelocity = distance / timeSinceDown.coerceAtLeast(1L).toFloat()
                    if (avgVelocity > SwipeDetectionConstants.MAX_SWIPE_VELOCITY_PX_PER_MS) {
                        return
                    }

                    if (isPeckLikeMotion()) {
                        return
                    }

                    if (isGhostPath(distance, avgVelocity)) {
                        return
                    }

                    isSwiping = true
                    pointCounter = swipePoints.size
                    cachedTransformPoint.set(start.x, start.y)
                    _swipeListener?.onSwipeStart(cachedTransformPoint)
                    updateSwipePath(event)
                }
            }
        }

        private fun isPeckLikeMotion(): Boolean {
            val pointCount = swipePoints.size
            if (pointCount < 3) return false

            val first = swipePoints[0]
            val last = swipePoints[pointCount - 1]
            val totalDuration = last.timestamp - first.timestamp
            if (totalDuration <= 0) return false

            val midTimestamp = first.timestamp + totalDuration / 2
            var midPointIndex = 0
            var minTimeDiff = Long.MAX_VALUE
            for (i in 1 until pointCount - 1) {
                val diff = kotlin.math.abs(swipePoints[i].timestamp - midTimestamp)
                if (diff < minTimeDiff) {
                    minTimeDiff = diff
                    midPointIndex = i
                }
            }

            if (midPointIndex == 0) return false

            val midPoint = swipePoints[midPointIndex]
            val earlyDisplacement = calculateDistance(first.x, first.y, midPoint.x, midPoint.y)
            val lateDisplacement = calculateDistance(midPoint.x, midPoint.y, last.x, last.y)
            val totalDisplacement = earlyDisplacement + lateDisplacement
            if (totalDisplacement <= 0f) return false

            return lateDisplacement / totalDisplacement > SwipeDetectionConstants.PECK_LATE_DISPLACEMENT_RATIO
        }

        private fun isGhostPath(
            totalDistance: Float,
            avgVelocity: Float,
        ): Boolean {
            if (hasImpossibleGap()) return true
            if (isSparsePath(totalDistance, avgVelocity)) return true
            if (isSlideOffStart(avgVelocity)) return true
            return false
        }

        private fun hasImpossibleGap(): Boolean {
            val threshold = SwipeDetectionConstants.GHOST_IMPOSSIBLE_GAP_PX
            val thresholdSq = threshold * threshold
            for (i in 0 until swipePoints.size - 1) {
                val p1 = swipePoints[i]
                val p2 = swipePoints[i + 1]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val dt = (p2.timestamp - p1.timestamp).coerceAtLeast(1L)
                if (dx * dx + dy * dy > thresholdSq && dt <= 16L) {
                    return true
                }
            }
            return false
        }

        private fun isSparsePath(
            totalDistance: Float,
            avgVelocity: Float,
        ): Boolean {
            if (avgVelocity < SwipeDetectionConstants.GHOST_DENSITY_VELOCITY_GATE) return false
            if (totalDistance < 1f) return false
            val density = swipePoints.size.toFloat() / totalDistance
            return density < SwipeDetectionConstants.GHOST_MIN_PATH_DENSITY
        }

        private fun isSlideOffStart(avgVelocity: Float): Boolean {
            if (swipePoints.size < 3) return false
            if (avgVelocity < SwipeDetectionConstants.GHOST_DENSITY_VELOCITY_GATE) return false

            val p0 = swipePoints[0]
            val p1 = swipePoints[1]
            val dt01 = (p1.timestamp - p0.timestamp).coerceAtLeast(1L).toFloat()
            val dx01 = p1.x - p0.x
            val dy01 = p1.y - p0.y
            val initialVelocity = sqrt(dx01 * dx01 + dy01 * dy01) / dt01

            if (initialVelocity < SwipeDetectionConstants.GHOST_START_MOMENTUM_VELOCITY) return false

            val checkEnd = minOf(swipePoints.size, SwipeDetectionConstants.GHOST_START_INTENT_POINTS)
            for (i in 2 until checkEnd) {
                val prev = swipePoints[i - 1]
                val curr = swipePoints[i]
                val dt = (curr.timestamp - prev.timestamp).coerceAtLeast(1L).toFloat()
                val dx = curr.x - prev.x
                val dy = curr.y - prev.y
                val v = sqrt(dx * dx + dy * dy) / dt

                if (v < initialVelocity * SwipeDetectionConstants.GHOST_START_SLOWDOWN_RATIO) {
                    return false
                }

                val prevDx = prev.x - swipePoints[i - 2].x
                val prevDy = prev.y - swipePoints[i - 2].y
                val dot = prevDx * dx + prevDy * dy
                val prevLen = sqrt(prevDx * prevDx + prevDy * prevDy)
                val currLen = sqrt(dx * dx + dy * dy)
                if (prevLen > 0.1f && currLen > 0.1f) {
                    val cosAngle = dot / (prevLen * currLen)
                    if (cosAngle < 0.7f) {
                        return false
                    }
                }
            }

            return true
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
                _swipeListener?.onSwipeUpdate(transformed)
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

                val pathSnapshot = ArrayList(swipePoints)

                _swipeListener?.onSwipeEnd()

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
            withContext(Dispatchers.Default) {
                try {
                    if (swipePath.isEmpty()) return@withContext emptyList()

                    val keyPositionsSnapshot = keyCharacterPositions
                    if (keyPositionsSnapshot.isEmpty()) return@withContext emptyList()

                    val interpolatedPath = interpolatePathForFastSegments(swipePath, keyPositionsSnapshot)

                    val minLength = 2
                    val maxLength = (interpolatedPath.size / 5).coerceIn(5, 20)

                    val compatibleLanguages = getCompatibleLanguagesForSwipe(activeLanguages, currentScriptCode)
                    val wordFrequencyMap = loadOrCacheDictionary(compatibleLanguages, minLength, maxLength)
                    if (wordFrequencyMap.isEmpty()) return@withContext emptyList()

                    val bigramPredictions: Set<String> =
                        if (lastCommittedWord.isNotBlank()) {
                            wordFrequencyRepository.getBigramPredictions(lastCommittedWord, currentLanguageTag).toSet()
                        } else {
                            emptySet()
                        }

                    updateAdaptiveSigmaCache(keyPositionsSnapshot)
                    val sigmaCache = cachedAdaptiveSigmas
                    val neighborhoodCache = cachedKeyNeighborhoods

                    val signal =
                        SwipeSignal.extract(
                            interpolatedPath,
                            keyPositionsSnapshot,
                            pathGeometryAnalyzer,
                            sigmaCache,
                        )

                    val dictionaryByFirstChar = buildDictionaryIndex(wordFrequencyMap, minLength, maxLength)
                    val relevantChars = signal.startAnchor.candidateKeys.ifEmpty { dictionaryByFirstChar.keys }
                    val dictionarySnapshot = relevantChars.flatMap { dictionaryByFirstChar[it] ?: emptyList() }

                    var maxFrequencySeen = 0L
                    val results = ArrayList<ResidualScorer.CandidateResult>(dictionarySnapshot.size / 4)

                    for (i in dictionarySnapshot.indices) {
                        if (i % 50 == 0) yield()

                        val entry = dictionarySnapshot[i]
                        if (entry.rawFrequency > maxFrequencySeen) {
                            maxFrequencySeen = entry.rawFrequency
                        }

                        val result =
                            residualScorer.scoreCandidate(
                                entry,
                                signal,
                                keyPositionsSnapshot,
                                sigmaCache,
                                neighborhoodCache,
                                maxFrequencySeen,
                            ) ?: continue

                        results.add(result)

                        if (result.combinedScore > SwipeDetectionConstants.EXCELLENT_CANDIDATE_THRESHOLD) {
                            var excellentCount = 0
                            for (candidate in results) {
                                if (candidate.combinedScore > 0.90f) excellentCount++
                            }
                            if (excellentCount >= SwipeDetectionConstants.MIN_EXCELLENT_CANDIDATES) break
                        }
                    }

                    val arbitration =
                        zipfCheck.arbitrate(
                            results,
                            signal.geometricAnalysis,
                            keyPositionsSnapshot,
                            bigramPredictions,
                            wordFrequencyMap,
                            interpolatedPath.size,
                        )
                    return@withContext arbitration.candidates
                } catch (_: Exception) {
                    return@withContext emptyList()
                }
            }

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
                cachedKeyNeighborhoods = pathGeometryAnalyzer.computeKeyNeighborhoods(keyPositions)
                lastKeyPositionsHash = positionsHash
            }
        }

        private fun buildDictionaryIndex(
            wordFrequencyMap: Map<String, Int>,
            minLength: Int,
            maxLength: Int,
        ): Map<Char, List<DictionaryEntry>> {
            val filtered = wordFrequencyMap.entries
                .filter { (word, _) -> word.length in minLength..maxLength }
                .sortedByDescending { it.value }
            return filtered.mapIndexed { rank, (word, frequency) ->
                DictionaryEntry(
                    word = word,
                    frequencyScore = ln(frequency.toFloat() + 1f) / 20f,
                    rawFrequency = frequency.toLong(),
                    firstChar = wordNormalizer.stripDiacritics(word.first().toString()).first().lowercaseChar(),
                    uniqueLetterCount = word.toSet().size,
                    frequencyTier = FrequencyTier.fromRank(rank),
                )
            }.groupBy { it.firstChar }
        }

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
            cachedAdaptiveSigmas = emptyMap()
            cachedKeyNeighborhoods = emptyMap()
            reset()
        }

        companion object {
            private const val TAG = "SwipeEngine"
        }
    }
