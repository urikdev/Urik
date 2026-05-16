@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import android.content.res.AssetManager
import android.graphics.PointF
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests [SpellCheckManager] dictionary lookups, suggestion generation, caching, and blacklisting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpellCheckManagerTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var languageManager: LanguageManager
    private lateinit var wordLearningEngine: WordLearningEngine
    private lateinit var wordFrequencyRepository: WordFrequencyRepository
    private lateinit var cacheMemoryManager: CacheMemoryManager
    private lateinit var wordNormalizer: WordNormalizer
    private lateinit var suggestionCache: ManagedCache<String, List<SpellingSuggestion>>
    private lateinit var dictionaryCache: ManagedCache<String, Boolean>

    private lateinit var currentLanguageFlow: MutableStateFlow<String>
    private lateinit var activeLanguagesFlow: MutableStateFlow<List<String>>
    private lateinit var effectiveDictionaryLanguagesFlow: MutableStateFlow<List<String>>

    private lateinit var spellCheckManager: SpellCheckManager

    private val testDictionary =
        """
        hello 1000
        world 800
        test 500
        testing 400
        help 300
        helpful 250
        computer 200
        keyboard 150
        does 450
        goes 420
        hose 100
        hots 90
        hogs 85
        hops 80
        hows 75
        toes 400
        foes 70
        woes 65
        shoes 380
        don't 500
        haven't 450
        can't 48000
        you're 460
        canteen 200
        canton 180
        cantor 170
        cantaloupe 160
        donate 220
        donkey 210
        done 230
        how's 15000
        cant 10
        wont 15
        won't 6000
        its 100
        it's 500
        well 2000
        we'll 100
        lets 200
        let's 1000
        this 5000
        that's 3000
        thats 50
        join 500
        in 8000
        best 300000
        bested 300
        bester 100
        bestow 800
        """.trimIndent()

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

        wordLearningEngine =
            mock {
                onBlocking { isWordLearned(any()) } doReturn false
                onBlocking { areWordsLearned(any()) } doReturn emptyMap()
                onBlocking { getSimilarLearnedWordsWithFrequency(any(), any(), any()) } doReturn emptyList()
            }

        wordFrequencyRepository =
            mock {
                onBlocking { getFrequency(any(), any()) } doReturn 0
                onBlocking { getFrequencies(any(), any()) } doReturn emptyMap()
            }

        suggestionCache =
            ManagedCache(
                name = "test_suggestions",
                maxSize = 500,
                onEvict = null
            )

        dictionaryCache =
            ManagedCache(
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

        currentLanguageFlow = MutableStateFlow("en")
        whenever(languageManager.currentLanguage).thenReturn(currentLanguageFlow)

        activeLanguagesFlow = MutableStateFlow(listOf("en"))
        whenever(languageManager.activeLanguages).thenReturn(activeLanguagesFlow)

        effectiveDictionaryLanguagesFlow = MutableStateFlow(listOf("en"))
        whenever(languageManager.effectiveDictionaryLanguages).thenReturn(effectiveDictionaryLanguagesFlow)

        val keyPositionsFlow = MutableStateFlow<Map<Char, PointF>>(emptyMap())
        whenever(languageManager.keyPositions).thenReturn(keyPositionsFlow)

        whenever(context.assets).thenReturn(assetManager)

        val urikBytes = TestUrikBuilder.buildUrikFromText(testDictionary)
        whenever(assetManager.open("dictionaries/en.urik"))
            .thenAnswer { ByteArrayInputStream(urikBytes) }

        spellCheckManager =
            SpellCheckManager(
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
    fun `initialization loads dictionary successfully`() = runTest {
        val result = spellCheckManager.isWordInDictionary("hello")
        assertTrue(result)
    }

    @Test
    fun `initialization handles missing dictionary gracefully`() = runTest {
        whenever(assetManager.open("dictionaries/sv.urik"))
            .thenThrow(java.io.FileNotFoundException())

        activeLanguagesFlow.emit(listOf("sv"))
        effectiveDictionaryLanguagesFlow.emit(listOf("sv"))

        val result = spellCheckManager.isWordInDictionary("test")
        assertFalse(result)
    }

    @Test
    fun `isWordInDictionary checks learned words first`() = runTest {
        whenever(wordLearningEngine.isWordLearned("myword")).thenReturn(true)

        val result = spellCheckManager.isWordInDictionary("myword")

        assertTrue(result)
        verify(wordLearningEngine).isWordLearned("myword")
    }

    @Test
    fun `isWordInDictionary falls back to dictionary when not learned`() = runTest {
        whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(false)

        val result = spellCheckManager.isWordInDictionary("hello")

        verify(wordLearningEngine).isWordLearned("hello")
        assertTrue(result)
    }

    @Test
    fun `isWordInDictionary returns false for unknown word`() = runTest {
        whenever(wordLearningEngine.isWordLearned("xyzabc")).thenReturn(false)

        val result = spellCheckManager.isWordInDictionary("xyzabc")

        assertFalse(result)
    }

    @Test
    fun `isWordInDictionary normalizes to lowercase`() = runTest {
        whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(true)

        val result = spellCheckManager.isWordInDictionary("HELLO")

        assertTrue(result)
        verify(wordLearningEngine).isWordLearned("hello")
    }

    @Test
    fun `isWordInDictionary trims whitespace`() = runTest {
        whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(true)

        val result = spellCheckManager.isWordInDictionary("  hello  ")

        assertTrue(result)
        verify(wordLearningEngine).isWordLearned("hello")
    }

    @Test
    fun `isWordInDictionary rejects invalid input`() = runTest {
        assertFalse(spellCheckManager.isWordInDictionary(""))
        assertFalse(spellCheckManager.isWordInDictionary("   "))
        assertFalse(spellCheckManager.isWordInDictionary("12345"))
        assertFalse(spellCheckManager.isWordInDictionary("!!!"))

        verify(wordLearningEngine, never()).isWordLearned(any())
    }

    @Test
    fun `isWordInDictionary accepts valid unicode characters`() = runTest {
        whenever(wordLearningEngine.isWordLearned("café")).thenReturn(true)

        val result = spellCheckManager.isWordInDictionary("café")

        assertTrue(result)
    }

    @Test
    fun `isWordInDictionary rejects extremely long input`() = runTest {
        val longWord = "a".repeat(200)

        val result = spellCheckManager.isWordInDictionary(longWord)

        assertFalse(result)
    }

    @Test
    fun `isWordInDictionary caches positive result`() = runTest {
        whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(false)

        spellCheckManager.isWordInDictionary("hello")
        spellCheckManager.isWordInDictionary("hello")

        val cacheKey = "en_hello"
        assertEquals(true, dictionaryCache.getIfPresent(cacheKey))
    }

    @Test
    fun `isWordInDictionary caches negative result`() = runTest {
        whenever(wordLearningEngine.isWordLearned("unknown")).thenReturn(false)

        spellCheckManager.isWordInDictionary("unknown")
        spellCheckManager.isWordInDictionary("unknown")

        val cacheKey = "en_unknown"
        assertEquals(false, dictionaryCache.getIfPresent(cacheKey))
    }

    @Test
    fun `invalidateWordCache removes from dictionary cache`() = runTest {
        whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(false)
        spellCheckManager.isWordInDictionary("hello")
        assertTrue(dictionaryCache.getIfPresent("en_hello") == true)

        spellCheckManager.invalidateWordCache("hello")

        assertEquals(null, dictionaryCache.getIfPresent("en_hello"))
    }

    @Test
    fun `clearCaches removes all dictionary entries`() = runTest {
        whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)
        spellCheckManager.isWordInDictionary("hello")
        spellCheckManager.isWordInDictionary("world")
        assertTrue(dictionaryCache.size() > 0)

        spellCheckManager.clearCaches()

        assertEquals(0, dictionaryCache.size())
    }

    @Test
    fun `areWordsInDictionary checks all words`() = runTest {
        whenever(wordLearningEngine.areWordsLearned(listOf("learned", "notlearned")))
            .thenReturn(mapOf("learned" to true, "notlearned" to false))

        val results = spellCheckManager.areWordsInDictionary(listOf("learned", "notlearned"))

        assertEquals(2, results.size)
        assertEquals(true, results["learned"])
        assertEquals(false, results["notlearned"])
    }

    @Test
    fun `areWordsInDictionary uses cache for known words`() = runTest {
        dictionaryCache.put("en_cached", true)

        val results = spellCheckManager.areWordsInDictionary(listOf("cached", "new"))

        assertEquals(true, results["cached"])
        verify(wordLearningEngine).areWordsLearned(listOf("new"))
    }

    @Test
    fun `areWordsInDictionary batches learned word checks`() = runTest {
        whenever(wordLearningEngine.areWordsLearned(any()))
            .thenReturn(mapOf("word1" to false, "word2" to false, "word3" to false))

        spellCheckManager.areWordsInDictionary(listOf("word1", "word2", "word3"))

        verify(wordLearningEngine, times(1)).areWordsLearned(any())
    }

    @Test
    fun `areWordsInDictionary filters invalid input`() = runTest {
        val results = spellCheckManager.areWordsInDictionary(listOf("", "  ", "valid"))

        assertEquals(false, results[""])
        assertEquals(false, results["  "])
        verify(wordLearningEngine).areWordsLearned(listOf("valid"))
    }

    @Test
    fun `generateSuggestions returns learned words with high confidence`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("helo", "en", 5))
            .thenReturn(listOf("hello" to 100, "help" to 50))

        val suggestions = spellCheckManager.generateSuggestions("helo", 3)

        assertTrue(suggestions.contains("hello"))
        assertTrue(suggestions.contains("help"))
    }

    @Test
    fun `generateSuggestions prioritizes by frequency`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("tes", "en", 5))
            .thenReturn(
                listOf(
                    "testing" to 1000,
                    "test" to 500,
                    "tester" to 100
                )
            )

        val suggestions = spellCheckManager.generateSuggestions("tes", 3)

        assertEquals("testing", suggestions[0])
        assertEquals("test", suggestions[1])
    }

    @Test
    fun `generateSuggestions respects max limit`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", "en", 5))
            .thenReturn(
                listOf(
                    "test1" to 10,
                    "test2" to 9,
                    "test3" to 8,
                    "test4" to 7,
                    "test5" to 6
                )
            )

        val suggestions = spellCheckManager.generateSuggestions("test", 2)

        assertEquals(2, suggestions.size)
    }

    @Test
    fun `getSpellingSuggestionsWithConfidence includes metadata`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("helo", "en", 5))
            .thenReturn(listOf("hello" to 100))

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("helo")

        assertTrue(suggestions.isNotEmpty())
        assertEquals("learned", suggestions.first().source)
        assertTrue(suggestions.first().confidence > 0.80)
        assertEquals(0, suggestions.first().ranking)
    }

    @Test
    fun `getSpellingSuggestionsWithConfidence boosts high frequency words`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", "en", 5))
            .thenReturn(
                listOf(
                    "common" to 10000,
                    "rare" to 1
                )
            )

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("test")

        val commonSuggestion = suggestions.find { it.word == "common" }!!
        val rareSuggestion = suggestions.find { it.word == "rare" }!!
        assertTrue(commonSuggestion.confidence > rareSuggestion.confidence)
    }

    @Test
    fun `getSpellingSuggestionsWithConfidence excludes exact match of typed word`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("hello")
        val exactMatch = suggestions.filter { it.source == "dictionary" }.find { it.word == "hello" }
        assertNull("Typed word should not appear in its own dictionary suggestions", exactMatch)
    }

    @Test
    fun `getSpellingSuggestionsWithConfidence filters low frequency noise relative to input`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        // "hello" freq=1000, "hose" freq=100, "hops" freq=80 — ratio > 500 threshold
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("hello")
        val dictSuggestions = suggestions.filter { it.source == "dictionary" }
        val lowFreqNoise = dictSuggestions.filter { it.word in listOf("hose", "hops", "hogs", "woes", "foes") }
        assertTrue(
            "Low-frequency noise words should be filtered, got: $lowFreqNoise",
            lowFreqNoise.isEmpty()
        )
    }

    @Test
    fun `queryCompletionSuggestions filters low frequency completions relative to typed prefix`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("best")
        val completions = suggestions.filter { it.source == "completion" }

        val noiseCompletions = completions.filter { it.word in listOf("bested", "bester") }
        assertTrue(
            "Low-frequency completions should be filtered when prefix freq >> completion freq, got: $noiseCompletions",
            noiseCompletions.isEmpty()
        )

        val realCompletion = completions.find { it.word == "bestow" }
        assertNotNull("Higher-frequency completion 'bestow' should survive filtering", realCompletion)
    }

    @Test
    fun `generateSuggestions caches results`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", "en", 5))
            .thenReturn(listOf("testing" to 10))

        spellCheckManager.generateSuggestions("test", 3)
        spellCheckManager.generateSuggestions("test", 3)

        assertTrue(suggestionCache.getIfPresent("en_test") != null)
    }

    @Test
    fun `invalidateWordCache removes from suggestion cache`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("word", "en", 5))
            .thenReturn(listOf("words" to 10))
        spellCheckManager.generateSuggestions("word", 3)
        assertTrue(suggestionCache.getIfPresent("en_word") != null)

        spellCheckManager.invalidateWordCache("word")

        assertEquals(null, suggestionCache.getIfPresent("en_word"))
    }

    @Test
    fun `clearCaches removes all suggestions`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(listOf("word" to 10))
        spellCheckManager.generateSuggestions("test1", 3)
        spellCheckManager.generateSuggestions("test2", 3)
        assertTrue(suggestionCache.size() > 0)

        spellCheckManager.clearCaches()

        assertEquals(0, suggestionCache.size())
    }

    @Test
    fun `blacklistSuggestion filters word from suggestions`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("bad", "en", 5))
            .thenReturn(listOf("badword" to 100, "badge" to 50))

        spellCheckManager.blacklistSuggestion("badword")

        val suggestions = spellCheckManager.generateSuggestions("bad", 3)

        assertFalse(suggestions.contains("badword"))
        assertTrue(suggestions.contains("badge"))
    }

    @Test
    fun `blacklistSuggestion normalizes word`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("bad", "en", 5))
            .thenReturn(listOf("badword" to 100))

        spellCheckManager.blacklistSuggestion("BadWord")

        val suggestions = spellCheckManager.generateSuggestions("bad", 3)
        assertFalse(suggestions.contains("badword"))
    }

    @Test
    fun `blacklistSuggestion clears relevant caches`() = runTest {
        dictionaryCache.put("en_bad", true)
        suggestionCache.put("en_bad", emptyList())

        spellCheckManager.blacklistSuggestion("bad")

        assertEquals(null, dictionaryCache.getIfPresent("en_bad"))
        assertEquals(null, suggestionCache.getIfPresent("en_bad"))
    }

    @Test
    fun `removeFromBlacklist allows word in suggestions again`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("word", "en", 5))
            .thenReturn(listOf("word" to 100))

        spellCheckManager.blacklistSuggestion("word")
        val blacklisted = spellCheckManager.generateSuggestions("word", 3)
        assertFalse(blacklisted.contains("word"))

        spellCheckManager.removeFromBlacklist("word")
        val unblacklisted = spellCheckManager.generateSuggestions("word", 3)
        assertTrue(unblacklisted.contains("word"))
    }

    @Test
    fun `removeFromBlacklist clears caches`() = runTest {
        spellCheckManager.blacklistSuggestion("word")
        dictionaryCache.put("en_word", false)

        spellCheckManager.removeFromBlacklist("word")

        assertEquals(null, dictionaryCache.getIfPresent("en_word"))
    }

    @Test
    fun `blacklisted words filtered from suggestions`() = runTest {
        spellCheckManager.blacklistSuggestion("offensive")

        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("off", "en", 5))
            .thenReturn(listOf("offensive" to 100, "offer" to 50))

        val suggestions = spellCheckManager.generateSuggestions("off", 3)

        assertFalse(suggestions.contains("offensive"))
        assertTrue(suggestions.contains("offer"))
    }

    @Test
    fun `language switch creates separate cache namespace`() = runTest {
        whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)

        val swedishDictionary = "hej 1000\nvärld 800"
        whenever(assetManager.open("dictionaries/sv.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(swedishDictionary)) }

        activeLanguagesFlow.value = listOf("en", "sv")
        effectiveDictionaryLanguagesFlow.value = listOf("en", "sv")

        spellCheckManager.isWordInDictionary("hello")
        assertTrue(dictionaryCache.getIfPresent("en_hello") != null)

        spellCheckManager.isWordInDictionary("hej")
        assertTrue(dictionaryCache.getIfPresent("en_hello") != null)
        assertTrue(dictionaryCache.getIfPresent("sv_hej") != null)
    }

    @Test
    fun `getCommonWords returns dictionary words with frequencies`() = runTest {
        val words = spellCheckManager.getCommonWords()

        assertTrue(words.isNotEmpty())
        assertTrue(words.any { it.first == "hello" })
        assertTrue(words.any { it.first == "world" })
    }

    @Test
    fun `getCommonWords returns all dictionary words`() = runTest {
        val words = spellCheckManager.getCommonWords()

        assertTrue("Expected multiple words", words.size > 5)
        assertTrue("hello should be present", words.any { it.first == "hello" })
    }

    @Test
    fun `getCommonWords excludes blacklisted words`() = runTest {
        spellCheckManager.blacklistSuggestion("hello")

        val words = spellCheckManager.getCommonWords()

        assertFalse(words.any { it.first == "hello" })
    }

    @Test
    fun `getCommonWords filters non-alphabetic words`() = runTest {
        val words = spellCheckManager.getCommonWords()

        assertTrue(
            words.all { word ->
                word.first.all { char ->
                    Character.isLetter(char.code) ||
                        Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                        com.urik.keyboard.utils.TextMatchingUtils
                            .isValidWordPunctuation(char)
                }
            }
        )
    }

    @Test
    fun `isWordInDictionary handles WordLearningEngine exception`() = runTest {
        whenever(wordLearningEngine.isWordLearned(any()))
            .thenThrow(RuntimeException("Database error"))

        val result = spellCheckManager.isWordInDictionary("test")

        assertFalse(result)
    }

    @Test
    fun `generateSuggestions falls back to dictionary when learned words fail`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenThrow(RuntimeException("Error"))

        val suggestions = spellCheckManager.generateSuggestions("test", 3)

        assertTrue(suggestions.contains("test") || suggestions.contains("testing"))
    }

    @Test
    fun `areWordsInDictionary handles partial failures`() = runTest {
        whenever(wordLearningEngine.areWordsLearned(any()))
            .thenThrow(RuntimeException("Error"))

        val results = spellCheckManager.areWordsInDictionary(listOf("word1", "word2"))

        assertEquals(2, results.size)
    }

    @Test
    fun `getCommonWords handles IOException gracefully`() = runTest {
        whenever(assetManager.open("dictionaries/en.urik"))
            .thenThrow(java.io.IOException("File error"))

        val failingManager =
            SpellCheckManager(
                context,
                languageManager,
                wordLearningEngine,
                wordFrequencyRepository,
                wordNormalizer,
                cacheMemoryManager,
                testDispatcher
            )
        val words = failingManager.getCommonWords()

        assertTrue(words.isEmpty())
    }

    @Test
    fun `blacklistSuggestion removes word from cached suggestion lists`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("te", "en", 5))
            .thenReturn(listOf("test" to 100, "team" to 80, "text" to 60))

        val suggestions1 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
        assertTrue(suggestions1.any { it.word == "test" })

        spellCheckManager.blacklistSuggestion("test")

        val suggestions2 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
        assertFalse(suggestions2.any { it.word == "test" })
        assertTrue(suggestions2.any { it.word == "team" })
    }

    @Test
    fun `removeFromBlacklist includes word in cached suggestion lists`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("te", "en", 5))
            .thenReturn(listOf("test" to 100, "team" to 80))

        spellCheckManager.blacklistSuggestion("test")

        val suggestions1 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
        assertFalse(suggestions1.any { it.word == "test" })

        spellCheckManager.removeFromBlacklist("test")

        val suggestions2 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
        assertTrue(suggestions2.any { it.word == "test" })
    }

    @Test
    fun `corrections from symspell have varied confidence based on frequency`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("helo")

        val dictSuggestions = suggestions.filter { it.source == "dictionary" }
        assertTrue("Should have multiple dictionary suggestions", dictSuggestions.size >= 2)

        val confidences = dictSuggestions.map { it.confidence }.distinct()
        assertTrue(
            "Dictionary suggestions should have different confidence scores " +
                "based on frequency, not all capped at same value",
            confidences.size > 1
        )
    }

    @Test
    fun `learned word contraction gets guaranteed confidence`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("dont", "en", 5))
            .thenReturn(listOf("don't" to 5))

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")

        val contractionSuggestion = suggestions.find { it.word == "don't" && it.source == "learned" }
        assertNotNull("Should find learned contraction don't", contractionSuggestion)
        assertEquals(
            "Learned contraction should have guaranteed confidence",
            0.995,
            contractionSuggestion!!.confidence,
            0.001
        )
    }

    @Test
    fun `prefix completion contraction gets guaranteed confidence`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")

        val contractionSuggestion = suggestions.find { it.word == "don't" }
        assertNotNull("Should find contraction don't from dictionary", contractionSuggestion)
        assertEquals(
            "Prefix completion contraction should have guaranteed confidence",
            0.995,
            contractionSuggestion!!.confidence,
            0.001
        )
    }

    @Test
    fun `symspell contraction gets guaranteed confidence`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("havent")

        val contractionSuggestion = suggestions.find { it.word == "haven't" }
        assertNotNull("Should find contraction haven't from SymSpell", contractionSuggestion)
        assertEquals(
            "SymSpell contraction should have guaranteed confidence",
            0.995,
            contractionSuggestion!!.confidence,
            0.001
        )
    }

    @Test
    fun `contraction ranks higher than high frequency learned words`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("dont", "en", 5))
            .thenReturn(
                listOf(
                    "donate" to 100,
                    "donkey" to 90,
                    "done" to 80
                )
            )

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")

        val contractionSuggestion = suggestions.find { it.word == "don't" }
        assertNotNull("Should find contraction don't", contractionSuggestion)

        val contractionIndex = suggestions.indexOfFirst { it.word == "don't" }
        assertEquals(
            "Contraction should rank first despite lower learned word frequencies",
            0,
            contractionIndex
        )
    }

    @Test
    fun `contraction appears when many high confidence suggestions exist`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("cant", "en", 5))
            .thenReturn(
                listOf(
                    "canteen" to 100,
                    "canton" to 90,
                    "cantor" to 80,
                    "cantaloupe" to 70
                )
            )

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("cant")

        val contractionSuggestion = suggestions.find { it.word == "can't" }
        assertNotNull("Should find contraction can't despite many learned words", contractionSuggestion)

        val contractionIndex = suggestions.indexOfFirst { it.word == "can't" }
        assertTrue(
            "Contraction should appear in top 3 positions",
            contractionIndex < 3
        )
    }

    @Test
    fun `contraction not boosted when user types with apostrophe`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("don't")

        val dontSuggestion = suggestions.find { it.word == "don't" }
        if (dontSuggestion != null) {
            assertNotEquals(
                "Should not apply contraction boost when input already has apostrophe",
                0.995,
                dontSuggestion.confidence,
                0.001
            )
        }
    }

    @Test
    fun `reverse direction contraction not boosted`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("don't", "en", 5))
            .thenReturn(listOf("dont" to 10))

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("don't")

        val dontSuggestion = suggestions.find { it.word == "dont" }
        if (dontSuggestion != null) {
            assertNotEquals(
                "Should not apply contraction boost for reverse direction (don't -> dont)",
                0.995,
                dontSuggestion.confidence,
                0.001
            )
        }
    }

    @Test
    fun `multiple contractions can coexist with correct ranking`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("youre")

        val contractionSuggestion = suggestions.find { it.word == "you're" }
        assertNotNull("Should find you're contraction", contractionSuggestion)
        assertEquals(
            "you're should have guaranteed confidence",
            0.995,
            contractionSuggestion!!.confidence,
            0.001
        )
    }

    @Test
    fun `high frequency learned words get significant boost`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", "en", 5))
            .thenReturn(
                listOf(
                    "testing" to 15,
                    "tester" to 2
                )
            )

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("test")

        val highFreq = suggestions.find { it.word == "testing" }!!
        val lowFreq = suggestions.find { it.word == "tester" }!!
        assertTrue(
            "High frequency (15 uses) should have higher confidence than low frequency (2 uses)",
            highFreq.confidence > lowFreq.confidence
        )
    }

    @Test
    fun `medium frequency learned words get moderate boost`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("word", "en", 5))
            .thenReturn(
                listOf(
                    "words" to 5,
                    "wordy" to 1
                )
            )

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("word")

        val mediumFreq = suggestions.find { it.word == "words" }!!
        val singleUse = suggestions.find { it.word == "wordy" }!!
        assertTrue(
            "Medium frequency (5 uses) should have higher confidence than single use (1 use)",
            mediumFreq.confidence > singleUse.confidence
        )
    }

    @Test
    fun `very high frequency word wins over lower frequency despite ranking`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("typ", "en", 5))
            .thenReturn(
                listOf(
                    "type" to 2,
                    "typo" to 50
                )
            )

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("typ")

        val typeWord = suggestions.find { it.word == "type" }!!
        val typoWord = suggestions.find { it.word == "typo" }!!
        assertTrue(
            "Very high frequency (50 uses) should have higher confidence despite lower ranking",
            typoWord.confidence > typeWord.confidence
        )
    }

    @Test
    fun `contraction dont gets guaranteed confidence for don't`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("dont", "en", 5))
            .thenReturn(listOf("don't" to 10))

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")

        val contractionSuggestion = suggestions.find { it.word == "don't" }
        assertNotNull("Should find contraction don't", contractionSuggestion)
        assertEquals(
            "Contraction should have guaranteed confidence",
            0.995,
            contractionSuggestion!!.confidence,
            0.001
        )
    }

    @Test
    fun `hyphenated compound coworker gets guaranteed confidence for co-worker`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("coworker", "en", 5))
            .thenReturn(listOf("co-worker" to 10))

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("coworker")

        val hyphenatedSuggestion = suggestions.find { it.word == "co-worker" }
        assertNotNull("Should find hyphenated word co-worker", hyphenatedSuggestion)
        assertEquals(
            "Hyphenated compound should have guaranteed confidence",
            0.995,
            hyphenatedSuggestion!!.confidence,
            0.001
        )
    }

    @Test
    fun `getCommonWordsForLanguages merges multiple dictionaries`() = runTest {
        val spanishDictionary =
            """
                hola 2000
                mundo 1500
                prueba 800
            """.trimIndent()

        whenever(assetManager.open("dictionaries/es.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(spanishDictionary)) }

        val words = spellCheckManager.getCommonWordsForLanguages(listOf("en", "es"))

        assertTrue(words.containsKey("hello"))
        assertTrue(words.containsKey("hola"))
    }

    @Test
    fun `getCommonWordsForLanguages keeps highest frequency for duplicate words`() = runTest {
        val spanishDictionary =
            """
                test 1200
                unique 500
            """.trimIndent()

        whenever(assetManager.open("dictionaries/es.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(spanishDictionary)) }

        val words = spellCheckManager.getCommonWordsForLanguages(listOf("en", "es"))

        assertTrue("test word should be present", words.containsKey("test"))
    }

    @Test
    fun `getCommonWordsForLanguages skips invalid language gracefully`() = runTest {
        val words = spellCheckManager.getCommonWordsForLanguages(listOf("en", "ja"))

        assertTrue(words.containsKey("hello"))
        assertFalse(words.isEmpty())
    }

    @Test
    fun `isWordInDictionary validates clitic form via decomposition`() = runTest {
        val frenchDictionary =
            """
                j' 500
                ai 1000
                le 900
                la 800
                arbre 600
                l' 700
            """.trimIndent()

        whenever(assetManager.open("dictionaries/fr.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(frenchDictionary)) }

        activeLanguagesFlow.value = listOf("fr")
        effectiveDictionaryLanguagesFlow.value = listOf("fr")
        currentLanguageFlow.value = "fr"
        whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)

        val frenchManager =
            SpellCheckManager(
                context = context,
                languageManager = languageManager,
                wordLearningEngine = wordLearningEngine,
                wordFrequencyRepository = wordFrequencyRepository,
                wordNormalizer = wordNormalizer,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher
            )

        val result = frenchManager.isWordInDictionary("j'ai")

        assertTrue("j'ai should be valid via clitic decomposition (j' + ai)", result)
    }

    @Test
    fun `isWordInDictionary validates Italian elision via decomposition`() = runTest {
        val italianDictionary =
            """
                l' 700
                uomo 500
                dell' 600
            """.trimIndent()

        whenever(assetManager.open("dictionaries/it.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(italianDictionary)) }

        activeLanguagesFlow.value = listOf("it")
        effectiveDictionaryLanguagesFlow.value = listOf("it")
        currentLanguageFlow.value = "it"
        whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)

        val italianManager =
            SpellCheckManager(
                context = context,
                languageManager = languageManager,
                wordLearningEngine = wordLearningEngine,
                wordFrequencyRepository = wordFrequencyRepository,
                wordNormalizer = wordNormalizer,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher
            )

        val result = italianManager.isWordInDictionary("l'uomo")

        assertTrue("l'uomo should be valid via clitic decomposition (l' + uomo)", result)
    }

    @Test
    fun `isWordInDictionary rejects invalid clitic form`() = runTest {
        whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)

        val result = spellCheckManager.isWordInDictionary("x'zzz")

        assertFalse("x'zzz should not be valid (neither part is a word)", result)
    }

    @Test
    fun `isWordInDictionary validates English possessive via learned word`() = runTest {
        whenever(wordLearningEngine.isWordLearned("user's")).thenReturn(false)
        whenever(wordLearningEngine.isWordLearned("user'")).thenReturn(false)
        whenever(wordLearningEngine.isWordLearned("s")).thenReturn(false)

        val result = spellCheckManager.isWordInDictionary("user's")

        assertFalse("user's requires at least one part in dictionary", result)
    }

    @Test
    fun `apostrophe-aware prefix completion matches unstripped dictionary words`() = runTest {
        val frenchDictionary =
            """
                j'ai 500
                j'aime 400
                jaune 300
                jardin 200
            """.trimIndent()

        whenever(assetManager.open("dictionaries/fr.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(frenchDictionary)) }

        activeLanguagesFlow.value = listOf("fr")
        effectiveDictionaryLanguagesFlow.value = listOf("fr")
        currentLanguageFlow.value = "fr"
        whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val frenchManager =
            SpellCheckManager(
                context = context,
                languageManager = languageManager,
                wordLearningEngine = wordLearningEngine,
                wordFrequencyRepository = wordFrequencyRepository,
                wordNormalizer = wordNormalizer,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher
            )

        frenchManager.getCommonWords("fr")

        val suggestions = frenchManager.getSpellingSuggestionsWithConfidence("j'a")

        val completionWords = suggestions.map { it.word }
        assertTrue(
            "Should find j'ai or j'aime when prefix is j'a",
            completionWords.any { it.startsWith("j'a", ignoreCase = true) }
        )
    }

    @Test
    fun `isWordInDictionary respects effective languages when isolated`() = runTest {
        val spanishDictionary = "hola 2000\nmundo 1500"
        whenever(assetManager.open("dictionaries/es.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(spanishDictionary)) }

        activeLanguagesFlow.value = listOf("en", "es")
        effectiveDictionaryLanguagesFlow.value = listOf("en")

        val result = spellCheckManager.isWordInDictionary("hola")

        assertFalse("hola should not be found when effective languages is only en", result)
    }

    @Test
    fun `isWordInDictionary finds word when effective languages includes its language`() = runTest {
        val spanishDictionary = "hola 2000\nmundo 1500"
        whenever(assetManager.open("dictionaries/es.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(spanishDictionary)) }

        activeLanguagesFlow.value = listOf("en", "es")
        effectiveDictionaryLanguagesFlow.value = listOf("en", "es")

        val result = spellCheckManager.isWordInDictionary("hola")

        assertTrue("hola should be found when effective languages includes es", result)
    }

    @Test
    fun `suggestions restricted to effective languages when isolated`() = runTest {
        val spanishDictionary = "hola 2000\nmundo 1500"
        whenever(assetManager.open("dictionaries/es.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(spanishDictionary)) }

        activeLanguagesFlow.value = listOf("en", "es")
        effectiveDictionaryLanguagesFlow.value = listOf("en")

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("hola")
        val words = suggestions.map { it.word }

        assertFalse("hola should not appear in suggestions when isolated to en", words.contains("hola"))
    }

    @Test
    fun `areWordsInDictionary respects effective languages`() = runTest {
        val spanishDictionary = "hola 2000\nmundo 1500"
        whenever(assetManager.open("dictionaries/es.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(spanishDictionary)) }

        activeLanguagesFlow.value = listOf("en", "es")
        effectiveDictionaryLanguagesFlow.value = listOf("en")

        val results = spellCheckManager.areWordsInDictionary(listOf("hello", "hola"))

        assertTrue("hello should be valid in en", results["hello"] == true)
        assertFalse("hola should not be valid when isolated to en", results["hola"] == true)
    }

    @Test
    fun `switching effective languages invalidates caches`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertTrue(dictionaryCache.getIfPresent("en_hello") != null)

        effectiveDictionaryLanguagesFlow.value = listOf("es")

        assertTrue(
            "Cache should be invalidated after effective language change",
            dictionaryCache.getIfPresent("en_hello") == null
        )
    }

    @Test
    fun `hasDominantContractionForm returns true when contraction is 20x more frequent`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertTrue(spellCheckManager.hasDominantContractionForm("hows"))
    }

    @Test
    fun `hasDominantContractionForm returns true for cant vs can't`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertTrue(spellCheckManager.hasDominantContractionForm("cant"))
    }

    @Test
    fun `hasDominantContractionForm returns true for wont vs won't`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertTrue(spellCheckManager.hasDominantContractionForm("wont"))
    }

    @Test
    fun `hasDominantContractionForm returns false when ratio is below threshold`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertFalse(spellCheckManager.hasDominantContractionForm("its"))
        assertFalse(spellCheckManager.hasDominantContractionForm("lets"))
    }

    @Test
    fun `hasDominantContractionForm returns false when plain form is more frequent`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertFalse(spellCheckManager.hasDominantContractionForm("well"))
    }

    @Test
    fun `hasDominantContractionForm returns false for word already containing apostrophe`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertFalse(spellCheckManager.hasDominantContractionForm("don't"))
    }

    @Test
    fun `hasDominantContractionForm returns false for word not in dictionary`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertFalse(spellCheckManager.hasDominantContractionForm("xyz"))
    }

    @Test
    fun `hasDominantContractionForm returns false when no contraction form exists`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertFalse(spellCheckManager.hasDominantContractionForm("hello"))
    }

    @Test
    fun `getDominantContractionForm returns contraction when dominant`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertEquals("how's", spellCheckManager.getDominantContractionForm("hows"))
        assertEquals("can't", spellCheckManager.getDominantContractionForm("cant"))
        assertEquals("won't", spellCheckManager.getDominantContractionForm("wont"))
    }

    @Test
    fun `getDominantContractionForm returns null when not dominant`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        assertEquals(null, spellCheckManager.getDominantContractionForm("its"))
        assertEquals(null, spellCheckManager.getDominantContractionForm("well"))
        assertEquals(null, spellCheckManager.getDominantContractionForm("hello"))
    }

    @Test
    fun `suggestions promote cant to can't for misspelled input`() = runTest {
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("cabt")
        val words = suggestions.map { it.word }
        assertTrue(
            "can't should appear in suggestions for cabt",
            words.contains("can't")
        )
        assertFalse(
            "cant should be promoted to can't, not appear separately",
            words.contains("cant")
        )
    }

    @Test
    fun `contraction distance reduction gives distance-2 contraction competitive confidence`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("thts")

        val thatsConfidence = suggestions.find { it.word == "that's" }?.confidence
        val thisConfidence = suggestions.find { it.word == "this" }?.confidence

        assertNotNull("that's should appear in suggestions for thts", thatsConfidence)
        assertNotNull("this should appear in suggestions for thts", thisConfidence)

        if (thatsConfidence != null && thisConfidence != null) {
            val gap = thisConfidence - thatsConfidence
            assertTrue(
                "that's should be within 0.05 of this (gap=$gap), proving distance reduction worked",
                gap < 0.05
            )
        }
    }

    @Test
    fun `short candidate penalized when much shorter than input`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("jkin")
        val words = suggestions.map { it.word }
        if (words.contains("join") && words.contains("in")) {
            val joinIdx = words.indexOf("join")
            val inIdx = words.indexOf("in")
            assertTrue(
                "join should rank above in for jkin",
                joinIdx < inIdx
            )
        }
    }

    @Test
    fun `distance 1 contraction apostrophe boost unchanged`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("cant")
        val words = suggestions.map { it.word }
        assertTrue(
            "can't should appear for cant",
            words.contains("can't")
        )
        if (words.isNotEmpty()) {
            assertTrue(
                "can't should be top suggestion for cant",
                words.first() == "can't"
            )
        }
    }

    @Test
    fun `contraction distance reduction does not apply at distance 1`() = runTest {
        spellCheckManager.isWordInDictionary("hello")
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("helo")
        val topWord = suggestions.firstOrNull()?.word
        assertEquals(
            "hello should still be top result for helo (distance 1, no contraction change)",
            "hello",
            topWord
        )
    }

    @Test
    fun `user frequency suppresses contraction dominance bypass`() = runTest {
        spellCheckManager.isWordInDictionary("hello")

        assertTrue(
            "With no user frequency, hows should have dominant contraction",
            spellCheckManager.hasDominantContractionForm("hows")
        )

        whenever(wordFrequencyRepository.getFrequency(eq("hows"), any())).thenReturn(15)

        assertFalse(
            "With user frequency boosting hows, contraction dominance should be suppressed",
            spellCheckManager.hasDominantContractionForm("hows")
        )
    }

    @Test
    fun `low user frequency does not suppress contraction dominance`() = runTest {
        spellCheckManager.isWordInDictionary("hello")

        whenever(wordFrequencyRepository.getFrequency(eq("hows"), any())).thenReturn(1)

        assertTrue(
            "Low user frequency should not suppress contraction dominance",
            spellCheckManager.hasDominantContractionForm("hows")
        )
    }

    @Test
    fun `isDegradedMode is false by default`() = runTest {
        assertFalse(spellCheckManager.isDegradedMode)
    }

    @Test
    fun `isDegradedMode is false after successful initialization`() = runTest {
        assertFalse(spellCheckManager.isDegradedMode)
    }

    @Test
    fun `dictionary lookup is scoped per language`() = runTest {
        val frenchDictionary =
            """
            bonjour 2000
            monde 1500
            """.trimIndent()

        whenever(assetManager.open("dictionaries/fr.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(frenchDictionary)) }

        activeLanguagesFlow.value = listOf("en", "fr")
        effectiveDictionaryLanguagesFlow.value = listOf("en", "fr")
        currentLanguageFlow.value = "fr"

        val frenchManager =
            SpellCheckManager(
                context = context,
                languageManager = languageManager,
                wordLearningEngine = wordLearningEngine,
                wordFrequencyRepository = wordFrequencyRepository,
                wordNormalizer = wordNormalizer,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher
            )

        assertTrue("hello should be found in en", frenchManager.isWordInDictionary("hello"))
        assertTrue("bonjour should be found in fr", frenchManager.isWordInDictionary("bonjour"))

        effectiveDictionaryLanguagesFlow.value = listOf("en")
        frenchManager.clearCaches()
        assertFalse(
            "bonjour should not be found when only en is effective",
            frenchManager.isWordInDictionary("bonjour")
        )
    }

    @Test
    fun `onMemoryPressure CRITICAL evicts non-current language dictionaries`() = runTest {
        val frenchDictionary = "bonjour 2000\nmonde 1500"
        whenever(assetManager.open("dictionaries/fr.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(frenchDictionary)) }

        effectiveDictionaryLanguagesFlow.value = listOf("en", "fr")
        spellCheckManager.isWordInDictionary("bonjour")

        spellCheckManager.onMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        whenever(assetManager.open("dictionaries/fr.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(frenchDictionary)) }
        assertTrue("en lookup still works after CRITICAL pressure", spellCheckManager.isWordInDictionary("hello"))
    }

    @Test
    fun `isDegradedMode is true after initialization timeout`() {
        val standardDispatcher = StandardTestDispatcher()
        val neverDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                // Never executes — simulates IO that never completes, forcing a timeout
            }
        }
        Dispatchers.setMain(standardDispatcher)
        try {
            val timeoutManager =
                SpellCheckManager(
                    context = context,
                    languageManager = languageManager,
                    wordLearningEngine = wordLearningEngine,
                    wordFrequencyRepository = wordFrequencyRepository,
                    cacheMemoryManager = cacheMemoryManager,
                    ioDispatcher = neverDispatcher,
                    wordNormalizer = wordNormalizer
                )

            kotlinx.coroutines.test.runTest(standardDispatcher) {
                launch { timeoutManager.generateSuggestions("hello") }
                advanceTimeBy(5001L)
                runCurrent()
                assertTrue(timeoutManager.isDegradedMode)
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `isDegradedMode is true after initialization fails with exception`() = runTest {
        whenever(assetManager.open("dictionaries/en.urik"))
            .thenThrow(java.io.IOException("Asset missing"))

        val failingManager =
            SpellCheckManager(
                context = context,
                languageManager = languageManager,
                wordLearningEngine = wordLearningEngine,
                wordFrequencyRepository = wordFrequencyRepository,
                wordNormalizer = wordNormalizer,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher
            )

        failingManager.generateSuggestions("hello")
        assertTrue(failingManager.isDegradedMode)
    }

    @Test
    fun `isWordInDictionary cancels cooperatively without hanging`() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { spellCheckManager.isWordInDictionary("hello") }
        yield()
        job.cancelAndJoin()
    }

    @Test
    fun `fat finger expansion returns gave for bave via b to g substitution`() = runTest {
        val fatFingerExpander = FatFingerExpander()
        val gaveDictionary = "gave 500\nhave 800"
        val fatKeyPositionsFlow = MutableStateFlow<Map<Char, PointF>>(emptyMap())
        whenever(languageManager.keyPositions).thenReturn(fatKeyPositionsFlow)
        whenever(assetManager.open("dictionaries/en.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(gaveDictionary)) }
        val fatFingerManager = SpellCheckManager(
            context = context,
            languageManager = languageManager,
            wordLearningEngine = wordLearningEngine,
            wordFrequencyRepository = wordFrequencyRepository,
            cacheMemoryManager = cacheMemoryManager,
            ioDispatcher = testDispatcher,
            wordNormalizer = wordNormalizer,
            fatFingerExpander = fatFingerExpander
        )
        val qwertyPositions = linkedMapOf(
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
        fatKeyPositionsFlow.emit(qwertyPositions)

        val suggestions = fatFingerManager.getSpellingSuggestionsWithConfidence("bave")
        val words = suggestions.map { it.word }

        assertTrue(
            "gave should appear as a candidate for bave via b→g adjacent-key substitution",
            words.contains("gave")
        )
    }

    @Test
    fun `fat finger expansion does not produce duplicate candidates`() = runTest {
        val fatFingerExpander = FatFingerExpander()
        val gaveDictionary = "gave 500\nhave 800"
        val fatKeyPositionsFlow = MutableStateFlow<Map<Char, PointF>>(emptyMap())
        whenever(languageManager.keyPositions).thenReturn(fatKeyPositionsFlow)
        whenever(assetManager.open("dictionaries/en.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(gaveDictionary)) }
        val fatFingerManager = SpellCheckManager(
            context = context,
            languageManager = languageManager,
            wordLearningEngine = wordLearningEngine,
            wordFrequencyRepository = wordFrequencyRepository,
            cacheMemoryManager = cacheMemoryManager,
            ioDispatcher = testDispatcher,
            wordNormalizer = wordNormalizer,
            fatFingerExpander = fatFingerExpander
        )
        val qwertyPositions = linkedMapOf(
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
        fatKeyPositionsFlow.emit(qwertyPositions)

        val suggestions = fatFingerManager.getSpellingSuggestionsWithConfidence("bave")
        val words = suggestions.map { it.word.lowercase() }

        assertEquals("Candidate list must not contain duplicates", words.size, words.toSet().size)
    }

    @Test
    fun `fat finger expansion falls back gracefully when key positions are empty`() = runTest {
        val fatFingerExpander = FatFingerExpander()
        val fatFingerManager = SpellCheckManager(
            context = context,
            languageManager = languageManager,
            wordLearningEngine = wordLearningEngine,
            wordFrequencyRepository = wordFrequencyRepository,
            cacheMemoryManager = cacheMemoryManager,
            ioDispatcher = testDispatcher,
            wordNormalizer = wordNormalizer,
            fatFingerExpander = fatFingerExpander
        )

        val suggestions = fatFingerManager.getSpellingSuggestionsWithConfidence("helo")

        assertNotNull("Should return non-null suggestions even with empty key positions", suggestions)
        assertTrue("hello should still appear via direct dictionary lookup", suggestions.any { it.word == "hello" })
    }

    @Test
    fun `urik dictionary returns suggestions for known typo`() = runTest {
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("hellp")
        assertTrue("Expected hello suggestion from URIK dictionary", suggestions.any { it.word == "hello" })
    }

    @Test
    fun `apostrophe candidate at distance 1 gets confidence boost`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        // "dont" → "don't" is distance 1 (apostrophe insertion), contraction candidate
        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")
        val contraction = suggestions.find { it.word == "don't" }
        assertNotNull("don't should appear in suggestions for 'dont'", contraction)
        assertTrue(
            "Contraction suggestion should have guaranteed confidence, got ${contraction!!.confidence}",
            contraction.confidence >= SpellCheckManager.CONTRACTION_GUARANTEED_CONFIDENCE
        )
    }

    @Test
    fun `dictionary suggestion ranking reflects result order`() = runTest {
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("helo")
            .filter { it.source == "dictionary" }
        assertTrue("Should have dictionary suggestions", suggestions.isNotEmpty())
        suggestions.forEachIndexed { index, suggestion ->
            assertEquals("Ranking should match position $index", index, suggestion.ranking)
        }
    }

    @Test
    fun `getCommonWordsForLanguages returns Long frequencies`() = runTest {
        val words = spellCheckManager.getCommonWordsForLanguages(listOf("en"))
        assertTrue(words.isNotEmpty())
        val helloFreq = words["hello"]
        assertNotNull(helloFreq)
        assertTrue("Expected Long frequency > 1000, got $helloFreq", helloFreq!! > 1000L)
    }

    @Test
    fun `fat finger expansion integrates with URIK candidates for adjacent key typo`() = runTest {
        val fatFingerExpander = FatFingerExpander()
        val gaveDictionary = "gave 500\nhave 800"
        val fatKeyPositionsFlow = MutableStateFlow<Map<Char, PointF>>(emptyMap())
        whenever(languageManager.keyPositions).thenReturn(fatKeyPositionsFlow)
        whenever(assetManager.open("dictionaries/en.urik"))
            .thenAnswer { ByteArrayInputStream(TestUrikBuilder.buildUrikFromText(gaveDictionary)) }

        val fatManager = SpellCheckManager(
            context = context,
            languageManager = languageManager,
            wordLearningEngine = wordLearningEngine,
            wordFrequencyRepository = wordFrequencyRepository,
            cacheMemoryManager = cacheMemoryManager,
            ioDispatcher = testDispatcher,
            wordNormalizer = wordNormalizer,
            fatFingerExpander = fatFingerExpander
        )

        val qwerty = linkedMapOf(
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
        fatKeyPositionsFlow.emit(qwerty)
        whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any(), any()))
            .thenReturn(emptyList())

        val suggestions = fatManager.getSpellingSuggestionsWithConfidence("bave")
        assertTrue(
            "gave should appear as suggestion for bave (b→g adjacent keys), got: ${suggestions.map { it.word }}",
            suggestions.any { it.word == "gave" }
        )
    }
}
