@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import android.content.res.AssetManager
import android.graphics.PointF
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Tests that spatial proximity (key layout distance) correctly influences
 * autocorrect rankings when key positions are loaded.
 *
 * Uses Robolectric so that [android.graphics.PointF] constructors work correctly.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpellCheckManagerSpatialTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var languageManager: LanguageManager
    private lateinit var wordLearningEngine: WordLearningEngine
    private lateinit var wordFrequencyRepository: WordFrequencyRepository
    private lateinit var cacheMemoryManager: CacheMemoryManager
    private lateinit var wordNormalizer: WordNormalizer
    private lateinit var keyPositionsFlow: MutableStateFlow<Map<Char, PointF>>

    private lateinit var spellCheckManager: SpellCheckManager

    // fox=100 (low frequency), for=100000 (high frequency)
    // Without correct spatial scoring, "for" wins due to frequency advantage.
    // With correct spatial scoring, "fox" wins: z→x is one key apart, z→r is far.
    private val testDictionary = "fox 100\nfor 100000"

    // Standard QWERTY key positions (100px key width, 80px key height, staggered rows).
    // Order matters: stored as LinkedHashMap (q...p, a...l, z...m) so that cross-row pairs
    // p→a and l→z appear within 4 positions of each other in iteration — this is the pattern
    // that exposes the sigma inflation bug in calculateAverageKeySpacing.
    private val qwertyPositions: Map<Char, PointF> by lazy {
        linkedMapOf(
            'q' to PointF(50f, 40f), 'w' to PointF(150f, 40f), 'e' to PointF(250f, 40f),
            'r' to PointF(350f, 40f), 't' to PointF(450f, 40f), 'y' to PointF(550f, 40f),
            'u' to PointF(650f, 40f), 'i' to PointF(750f, 40f), 'o' to PointF(850f, 40f),
            'p' to PointF(950f, 40f),
            'a' to PointF(100f, 120f), 's' to PointF(200f, 120f), 'd' to PointF(300f, 120f),
            'f' to PointF(400f, 120f), 'g' to PointF(500f, 120f), 'h' to PointF(600f, 120f),
            'j' to PointF(700f, 120f), 'k' to PointF(800f, 120f), 'l' to PointF(900f, 120f),
            'z' to PointF(150f, 200f), 'x' to PointF(250f, 200f), 'c' to PointF(350f, 200f),
            'v' to PointF(450f, 200f), 'b' to PointF(550f, 200f), 'n' to PointF(650f, 200f),
            'm' to PointF(750f, 200f)
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mock()
        assetManager = mock()
        languageManager = mock()
        cacheMemoryManager = mock()
        wordNormalizer = mock()
        whenever(wordNormalizer.stripDiacritics(any())).thenAnswer { it.arguments[0] as String }
        whenever(wordNormalizer.canonicalizeApostrophes(any())).thenAnswer { it.arguments[0] as String }

        wordLearningEngine = mock {
            onBlocking { isWordLearned(any()) } doReturn false
            onBlocking { areWordsLearned(any()) } doReturn emptyMap()
            onBlocking { getSimilarLearnedWordsWithFrequency(any(), any(), any()) } doReturn emptyList()
        }

        wordFrequencyRepository = mock {
            onBlocking { getFrequency(any(), any()) } doReturn 0
            onBlocking { getFrequencies(any(), any()) } doReturn emptyMap()
        }

        val suggestionCache = ManagedCache<String, List<SpellingSuggestion>>(
            name = "test_suggestions",
            maxSize = 500,
            onEvict = null
        )
        val dictionaryCache = ManagedCache<String, Boolean>(
            name = "test_dictionary",
            maxSize = 1000,
            onEvict = null
        )

        whenever(
            cacheMemoryManager.createCache<String, List<SpellingSuggestion>>(
                eq("spell_suggestions"),
                eq(500),
                anyOrNull()
            )
        ).thenReturn(suggestionCache)

        whenever(
            cacheMemoryManager.createCache<String, Boolean>(
                eq("dictionary_cache"),
                eq(1000),
                anyOrNull()
            )
        ).thenReturn(dictionaryCache)

        whenever(languageManager.currentLanguage).thenReturn(MutableStateFlow("en"))
        whenever(languageManager.activeLanguages).thenReturn(MutableStateFlow(listOf("en")))
        whenever(languageManager.effectiveDictionaryLanguages).thenReturn(MutableStateFlow(listOf("en")))

        keyPositionsFlow = MutableStateFlow(emptyMap())
        whenever(languageManager.keyPositions).thenReturn(keyPositionsFlow)

        whenever(context.assets).thenReturn(assetManager)
        whenever(assetManager.open("dictionaries/en_symspell.txt"))
            .thenAnswer { ByteArrayInputStream(testDictionary.toByteArray()) }

        spellCheckManager = SpellCheckManager(
            context = context,
            languageManager = languageManager,
            wordLearningEngine = wordLearningEngine,
            wordFrequencyRepository = wordFrequencyRepository,
            cacheMemoryManager = cacheMemoryManager,
            ioDispatcher = testDispatcher,
            wordNormalizer = wordNormalizer
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `adjacent key substitution ranks above distant key substitution despite lower frequency`() = runTest {
        keyPositionsFlow.emit(qwertyPositions)

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("foz")

        val foxSuggestion = suggestions.find { it.word == "fox" }
        val forSuggestion = suggestions.find { it.word == "for" }

        assertNotNull("fox should appear as a suggestion for foz", foxSuggestion)
        assertNotNull("for should appear as a suggestion for foz", forSuggestion)
        assertTrue(
            "fox (z→x, adjacent keys) should rank above for (z→r, distant keys) despite lower frequency; " +
                "fox=${foxSuggestion!!.confidence}, for=${forSuggestion!!.confidence}",
            foxSuggestion.confidence > forSuggestion.confidence
        )
    }
}
