@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.service.WordNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/** Incrementally prunes swipe candidates during the gesture via a 50ms ticker. */
@Singleton
class StreamingScoringEngine
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

    private val scoringExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "urik-swipe-scorer").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }
    private val scoringDispatcher = scoringExecutor.asCoroutineDispatcher()
    private val scoringScope = CoroutineScope(scoringDispatcher + SupervisorJob())

    private var tickerJob: Job? = null

    @Volatile private var keyPositions = emptyMap<Char, PointF>()
    @Volatile private var liveCandidates = ArrayList<SwipeDetector.DictionaryEntry>(LIVE_SET_CAPACITY)
    @Volatile private var gestureActive = false
    @Volatile private var tickCount = 0
    @Volatile private var gestureStartTimeNanos = 0L

    @Volatile private var cachedDictionary = emptyMap<String, Int>()
    @Volatile private var cachedLanguageCombination = emptyList<String>()
    @Volatile private var cachedAdaptiveSigmas = emptyMap<Char, PathGeometryAnalyzer.AdaptiveSigma>()
    @Volatile private var cachedKeyNeighborhoods = emptyMap<Char, PathGeometryAnalyzer.KeyNeighborhood>()
    @Volatile private var lastKeyPositionsHash = 0

    @Volatile var lastCommittedWord: String = ""
    @Volatile var currentLanguageTag: String = "en"

    private var fullDictionary = ArrayList<SwipeDetector.DictionaryEntry>()

    lateinit var ringBuffer: SwipePointRingBuffer
        private set

    fun bindRingBuffer(buffer: SwipePointRingBuffer) {
        ringBuffer = buffer
    }

    fun startGesture(
        currentKeyPositions: Map<Char, PointF>,
        activeLanguages: List<String>,
        languageTag: String,
    ) {
        cancelActiveGesture()

        keyPositions = currentKeyPositions
        currentLanguageTag = languageTag
        gestureActive = true
        tickCount = 0
        gestureStartTimeNanos = System.nanoTime()
        liveCandidates.clear()

        scoringScope.launch {
            try {
                val dictionary = loadOrCacheDictionary(activeLanguages)
                if (dictionary.isEmpty()) return@launch

                val indexed = buildDictionaryIndex(dictionary)
                fullDictionary = ArrayList(indexed)
                liveCandidates = ArrayList(indexed)

                updateAdaptiveSigmaCache(currentKeyPositions)
                startTicker()
            } catch (_: Exception) { }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scoringScope.launch {
            var nextTickNanos = System.nanoTime() + TICK_INTERVAL_NANOS

            while (isActive && gestureActive) {
                val now = System.nanoTime()
                val sleepMs = ((nextTickNanos - now) / 1_000_000L).coerceAtLeast(1L)
                delay(sleepMs)

                if (!gestureActive) break

                onTick()
                tickCount++
                nextTickNanos += TICK_INTERVAL_NANOS
            }
        }
    }

    private fun onTick() {
        if (!::ringBuffer.isInitialized) return
        val path = ringBuffer.snapshot()
        if (path.size < 3) return

        val currentKeyPositions = keyPositions
        if (currentKeyPositions.isEmpty()) return

        val elapsedMs = (System.nanoTime() - gestureStartTimeNanos) / 1_000_000L

        when {
            elapsedMs >= TRAVERSAL_PRUNE_MS && tickCount >= 3 -> {
                val charsInBounds = computeCharsInBounds(path, currentKeyPositions)
                val traversedKeys = computeTraversedKeys(path, currentKeyPositions)
                liveCandidates = ArrayList(pruneByTraversal(liveCandidates, traversedKeys))
                liveCandidates = ArrayList(pruneByBounds(liveCandidates, charsInBounds))
            }
            elapsedMs >= BOUNDS_PRUNE_MS && tickCount >= 2 -> {
                val charsInBounds = computeCharsInBounds(path, currentKeyPositions)
                liveCandidates = ArrayList(pruneByBounds(liveCandidates, charsInBounds))
            }
            elapsedMs >= ANCHOR_PRUNE_MS && tickCount >= 1 -> {
                val startAnchorKeys = computeStartAnchorKeys(path, currentKeyPositions)
                liveCandidates = ArrayList(pruneByStartAnchor(liveCandidates, startAnchorKeys))
            }
        }
    }

    fun cancelActiveGesture() {
        gestureActive = false
        tickerJob?.cancel()
        tickerJob = null
        liveCandidates.clear()
        tickCount = 0
    }

    suspend fun finalize(
        swipePath: List<SwipeDetector.SwipePoint>,
        rawPointCount: Int,
    ): List<WordCandidate> = withContext(scoringDispatcher) {
        gestureActive = false
        tickerJob?.cancel()

        if (swipePath.isEmpty()) return@withContext emptyList()

        val currentKeyPositions = keyPositions
        if (currentKeyPositions.isEmpty()) return@withContext emptyList()

        val maxLength = (rawPointCount / 5).coerceIn(5, 20)
        val unfilteredCandidates = if (liveCandidates.isNotEmpty()) {
            liveCandidates
        } else {
            fullDictionary
        }
        val candidates = unfilteredCandidates.filter { it.word.length <= maxLength }

        if (candidates.isEmpty()) return@withContext emptyList()

        val sigmaCache = cachedAdaptiveSigmas
        val neighborhoodCache = cachedKeyNeighborhoods

        val signal = SwipeSignal.extract(
            swipePath,
            currentKeyPositions,
            pathGeometryAnalyzer,
            sigmaCache,
            rawPointCount,
        )

        val bigramPredictions: Set<String> =
            if (lastCommittedWord.isNotBlank()) {
                wordFrequencyRepository.getBigramPredictions(
                    lastCommittedWord, currentLanguageTag,
                ).toSet()
            } else {
                emptySet()
            }

        var maxFrequencySeen = 0L
        val results = ArrayList<ResidualScorer.CandidateResult>(candidates.size / 4)

        for (i in candidates.indices) {
            if (i % 50 == 0) yield()

            val entry = candidates[i]
            if (entry.rawFrequency > maxFrequencySeen) {
                maxFrequencySeen = entry.rawFrequency
            }

            val result = residualScorer.scoreCandidate(
                entry, signal, currentKeyPositions,
                sigmaCache, neighborhoodCache, maxFrequencySeen,
            ) ?: continue

            results.add(result)

            if (result.combinedScore > EXCELLENT_CANDIDATE_THRESHOLD) {
                var excellentCount = 0
                for (candidate in results) {
                    if (candidate.combinedScore > 0.90f) excellentCount++
                }
                if (excellentCount >= MIN_EXCELLENT_CANDIDATES) break
            }
        }

        val wordFrequencyMap = cachedDictionary

        val arbitration = zipfCheck.arbitrate(
            results,
            signal.geometricAnalysis,
            currentKeyPositions,
            bigramPredictions,
            wordFrequencyMap,
            rawPointCount,
        )

        return@withContext arbitration.candidates
    }

    fun pruneByStartAnchor(
        candidates: List<SwipeDetector.DictionaryEntry>,
        startKeys: Set<Char>,
    ): List<SwipeDetector.DictionaryEntry> {
        if (startKeys.isEmpty()) return candidates
        return candidates.filter { it.firstChar in startKeys }
    }

    fun pruneByBounds(
        candidates: List<SwipeDetector.DictionaryEntry>,
        charsInBounds: Set<Char>,
    ): List<SwipeDetector.DictionaryEntry> {
        if (charsInBounds.isEmpty()) return candidates
        return candidates.filter { entry ->
            val uniqueChars = entry.word.lowercase().toSet()
            val outOfBoundsCount = uniqueChars.count { it !in charsInBounds }
            outOfBoundsCount <= BOUNDS_SAFETY_MARGIN
        }
    }

    fun pruneByTraversal(
        candidates: List<SwipeDetector.DictionaryEntry>,
        traversedKeys: Set<Char>,
    ): List<SwipeDetector.DictionaryEntry> {
        if (traversedKeys.size < 2) return candidates
        return candidates.filter { entry ->
            val wordChars = entry.word.lowercase().toSet()
            val traversedOverlap = wordChars.count { it in traversedKeys }
            traversedOverlap.toFloat() / wordChars.size >= TRAVERSAL_MIN_OVERLAP
        }
    }

    private fun computeStartAnchorKeys(
        path: List<SwipeDetector.SwipePoint>,
        positions: Map<Char, PointF>,
    ): Set<Char> {
        if (path.isEmpty()) return emptySet()

        val sampleCount = minOf(5, path.size)
        var cx = 0f
        var cy = 0f
        for (i in 0 until sampleCount) {
            cx += path[i].x
            cy += path[i].y
        }
        cx /= sampleCount
        cy /= sampleCount

        val thresholdSq = START_ANCHOR_RADIUS * START_ANCHOR_RADIUS
        val result = mutableSetOf<Char>()
        for ((char, pos) in positions) {
            val dx = pos.x - cx
            val dy = pos.y - cy
            if (dx * dx + dy * dy < thresholdSq) {
                result.add(char)
            }
        }
        return result
    }

    private fun computeCharsInBounds(
        path: List<SwipeDetector.SwipePoint>,
        positions: Map<Char, PointF>,
    ): Set<Char> {
        if (path.isEmpty()) return emptySet()

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (point in path) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }

        minX -= BOUNDS_MARGIN
        maxX += BOUNDS_MARGIN
        minY -= BOUNDS_MARGIN
        maxY += BOUNDS_MARGIN

        val result = mutableSetOf<Char>()
        for ((char, pos) in positions) {
            if (pos.x in minX..maxX && pos.y in minY..maxY) {
                result.add(char)
            }
        }
        return result
    }

    private fun computeTraversedKeys(
        path: List<SwipeDetector.SwipePoint>,
        positions: Map<Char, PointF>,
    ): Set<Char> {
        val result = mutableSetOf<Char>()
        val traversalRadiusSq = TRAVERSAL_RADIUS * TRAVERSAL_RADIUS

        for (point in path) {
            for ((char, pos) in positions) {
                if (char in result) continue
                val dx = point.x - pos.x
                val dy = point.y - pos.y
                if (dx * dx + dy * dy < traversalRadiusSq) {
                    result.add(char)
                }
            }
        }
        return result
    }

    private suspend fun loadOrCacheDictionary(
        compatibleLanguages: List<String>,
    ): Map<String, Int> {
        if (compatibleLanguages == cachedLanguageCombination && cachedDictionary.isNotEmpty()) {
            return cachedDictionary
        }

        val dictionaryWordsMap = spellCheckManager.getCommonWordsForLanguages(compatibleLanguages)
        val learnedWordsMap = wordLearningEngine.getLearnedWordsForSwipeAllLanguages(
            compatibleLanguages, 2, 20,
        )

        val mergedMap = HashMap<String, Int>(dictionaryWordsMap.size + learnedWordsMap.size)
        dictionaryWordsMap.forEach { (word, freq) -> mergedMap[word] = freq }
        learnedWordsMap.forEach { (word, freq) ->
            mergedMap[word] = maxOf(mergedMap[word] ?: 0, freq)
        }

        cachedDictionary = mergedMap
        cachedLanguageCombination = compatibleLanguages
        return mergedMap
    }

    private fun buildDictionaryIndex(
        wordFrequencyMap: Map<String, Int>,
    ): List<SwipeDetector.DictionaryEntry> {
        val sorted = wordFrequencyMap.entries
            .filter { (word, _) -> word.length in 2..20 }
            .sortedByDescending { it.value }

        return sorted.mapIndexed { rank, (word, frequency) ->
            SwipeDetector.DictionaryEntry(
                word = word,
                frequencyScore = ln(frequency.toFloat() + 1f) / 20f,
                rawFrequency = frequency.toLong(),
                firstChar = wordNormalizer.stripDiacritics(
                    word.first().toString(),
                ).first().lowercaseChar(),
                uniqueLetterCount = word.toSet().size,
                frequencyTier = SwipeDetector.FrequencyTier.fromRank(rank),
            )
        }
    }

    private fun updateAdaptiveSigmaCache(positions: Map<Char, PointF>) {
        val hash = positions.hashCode()
        if (hash != lastKeyPositionsHash) {
            val newSigmas = mutableMapOf<Char, PathGeometryAnalyzer.AdaptiveSigma>()
            positions.keys.forEach { char ->
                newSigmas[char] = pathGeometryAnalyzer.calculateAdaptiveSigma(char, positions)
            }
            cachedAdaptiveSigmas = newSigmas
            cachedKeyNeighborhoods = pathGeometryAnalyzer.computeKeyNeighborhoods(positions)
            lastKeyPositionsHash = hash
        }
    }

    fun shutdown() {
        cancelActiveGesture()
        scoringScope.cancel()
        scoringDispatcher.close()
        scoringExecutor.shutdown()
    }

    companion object {
        private const val TICK_INTERVAL_NANOS = 50_000_000L
        private const val ANCHOR_PRUNE_MS = 100L
        private const val BOUNDS_PRUNE_MS = 200L
        private const val TRAVERSAL_PRUNE_MS = 300L
        private const val START_ANCHOR_RADIUS = 85f
        private const val BOUNDS_MARGIN = 60f
        private const val TRAVERSAL_RADIUS = 40f
        private const val BOUNDS_SAFETY_MARGIN = 1
        private const val TRAVERSAL_MIN_OVERLAP = 0.30f
        private const val LIVE_SET_CAPACITY = 50
        private const val EXCELLENT_CANDIDATE_THRESHOLD = 0.95f
        private const val MIN_EXCELLENT_CANDIDATES = 3
    }
}
