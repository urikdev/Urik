@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.MotionEvent
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.AdaptiveDimensions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Word candidate with scoring metrics.
 */
data class WordCandidate(val word: String, val spatialScore: Float, val frequencyScore: Float, val combinedScore: Float)

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
constructor(private val streamingScoringEngine: StreamingScoringEngine) {
    /**
     * Captured swipe point with metadata.
     */
    data class SwipePoint(
        val x: Float,
        val y: Float,
        val timestamp: Long,
        val pressure: Float = 1.0f,
        val velocity: Float = 0.0f
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
        COMMON
        ;

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
        val uniqueLowercaseChars: Set<Char> = emptySet()
    )

    @Suppress("ktlint:standard:backing-property-naming")
    private var _swipeListener: SwipeListener? = null

    private val ringBuffer = SwipePointRingBuffer()
    private val interpolator = GestureInterpolator(ringBuffer)

    init {
        streamingScoringEngine.bindRingBuffer(ringBuffer)
    }

    private var lastUpdateTime = 0L
    private var isSwiping = false
    private var startTime = 0L
    private var pointCounter = 0
    private var firstPointX = 0f
    private var firstPointY = 0f
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
    private var swipeActivationDp = SWIPE_START_DISTANCE_DP
    private var currentDensity = 1f

    @Volatile
    private var layoutScaleFactor = 1.0f

    @Volatile
    private var layoutOffsetX = 0f

    @Volatile
    private var keyCharacterPositions = emptyMap<Char, PointF>()

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + scopeJob)
    private var scoringJob: Job? = null

    private val cachedTransformPoint = PointF()

    /**
     * Updates layout transform for adaptive keyboard modes.
     */
    fun updateLayoutTransform(scaleFactor: Float, offsetX: Float) {
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

    private fun transformTouchCoordinate(x: Float, y: Float): PointF {
        cachedTransformPoint.set(
            (x - layoutOffsetX) / layoutScaleFactor,
            y
        )
        return cachedTransformPoint
    }

    /**
     * Updates script context when language or layout changes.
     */
    fun updateScriptContext(locale: ULocale, isRTL: Boolean = false, scriptCode: Int = UScript.LATIN) {
        currentLocale = locale
        currentIsRTL = isRTL
        currentScriptCode = scriptCode
    }

    fun updateActiveLanguages(languages: List<String>) {
        activeLanguages = languages
    }

    private fun getScriptCodeForLanguage(languageCode: String): Int = when (languageCode) {
        "en", "es", "pl", "pt", "de", "cs", "sv" -> UScript.LATIN
        "ru", "uk" -> UScript.CYRILLIC
        "fa" -> UScript.ARABIC
        else -> UScript.LATIN
    }

    private fun areLayoutsCompatible(script1: Int, script2: Int): Boolean = when (script1) {
        in LATIN_LIKE_SCRIPTS if script2 in LATIN_LIKE_SCRIPTS -> true
        in ARABIC_SCRIPTS if script2 in ARABIC_SCRIPTS -> true
        else -> false
    }

    private fun getCompatibleLanguagesForSwipe(activeLanguages: List<String>, currentScriptCode: Int): List<String> =
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
        currentDensity = density
        swipeStartDistancePx = swipeActivationDp * density
    }

    fun updateAdaptiveDimensions(dimensions: AdaptiveDimensions) {
        swipeActivationDp = dimensions.swipeActivationDp
        swipeStartDistancePx = swipeActivationDp * currentDensity
    }

    fun setSwipeListener(listener: SwipeListener?) {
        this._swipeListener = listener
    }

    fun updateLastCommittedWord(word: String) {
        streamingScoringEngine.lastCommittedWord = word
    }

    fun updateCurrentLanguage(tag: String) {
        streamingScoringEngine.currentLanguageTag = tag
    }

    /**
     * Processes touch events for swipe detection.
     *
     * @return true if event consumed (swipe in progress), false if should propagate
     */
    @Suppress("ReturnCount")
    fun handleTouchEvent(event: MotionEvent, keyAt: (Float, Float) -> KeyboardKey?): Boolean {
        if (!swipeEnabled) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTime = System.currentTimeMillis()
                    return false
                }

                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - startTime
                    if (duration in 1..TAP_DURATION_THRESHOLD_MS) {
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

    private fun startSwipeDetection(event: MotionEvent, key: KeyboardKey) {
        scoringJob?.cancel()
        reset()
        startTime = System.currentTimeMillis()
        pointCounter = 0
        startingKey = key

        val transformed = transformTouchCoordinate(event.x, event.y)
        firstPointX = transformed.x
        firstPointY = transformed.y

        interpolator.onRawPoint(
            transformed.x,
            transformed.y,
            event.eventTime,
            event.pressure,
            0f
        )
        lastCheckX = transformed.x
    }

    private fun trackSwipeGesture(event: MotionEvent, keyAt: (Float, Float) -> KeyboardKey?) {
        if (firstPointX == 0f && firstPointY == 0f) return

        val now = System.currentTimeMillis()
        val timeSinceDown = now - startTime
        val distance = calculateDistance(firstPointX, firstPointY, event.x, event.y)

        if (lastCheckX != 0f) {
            val deltaX = event.x - lastCheckX
            if (lastDeltaX != 0f && deltaX != 0f && lastDeltaX > 0 != deltaX > 0) {
                directionReversals++
            }
            lastDeltaX = deltaX
        }
        lastCheckX = event.x

        for (h in 0 until event.historySize) {
            val histX = event.getHistoricalX(h)
            val histY = event.getHistoricalY(h)
            val histTime = event.getHistoricalEventTime(h)
            val histTransformed = transformTouchCoordinate(histX, histY)
            val histLastPoint = ringBuffer.peekLast()

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
                interpolator.onRawPoint(
                    histTransformed.x,
                    histTransformed.y,
                    histTime,
                    event.getHistoricalPressure(h),
                    histVelocity
                )
            }
        }

        val transformed = transformTouchCoordinate(event.x, event.y)
        val lastPoint = ringBuffer.peekLast()
        val velocityFromLast =
            if (lastPoint != null && timeSinceDown > 0) {
                val dx = transformed.x - lastPoint.x
                val dy = transformed.y - lastPoint.y
                sqrt(dx * dx + dy * dy) / (event.eventTime - lastPoint.timestamp).coerceAtLeast(1L).toFloat()
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
            interpolator.onRawPoint(
                transformed.x,
                transformed.y,
                event.eventTime,
                event.pressure,
                velocityFromLast
            )
        }

        if (ringBuffer.size >= 3 && timeSinceDown >= SWIPE_TIME_THRESHOLD_MS) {
            val snapshot = ringBuffer.snapshot()
            var largeGapCount = 0
            for (i in 0 until snapshot.size - 1) {
                val prev = snapshot[i]
                val curr = snapshot[i + 1]
                if (calculateDistance(prev.x, prev.y, curr.x, curr.y) > MAX_CONSECUTIVE_GAP_PX) {
                    largeGapCount++
                }
            }

            val gapRatio = largeGapCount.toFloat() / (snapshot.size - 1)
            if (gapRatio > 0.5f) {
                reset()
                return
            }
        }

        if (directionReversals >= 3) {
            reset()
            return
        }

        val isHighVelocity = timeSinceDown < SWIPE_TIME_THRESHOLD_MS
        val effectiveDistance =
            if (isHighVelocity) {
                swipeStartDistancePx * HIGH_VELOCITY_DISTANCE_MULTIPLIER
            } else {
                swipeStartDistancePx
            }

        if (distance > effectiveDistance) {
            val currentKey = keyAt(event.x, event.y)
            if (currentKey == startingKey) {
                return
            }

            val avgVelocity = distance / timeSinceDown.coerceAtLeast(1L).toFloat()
            if (avgVelocity > MAX_SWIPE_VELOCITY_PX_PER_MS) {
                return
            }

            if (isPeckLikeMotion()) {
                return
            }

            if (isGhostPath(distance, avgVelocity)) {
                return
            }

            isSwiping = true
            pointCounter = ringBuffer.size

            val compatibleLanguages = getCompatibleLanguagesForSwipe(activeLanguages, currentScriptCode)
            streamingScoringEngine.startGesture(
                keyCharacterPositions,
                compatibleLanguages,
                streamingScoringEngine.currentLanguageTag
            )

            cachedTransformPoint.set(firstPointX, firstPointY)
            _swipeListener?.onSwipeStart(cachedTransformPoint)

            val buffered = ringBuffer.snapshot()
            for (i in 1 until buffered.size) {
                cachedTransformPoint.set(buffered[i].x, buffered[i].y)
                _swipeListener?.onSwipeUpdate(cachedTransformPoint)
            }
            lastUpdateTime = System.currentTimeMillis()
        }
    }

    private fun isPeckLikeMotion(): Boolean {
        val snapshot = ringBuffer.snapshot()
        val pointCount = snapshot.size
        if (pointCount < 3) return false

        val first = snapshot[0]
        val last = snapshot[pointCount - 1]
        val totalDuration = last.timestamp - first.timestamp
        if (totalDuration <= 0) return false

        val midTimestamp = first.timestamp + totalDuration / 2
        var midPointIndex = 0
        var minTimeDiff = Long.MAX_VALUE
        for (i in 1 until pointCount - 1) {
            val diff = kotlin.math.abs(snapshot[i].timestamp - midTimestamp)
            if (diff < minTimeDiff) {
                minTimeDiff = diff
                midPointIndex = i
            }
        }

        if (midPointIndex == 0) return false

        val midPoint = snapshot[midPointIndex]
        val earlyDisplacement = calculateDistance(first.x, first.y, midPoint.x, midPoint.y)
        val lateDisplacement = calculateDistance(midPoint.x, midPoint.y, last.x, last.y)
        val totalDisplacement = earlyDisplacement + lateDisplacement
        if (totalDisplacement <= 0f) return false

        return lateDisplacement / totalDisplacement > PECK_LATE_DISPLACEMENT_RATIO
    }

    private fun isGhostPath(totalDistance: Float, avgVelocity: Float): Boolean {
        if (hasImpossibleGap()) return true
        if (isSparsePath(totalDistance, avgVelocity)) return true
        if (isSlideOffStart(avgVelocity)) return true
        return false
    }

    private fun hasImpossibleGap(): Boolean {
        val snapshot = ringBuffer.snapshot()
        val threshold = GHOST_IMPOSSIBLE_GAP_PX
        val thresholdSq = threshold * threshold
        for (i in 0 until snapshot.size - 1) {
            val p1 = snapshot[i]
            val p2 = snapshot[i + 1]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val dt = (p2.timestamp - p1.timestamp).coerceAtLeast(1L)
            if (dx * dx + dy * dy > thresholdSq && dt <= 16L) {
                return true
            }
        }
        return false
    }

    private fun isSparsePath(totalDistance: Float, avgVelocity: Float): Boolean {
        if (avgVelocity < GHOST_DENSITY_VELOCITY_GATE) return false
        if (totalDistance < 1f) return false
        val density = ringBuffer.size.toFloat() / totalDistance
        return density < GHOST_MIN_PATH_DENSITY
    }

    private fun isSlideOffStart(avgVelocity: Float): Boolean {
        val snapshot = ringBuffer.snapshot()
        if (snapshot.size < 3) return false
        if (avgVelocity < GHOST_DENSITY_VELOCITY_GATE) return false

        val p0 = snapshot[0]
        val p1 = snapshot[1]
        val dt01 = (p1.timestamp - p0.timestamp).coerceAtLeast(1L).toFloat()
        val dx01 = p1.x - p0.x
        val dy01 = p1.y - p0.y
        val initialVelocity = sqrt(dx01 * dx01 + dy01 * dy01) / dt01

        if (initialVelocity < GHOST_START_MOMENTUM_VELOCITY) return false

        val checkEnd = minOf(snapshot.size, GHOST_START_INTENT_POINTS)
        for (i in 2 until checkEnd) {
            val prev = snapshot[i - 1]
            val curr = snapshot[i]
            val dt = (curr.timestamp - prev.timestamp).coerceAtLeast(1L).toFloat()
            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            val v = sqrt(dx * dx + dy * dy) / dt

            if (v < initialVelocity * GHOST_START_SLOWDOWN_RATIO) {
                return false
            }

            val prevDx = prev.x - snapshot[i - 2].x
            val prevDy = prev.y - snapshot[i - 2].y
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

    private fun shouldSamplePoint(newX: Float, newY: Float, counter: Int, velocity: Float): Boolean {
        if (ringBuffer.size < MIN_SWIPE_POINTS_FOR_SAMPLING) return true

        val lastPoint = ringBuffer.peekLast() ?: return true

        val dx = newX - lastPoint.x
        val dy = newY - lastPoint.y
        val distanceSquared = dx * dx + dy * dy

        if (distanceSquared < MIN_POINT_DISTANCE * MIN_POINT_DISTANCE) return false

        val samplingInterval =
            when {
                ringBuffer.size < ADAPTIVE_THRESHOLD -> {
                    MIN_SAMPLING_INTERVAL
                }

                ringBuffer.size < SwipePointRingBuffer.CAPACITY * ADAPTIVE_THRESHOLD_RATIO -> {
                    MIN_SAMPLING_INTERVAL +
                        2
                }

                else -> {
                    MAX_SAMPLING_INTERVAL
                }
            }

        if (counter % samplingInterval != 0) return false

        val isSlowPreciseMovement = velocity < SLOW_MOVEMENT_VELOCITY_THRESHOLD && ringBuffer.size > 10
        if (isSlowPreciseMovement) {
            return counter % MIN_SAMPLING_INTERVAL == 0
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
            val histLastPoint = ringBuffer.peekLast()

            val histVelocity =
                if (histLastPoint != null) {
                    val dx = histTransformed.x - histLastPoint.x
                    val dy = histTransformed.y - histLastPoint.y
                    val dt = (histTime - histLastPoint.timestamp).coerceAtLeast(1L).toFloat()
                    sqrt(dx * dx + dy * dy) / dt
                } else {
                    0f
                }

            if (shouldSamplePoint(histTransformed.x, histTransformed.y, pointCounter, histVelocity)) {
                interpolator.onRawPoint(
                    histTransformed.x,
                    histTransformed.y,
                    histTime,
                    event.getHistoricalPressure(h),
                    histVelocity
                )
            }
        }

        pointCounter++
        val transformed = transformTouchCoordinate(event.x, event.y)
        val velocity = calculateVelocity(event)

        if (shouldSamplePoint(transformed.x, transformed.y, pointCounter, velocity)) {
            interpolator.onRawPoint(
                transformed.x,
                transformed.y,
                event.eventTime,
                event.pressure,
                velocity
            )
        }

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime >= UI_UPDATE_INTERVAL_MS) {
            lastUpdateTime = now
            _swipeListener?.onSwipeUpdate(transformed)
        }
    }

    private fun endSwipeDetection(event: MotionEvent, keyAt: (Float, Float) -> KeyboardKey?): Boolean {
        if (isSwiping) {
            val transformed = transformTouchCoordinate(event.x, event.y)
            interpolator.onRawPoint(
                transformed.x,
                transformed.y,
                event.eventTime,
                event.pressure,
                calculateVelocity(event)
            )

            val pathSnapshot = ringBuffer.snapshot().toList()
            val rawPointCount = interpolator.rawPointCount

            _swipeListener?.onSwipeEnd()

            scoringJob =
                scope.launch {
                    try {
                        val topCandidates = streamingScoringEngine.finalize(pathSnapshot, rawPointCount)

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
            if (duration <= TAP_DURATION_THRESHOLD_MS) {
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

    private fun calculateVelocity(event: MotionEvent): Float {
        if (ringBuffer.size < 2) return 0.0f

        val lastPoint = ringBuffer.peekLast() ?: return 0.0f
        val distance = calculateDistance(lastPoint.x, lastPoint.y, event.x, event.y)
        val timeDelta = event.eventTime - lastPoint.timestamp

        return if (timeDelta > 0) distance / timeDelta else 0.0f
    }

    private fun calculateDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun reset() {
        isSwiping = false
        ringBuffer.reset()
        interpolator.reset()
        startTime = 0L
        pointCounter = 0
        firstPointX = 0f
        firstPointY = 0f
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
        streamingScoringEngine.cancelActiveGesture()
        _swipeListener = null
        keyCharacterPositions = emptyMap()
        reset()
    }

    private companion object {
        private const val MIN_SAMPLING_INTERVAL = 2
        private const val MAX_SAMPLING_INTERVAL = 8
        private const val ADAPTIVE_THRESHOLD = 40
        private const val ADAPTIVE_THRESHOLD_RATIO = 0.75
        private const val MIN_POINT_DISTANCE = 8f
        private const val MAX_CONSECUTIVE_GAP_PX = 45f
        private const val SWIPE_TIME_THRESHOLD_MS = 100L
        private const val SWIPE_START_DISTANCE_DP = 35f
        private const val MIN_SWIPE_POINTS_FOR_SAMPLING = 3
        private const val SLOW_MOVEMENT_VELOCITY_THRESHOLD = 0.5f
        private const val UI_UPDATE_INTERVAL_MS = 16
        private const val TAP_DURATION_THRESHOLD_MS = 350L
        private const val MAX_SWIPE_VELOCITY_PX_PER_MS = 9f
        private const val PECK_LATE_DISPLACEMENT_RATIO = 0.95f
        private const val HIGH_VELOCITY_DISTANCE_MULTIPLIER = 1.5f
        private const val GHOST_DENSITY_VELOCITY_GATE = 2.0f
        private const val GHOST_MIN_PATH_DENSITY = 0.025f
        private const val GHOST_START_MOMENTUM_VELOCITY = 3.0f
        private const val GHOST_START_INTENT_POINTS = 4
        private const val GHOST_START_SLOWDOWN_RATIO = 0.7f
        private const val GHOST_IMPOSSIBLE_GAP_PX = 200f

        private val LATIN_LIKE_SCRIPTS = setOf(UScript.LATIN, UScript.CYRILLIC)
        private val ARABIC_SCRIPTS = setOf(UScript.ARABIC)
    }
}
