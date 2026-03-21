package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.service.WordNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StreamingScoringEngineTest {
    @Mock private lateinit var spellCheckManager: SpellCheckManager

    @Mock private lateinit var wordLearningEngine: WordLearningEngine

    @Mock private lateinit var pathGeometryAnalyzer: PathGeometryAnalyzer

    @Mock private lateinit var wordFrequencyRepository: WordFrequencyRepository

    @Mock private lateinit var residualScorer: ResidualScorer

    @Mock private lateinit var zipfCheck: ZipfCheck

    @Mock private lateinit var wordNormalizer: WordNormalizer

    private lateinit var engine: StreamingScoringEngine
    private lateinit var closeable: AutoCloseable

    private val qwertyKeyPositions =
        mapOf(
            'q' to PointF(30f, 50f),
            'w' to PointF(80f, 50f),
            'e' to PointF(130f, 50f),
            'r' to PointF(180f, 50f),
            't' to PointF(230f, 50f),
            'y' to PointF(280f, 50f),
            'u' to PointF(330f, 50f),
            'i' to PointF(380f, 50f),
            'o' to PointF(430f, 50f),
            'p' to PointF(480f, 50f),
            'a' to PointF(40f, 130f),
            's' to PointF(90f, 130f),
            'd' to PointF(140f, 130f),
            'f' to PointF(190f, 130f),
            'g' to PointF(240f, 130f),
            'h' to PointF(290f, 130f),
            'j' to PointF(340f, 130f),
            'k' to PointF(390f, 130f),
            'l' to PointF(440f, 130f),
            'z' to PointF(90f, 210f),
            'x' to PointF(140f, 210f),
            'c' to PointF(190f, 210f),
            'v' to PointF(240f, 210f),
            'b' to PointF(290f, 210f),
            'n' to PointF(340f, 210f),
            'm' to PointF(390f, 210f)
        )

    @Before
    fun setup() {
        closeable = MockitoAnnotations.openMocks(this)
        engine =
            StreamingScoringEngine(
                spellCheckManager = spellCheckManager,
                wordLearningEngine = wordLearningEngine,
                pathGeometryAnalyzer = pathGeometryAnalyzer,
                wordFrequencyRepository = wordFrequencyRepository,
                residualScorer = residualScorer,
                zipfCheck = zipfCheck,
                wordNormalizer = wordNormalizer
            )
    }

    @After
    fun teardown() {
        engine.shutdown()
        closeable.close()
    }

    @Test
    fun `cancelActiveGesture clears live candidate set`() = runTest {
        engine.startGesture(qwertyKeyPositions, listOf("en"), "en")
        engine.cancelActiveGesture()

        val results = engine.finalize(emptyList(), 0)
        assertTrue("Cancelled gesture should produce no results", results.isEmpty())
    }

    @Test
    fun `startGesture resets state from previous gesture`() = runTest {
        engine.startGesture(qwertyKeyPositions, listOf("en"), "en")
        engine.cancelActiveGesture()

        engine.startGesture(qwertyKeyPositions, listOf("en"), "en")
        engine.cancelActiveGesture()

        val results = engine.finalize(emptyList(), 0)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `pruneByStartAnchor removes candidates with wrong first letter`() {
        val candidates =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000),
                makeDictionaryEntry("world", 'w', 900),
                makeDictionaryEntry("zebra", 'z', 800)
            )

        val startKeys = setOf('h', 'j')
        val pruned = engine.pruneByStartAnchor(candidates, startKeys)

        assertEquals(1, pruned.size)
        assertEquals("hello", pruned[0].word)
    }

    @Test
    fun `pruneByBounds removes candidates requiring keys outside bounds`() {
        val candidates =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000),
                makeDictionaryEntry("zebra", 'z', 800)
            )

        val charsInBounds = setOf('h', 'e', 'l', 'o', 'w', 'r', 't')
        val pruned = engine.pruneByBounds(candidates, charsInBounds)

        assertEquals(1, pruned.size)
        assertEquals("hello", pruned[0].word)
    }

    @Test
    fun `pruneByBounds applies fingertip safety margin`() {
        val candidates =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000)
            )

        val charsInBounds = setOf('h', 'e', 'l', 'o')
        val pruned = engine.pruneByBounds(candidates, charsInBounds)

        assertEquals(
            "All letters within bounds should survive",
            1,
            pruned.size
        )
    }

    @Test
    fun `pruneByBounds allows one out-of-bounds letter as safety margin`() {
        val candidates =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000)
            )

        val charsInBounds = setOf('h', 'e', 'l')
        val pruned = engine.pruneByBounds(candidates, charsInBounds)

        assertEquals(
            "One out-of-bounds letter should be tolerated",
            1,
            pruned.size
        )
    }

    @Test
    fun `monotonic pruning does not re-add eliminated candidates`() {
        val full =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000),
                makeDictionaryEntry("world", 'w', 900),
                makeDictionaryEntry("zebra", 'z', 800)
            )

        val afterStart = engine.pruneByStartAnchor(full, setOf('h', 'w'))
        assertEquals(2, afterStart.size)

        val afterBounds = engine.pruneByBounds(afterStart, setOf('h', 'e', 'l', 'o'))
        assertEquals(1, afterBounds.size)
        assertEquals("hello", afterBounds[0].word)
    }

    @Test
    fun `traversal pruning preserves hello with 30 percent overlap threshold`() {
        val candidates =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000),
                makeDictionaryEntry("world", 'w', 900),
                makeDictionaryEntry("help", 'h', 800)
            )

        val traversedKeys = setOf('h', 'e', 'l', 'o', 'w', 'r')
        val pruned = engine.pruneByTraversal(candidates, traversedKeys)

        val prunedWords = pruned.map { it.word }.toSet()
        assertTrue("'hello' should survive traversal (100% overlap)", "hello" in prunedWords)
        assertTrue("'world' should survive traversal (75% overlap)", "world" in prunedWords)
        assertTrue("'help' should survive traversal (100% overlap)", "help" in prunedWords)
    }

    @Test
    fun `traversal pruning rejects word with low overlap`() {
        val candidates =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000),
                makeDictionaryEntry("pizza", 'p', 500)
            )

        val traversedKeys = setOf('h', 'e', 'l', 'o')
        val pruned = engine.pruneByTraversal(candidates, traversedKeys)

        assertEquals("Only hello should survive", 1, pruned.size)
        assertEquals("hello", pruned[0].word)
    }

    @Test
    fun `bounds pruning tolerates one out-of-bounds char for long words`() {
        val candidates =
            listOf(
                makeDictionaryEntry("together", 't', 5000)
            )

        val charsInBounds = setOf('t', 'o', 'g', 'e', 'h', 'r')
        val pruned = engine.pruneByBounds(candidates, charsInBounds)

        assertEquals(
            "together should survive with 1 out-of-bounds char",
            1,
            pruned.size
        )
    }

    @Test
    fun `bounds pruning rejects word with 2 plus out-of-bounds chars`() {
        val candidates =
            listOf(
                makeDictionaryEntry("together", 't', 5000)
            )

        val charsInBounds = setOf('t', 'o', 'g')
        val pruned = engine.pruneByBounds(candidates, charsInBounds)

        assertEquals(
            "together should be rejected with many out-of-bounds chars",
            0,
            pruned.size
        )
    }

    @Test
    fun `start anchor pruning preserves words matching any start key`() {
        val candidates =
            listOf(
                makeDictionaryEntry("another", 'a', 2000),
                makeDictionaryEntry("seven", 's', 1500),
                makeDictionaryEntry("together", 't', 1000)
            )

        val startKeys = setOf('a', 's')
        val pruned = engine.pruneByStartAnchor(candidates, startKeys)

        assertEquals(2, pruned.size)
        val prunedWords = pruned.map { it.word }.toSet()
        assertTrue("another" in prunedWords)
        assertTrue("seven" in prunedWords)
    }

    @Test
    fun `cascaded pruning does not eliminate common words prematurely`() {
        val commonWords =
            listOf(
                makeDictionaryEntry("the", 't', 100_000_000),
                makeDictionaryEntry("hello", 'h', 50_000_000),
                makeDictionaryEntry("world", 'w', 30_000_000),
                makeDictionaryEntry("picture", 'p', 10_000_000),
                makeDictionaryEntry("together", 't', 8_000_000),
                makeDictionaryEntry("another", 'a', 5_000_000),
                makeDictionaryEntry("proctor", 'p', 100),
                makeDictionaryEntry("zebra", 'z', 50)
            )

        val startKeys = setOf('h', 'p', 't', 'w', 'a')
        val afterAnchor = engine.pruneByStartAnchor(commonWords, startKeys)
        assertTrue(
            "Anchor prune should keep most common words",
            afterAnchor.size >= 6
        )

        val charsInBounds = setOf('h', 'e', 'l', 'o', 'p', 'i', 'c', 't', 'u', 'r')
        val afterBounds = engine.pruneByBounds(afterAnchor, charsInBounds)
        val survivingWords = afterBounds.map { it.word }.toSet()

        assertTrue(
            "hello should survive full cascade",
            "hello" in survivingWords
        )
        assertTrue(
            "picture should survive full cascade",
            "picture" in survivingWords
        )
    }

    @Test
    fun `traversal with sparse traversed keys does not prune`() {
        val candidates =
            listOf(
                makeDictionaryEntry("hello", 'h', 1000),
                makeDictionaryEntry("world", 'w', 900)
            )

        val sparseKeys = setOf('h')
        val pruned = engine.pruneByTraversal(candidates, sparseKeys)

        assertEquals(
            "Sparse traversed keys (size < 2) should skip pruning entirely",
            2,
            pruned.size
        )
    }

    private fun makeDictionaryEntry(word: String, firstChar: Char, frequency: Long): SwipeDetector.DictionaryEntry {
        val lowercaseWord = word.lowercase()
        return SwipeDetector.DictionaryEntry(
            word = word,
            frequencyScore = 0.5f,
            rawFrequency = frequency,
            firstChar = firstChar,
            uniqueLetterCount = lowercaseWord.toSet().size,
            uniqueLowercaseChars = lowercaseWord.toSet()
        )
    }
}
