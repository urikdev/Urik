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
 * Tests that the Japanese spatial-score bypass (`if (languageCode == "ja") 0.0`)
 * is load-bearing: without it, a low-frequency candidate adjacent to the input key
 * would outscore a high-frequency candidate due to SPATIAL_PROXIMITY_WEIGHT (0.35)
 * dwarfing SYMSPELL_FREQUENCY_WEIGHT (0.05).
 *
 * Setup:
 *   dictionary: あい=100, あう=10_000_000
 *   input: あの  (edit distance 1 from both candidates)
 *   key positions: の is 10 px from い, 490 px from う
 *
 * Without bypass → spatialScore(あの→あい) ≈ 1.0 → あい wins despite lower frequency.
 * With bypass    → spatial ignored          → あう wins on frequency alone.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpellCheckManagerJapaneseSpatialTest {
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

    private val testDictionary = "あい 100\nあう 10000000"

    /**
     * 12-key positions where の is adjacent to い (10 px) but far from う (490 px).
     * This makes spatialScore(あの→あい) ≈ 1.0 and spatialScore(あの→あう) ≈ 0.27,
     * which — without the Japanese bypass — would let the low-frequency あい win.
     */
    private val hiraganaPositions: Map<Char, PointF> by lazy {
        mapOf(
            'あ' to PointF(50f, 40f),
            'い' to PointF(150f, 40f),
            'の' to PointF(160f, 40f),
            'う' to PointF(650f, 40f)
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

        keyPositionsFlow = MutableStateFlow(emptyMap())
        whenever(languageManager.currentLanguage).thenReturn(MutableStateFlow("ja"))
        whenever(languageManager.activeLanguages).thenReturn(MutableStateFlow(listOf("ja")))
        whenever(languageManager.effectiveDictionaryLanguages).thenReturn(MutableStateFlow(listOf("ja")))
        whenever(languageManager.keyPositions).thenReturn(keyPositionsFlow)

        whenever(context.assets).thenReturn(assetManager)
        whenever(assetManager.open("dictionaries/ja_symspell.txt"))
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
    fun `Japanese spatial bypass lets frequency win over spatial proximity`() = runTest {
        keyPositionsFlow.emit(hiraganaPositions)

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("あの")

        val highFreqSuggestion = suggestions.find { it.word == "あう" }
        val lowFreqSuggestion = suggestions.find { it.word == "あい" }

        assertNotNull("あう (freq=10M) should appear as a suggestion for あの", highFreqSuggestion)
        assertNotNull("あい (freq=100) should appear as a suggestion for あの", lowFreqSuggestion)
        assertTrue(
            "Japanese bypass must prevent spatial proximity from overriding frequency: " +
                "あう (freq=10M, spatially far) should rank above あい (freq=100, spatially adjacent); " +
                "あう=${highFreqSuggestion!!.confidence}, あい=${lowFreqSuggestion!!.confidence}",
            highFreqSuggestion.confidence > lowFreqSuggestion.confidence
        )
    }
}
