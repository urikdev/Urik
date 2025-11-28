@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import android.content.res.AssetManager
import com.urik.keyboard.KeyboardConstants.SpellCheckConstants
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.io.ByteArrayInputStream

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
    private lateinit var cacheMemoryManager: CacheMemoryManager
    private lateinit var suggestionCache: ManagedCache<String, List<SpellingSuggestion>>
    private lateinit var dictionaryCache: ManagedCache<String, Boolean>

    private lateinit var currentLanguageFlow: MutableStateFlow<String>

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
        can't 480
        you're 460
        canteen 200
        canton 180
        cantor 170
        cantaloupe 160
        donate 220
        donkey 210
        done 230
        """.trimIndent()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mock()
        assetManager = mock()
        languageManager = mock()
        cacheMemoryManager = mock()

        wordLearningEngine =
            mock {
                onBlocking { isWordLearned(any()) } doReturn false
                onBlocking { areWordsLearned(any()) } doReturn emptyMap()
                onBlocking { getSimilarLearnedWordsWithFrequency(any(), any()) } doReturn emptyList()
            }

        suggestionCache =
            ManagedCache(
                name = "test_suggestions",
                maxSize = 500,
                onEvict = null,
            )

        dictionaryCache =
            ManagedCache(
                name = "test_dictionary",
                maxSize = 1000,
                onEvict = null,
            )

        whenever(
            cacheMemoryManager.createCache<String, List<SpellingSuggestion>>(
                eq("spell_suggestions"),
                eq(500),
                anyOrNull(),
            ),
        ).thenReturn(suggestionCache)

        whenever(
            cacheMemoryManager.createCache<String, Boolean>(
                eq("dictionary_cache"),
                eq(1000),
                anyOrNull(),
            ),
        ).thenReturn(dictionaryCache)

        currentLanguageFlow = MutableStateFlow("en")
        whenever(languageManager.currentLanguage).thenReturn(currentLanguageFlow)

        whenever(context.assets).thenReturn(assetManager)

        whenever(assetManager.open("dictionaries/en_symspell.txt"))
            .thenAnswer { ByteArrayInputStream(testDictionary.toByteArray()) }

        spellCheckManager =
            SpellCheckManager(
                context = context,
                languageManager = languageManager,
                wordLearningEngine = wordLearningEngine,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialization loads dictionary successfully`() =
        runTest {
            val result = spellCheckManager.isWordInDictionary("hello")
            assertTrue(result)
        }

    @Test
    fun `initialization handles missing dictionary gracefully`() =
        runTest {
            whenever(assetManager.open("dictionaries/sv_symspell.txt"))
                .thenThrow(java.io.FileNotFoundException())

            currentLanguageFlow.emit("sv")

            val result = spellCheckManager.isWordInDictionary("test")
            assertFalse(result)
        }

    @Test
    fun `isWordInDictionary checks learned words first`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("myword")).thenReturn(true)

            val result = spellCheckManager.isWordInDictionary("myword")

            assertTrue(result)
            verify(wordLearningEngine).isWordLearned("myword")
        }

    @Test
    fun `isWordInDictionary falls back to dictionary when not learned`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(false)

            val result = spellCheckManager.isWordInDictionary("hello")

            verify(wordLearningEngine).isWordLearned("hello")
            assertTrue(result)
        }

    @Test
    fun `isWordInDictionary returns false for unknown word`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("xyzabc")).thenReturn(false)

            val result = spellCheckManager.isWordInDictionary("xyzabc")

            assertFalse(result)
        }

    @Test
    fun `isWordInDictionary normalizes to lowercase`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(true)

            val result = spellCheckManager.isWordInDictionary("HELLO")

            assertTrue(result)
            verify(wordLearningEngine).isWordLearned("hello")
        }

    @Test
    fun `isWordInDictionary trims whitespace`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(true)

            val result = spellCheckManager.isWordInDictionary("  hello  ")

            assertTrue(result)
            verify(wordLearningEngine).isWordLearned("hello")
        }

    @Test
    fun `isWordInDictionary rejects invalid input`() =
        runTest {
            assertFalse(spellCheckManager.isWordInDictionary(""))
            assertFalse(spellCheckManager.isWordInDictionary("   "))
            assertFalse(spellCheckManager.isWordInDictionary("12345"))
            assertFalse(spellCheckManager.isWordInDictionary("!!!"))

            verify(wordLearningEngine, never()).isWordLearned(any())
        }

    @Test
    fun `isWordInDictionary accepts valid unicode characters`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("café")).thenReturn(true)

            val result = spellCheckManager.isWordInDictionary("café")

            assertTrue(result)
        }

    @Test
    fun `isWordInDictionary rejects extremely long input`() =
        runTest {
            val longWord = "a".repeat(200)

            val result = spellCheckManager.isWordInDictionary(longWord)

            assertFalse(result)
        }

    @Test
    fun `isWordInDictionary caches positive result`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(false)

            spellCheckManager.isWordInDictionary("hello")
            spellCheckManager.isWordInDictionary("hello")

            val cacheKey = "en_hello"
            assertEquals(true, dictionaryCache.getIfPresent(cacheKey))
        }

    @Test
    fun `isWordInDictionary caches negative result`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("unknown")).thenReturn(false)

            spellCheckManager.isWordInDictionary("unknown")
            spellCheckManager.isWordInDictionary("unknown")

            val cacheKey = "en_unknown"
            assertEquals(false, dictionaryCache.getIfPresent(cacheKey))
        }

    @Test
    fun `invalidateWordCache removes from dictionary cache`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned("hello")).thenReturn(false)
            spellCheckManager.isWordInDictionary("hello")
            assertTrue(dictionaryCache.getIfPresent("en_hello") == true)

            spellCheckManager.invalidateWordCache("hello")

            assertEquals(null, dictionaryCache.getIfPresent("en_hello"))
        }

    @Test
    fun `clearCaches removes all dictionary entries`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)
            spellCheckManager.isWordInDictionary("hello")
            spellCheckManager.isWordInDictionary("world")
            assertTrue(dictionaryCache.size() > 0)

            spellCheckManager.clearCaches()

            assertEquals(0, dictionaryCache.size())
        }

    @Test
    fun `areWordsInDictionary checks all words`() =
        runTest {
            whenever(wordLearningEngine.areWordsLearned(listOf("learned", "notlearned")))
                .thenReturn(mapOf("learned" to true, "notlearned" to false))

            val results = spellCheckManager.areWordsInDictionary(listOf("learned", "notlearned"))

            assertEquals(2, results.size)
            assertEquals(true, results["learned"])
            assertEquals(false, results["notlearned"])
        }

    @Test
    fun `areWordsInDictionary uses cache for known words`() =
        runTest {
            dictionaryCache.put("en_cached", true)

            val results = spellCheckManager.areWordsInDictionary(listOf("cached", "new"))

            assertEquals(true, results["cached"])
            verify(wordLearningEngine).areWordsLearned(listOf("new"))
        }

    @Test
    fun `areWordsInDictionary batches learned word checks`() =
        runTest {
            whenever(wordLearningEngine.areWordsLearned(any()))
                .thenReturn(mapOf("word1" to false, "word2" to false, "word3" to false))

            spellCheckManager.areWordsInDictionary(listOf("word1", "word2", "word3"))

            verify(wordLearningEngine, times(1)).areWordsLearned(any())
        }

    @Test
    fun `areWordsInDictionary filters invalid input`() =
        runTest {
            val results = spellCheckManager.areWordsInDictionary(listOf("", "  ", "valid"))

            assertEquals(false, results[""])
            assertEquals(false, results["  "])
            verify(wordLearningEngine).areWordsLearned(listOf("valid"))
        }

    @Test
    fun `generateSuggestions returns learned words with high confidence`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("helo", 5))
                .thenReturn(listOf("hello" to 100, "help" to 50))

            val suggestions = spellCheckManager.generateSuggestions("helo", 3)

            assertTrue(suggestions.contains("hello"))
            assertTrue(suggestions.contains("help"))
        }

    @Test
    fun `generateSuggestions prioritizes by frequency`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("tes", 5))
                .thenReturn(
                    listOf(
                        "testing" to 1000,
                        "test" to 500,
                        "tester" to 100,
                    ),
                )

            val suggestions = spellCheckManager.generateSuggestions("tes", 3)

            assertEquals("testing", suggestions[0])
            assertEquals("test", suggestions[1])
        }

    @Test
    fun `generateSuggestions respects max limit`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 5))
                .thenReturn(
                    listOf(
                        "test1" to 10,
                        "test2" to 9,
                        "test3" to 8,
                        "test4" to 7,
                        "test5" to 6,
                    ),
                )

            val suggestions = spellCheckManager.generateSuggestions("test", 2)

            assertEquals(2, suggestions.size)
        }

    @Test
    fun `getSpellingSuggestionsWithConfidence includes metadata`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("helo", 5))
                .thenReturn(listOf("hello" to 100))

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("helo")

            assertTrue(suggestions.isNotEmpty())
            assertEquals("learned", suggestions.first().source)
            assertTrue(suggestions.first().confidence > 0.85)
            assertEquals(0, suggestions.first().ranking)
        }

    @Test
    fun `getSpellingSuggestionsWithConfidence boosts high frequency words`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 5))
                .thenReturn(
                    listOf(
                        "common" to 10000,
                        "rare" to 1,
                    ),
                )

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("test")

            val commonSuggestion = suggestions.find { it.word == "common" }!!
            val rareSuggestion = suggestions.find { it.word == "rare" }!!
            assertTrue(commonSuggestion.confidence > rareSuggestion.confidence)
        }

    @Test
    fun `generateSuggestions caches results`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("test", 5))
                .thenReturn(listOf("testing" to 10))

            spellCheckManager.generateSuggestions("test", 3)
            spellCheckManager.generateSuggestions("test", 3)

            assertTrue(suggestionCache.getIfPresent("en_test") != null)
        }

    @Test
    fun `invalidateWordCache removes from suggestion cache`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("word", 5))
                .thenReturn(listOf("words" to 10))
            spellCheckManager.generateSuggestions("word", 3)
            assertTrue(suggestionCache.getIfPresent("en_word") != null)

            spellCheckManager.invalidateWordCache("word")

            assertEquals(null, suggestionCache.getIfPresent("en_word"))
        }

    @Test
    fun `clearCaches removes all suggestions`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any()))
                .thenReturn(listOf("word" to 10))
            spellCheckManager.generateSuggestions("test1", 3)
            spellCheckManager.generateSuggestions("test2", 3)
            assertTrue(suggestionCache.size() > 0)

            spellCheckManager.clearCaches()

            assertEquals(0, suggestionCache.size())
        }

    @Test
    fun `blacklistSuggestion filters word from suggestions`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("bad", 5))
                .thenReturn(listOf("badword" to 100, "badge" to 50))

            spellCheckManager.blacklistSuggestion("badword")

            val suggestions = spellCheckManager.generateSuggestions("bad", 3)

            assertFalse(suggestions.contains("badword"))
            assertTrue(suggestions.contains("badge"))
        }

    @Test
    fun `blacklistSuggestion normalizes word`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("bad", 5))
                .thenReturn(listOf("badword" to 100))

            spellCheckManager.blacklistSuggestion("BadWord")

            val suggestions = spellCheckManager.generateSuggestions("bad", 3)
            assertFalse(suggestions.contains("badword"))
        }

    @Test
    fun `blacklistSuggestion clears relevant caches`() =
        runTest {
            dictionaryCache.put("en_bad", true)
            suggestionCache.put("en_bad", emptyList())

            spellCheckManager.blacklistSuggestion("bad")

            assertEquals(null, dictionaryCache.getIfPresent("en_bad"))
            assertEquals(null, suggestionCache.getIfPresent("en_bad"))
        }

    @Test
    fun `removeFromBlacklist allows word in suggestions again`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("word", 5))
                .thenReturn(listOf("word" to 100))

            spellCheckManager.blacklistSuggestion("word")
            val blacklisted = spellCheckManager.generateSuggestions("word", 3)
            assertFalse(blacklisted.contains("word"))

            spellCheckManager.removeFromBlacklist("word")
            val unblacklisted = spellCheckManager.generateSuggestions("word", 3)
            assertTrue(unblacklisted.contains("word"))
        }

    @Test
    fun `removeFromBlacklist clears caches`() =
        runTest {
            spellCheckManager.blacklistSuggestion("word")
            dictionaryCache.put("en_word", false)

            spellCheckManager.removeFromBlacklist("word")

            assertEquals(null, dictionaryCache.getIfPresent("en_word"))
        }

    @Test
    fun `blacklisted words filtered from suggestions`() =
        runTest {
            spellCheckManager.blacklistSuggestion("offensive")

            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("off", 5))
                .thenReturn(listOf("offensive" to 100, "offer" to 50))

            val suggestions = spellCheckManager.generateSuggestions("off", 3)

            assertFalse(suggestions.contains("offensive"))
            assertTrue(suggestions.contains("offer"))
        }

    @Test
    fun `language switch creates separate cache namespace`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned(any())).thenReturn(false)

            spellCheckManager.isWordInDictionary("hello")
            assertTrue(dictionaryCache.getIfPresent("en_hello") != null)

            val swedishDictionary = "hej 1000\nvärld 800"
            whenever(assetManager.open("dictionaries/sv_symspell.txt"))
                .thenReturn(ByteArrayInputStream(swedishDictionary.toByteArray()))
            currentLanguageFlow.emit("sv")

            spellCheckManager.isWordInDictionary("hej")

            assertTrue(dictionaryCache.getIfPresent("en_hello") != null)
            assertTrue(dictionaryCache.getIfPresent("sv_hej") != null)
        }

    @Test
    fun `getCommonWords returns dictionary words with frequencies`() =
        runTest {
            val words = spellCheckManager.getCommonWords()

            assertTrue(words.isNotEmpty())
            assertTrue(words.any { it.first == "hello" && it.second == 1000 })
            assertTrue(words.any { it.first == "world" && it.second == 800 })
        }

    @Test
    fun `getCommonWords sorts by frequency descending`() =
        runTest {
            val words = spellCheckManager.getCommonWords()

            for (i in 0 until words.size - 1) {
                assertTrue(words[i].second >= words[i + 1].second)
            }
        }

    @Test
    fun `getCommonWords filters by length`() =
        runTest {
            val words = spellCheckManager.getCommonWords()

            assertTrue(words.all { it.first.length in 2..15 })
        }

    @Test
    fun `getCommonWords excludes blacklisted words`() =
        runTest {
            spellCheckManager.blacklistSuggestion("hello")

            val words = spellCheckManager.getCommonWords()

            assertFalse(words.any { it.first == "hello" })
        }

    @Test
    fun `getCommonWords filters non-alphabetic words`() =
        runTest {
            val words = spellCheckManager.getCommonWords()

            assertTrue(
                words.all { word ->
                    word.first.all { char ->
                        Character.isLetter(char.code) ||
                            Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                            com.urik.keyboard.utils.TextMatchingUtils
                                .isValidWordPunctuation(char)
                    }
                },
            )
        }

    @Test
    fun `isWordInDictionary handles WordLearningEngine exception`() =
        runTest {
            whenever(wordLearningEngine.isWordLearned(any()))
                .thenThrow(RuntimeException("Database error"))

            val result = spellCheckManager.isWordInDictionary("test")

            assertFalse(result)
        }

    @Test
    fun `generateSuggestions falls back to dictionary when learned words fail`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any()))
                .thenThrow(RuntimeException("Error"))

            val suggestions = spellCheckManager.generateSuggestions("test", 3)

            assertTrue(suggestions.contains("test") || suggestions.contains("testing"))
        }

    @Test
    fun `areWordsInDictionary handles partial failures`() =
        runTest {
            whenever(wordLearningEngine.areWordsLearned(any()))
                .thenThrow(RuntimeException("Error"))

            val results = spellCheckManager.areWordsInDictionary(listOf("word1", "word2"))

            assertEquals(2, results.size)
        }

    @Test
    fun `getCommonWords handles IOException gracefully`() =
        runTest {
            whenever(assetManager.open("dictionaries/en_symspell.txt"))
                .thenThrow(java.io.IOException("File error"))

            val failingManager = SpellCheckManager(context, languageManager, wordLearningEngine, cacheMemoryManager, testDispatcher)
            val words = failingManager.getCommonWords()

            assertTrue(words.isEmpty())
        }

    @Test
    fun `blacklistSuggestion removes word from cached suggestion lists`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("te", 5))
                .thenReturn(listOf("test" to 100, "team" to 80, "text" to 60))

            val suggestions1 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
            assertTrue(suggestions1.any { it.word == "test" })

            spellCheckManager.blacklistSuggestion("test")

            val suggestions2 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
            assertFalse(suggestions2.any { it.word == "test" })
            assertTrue(suggestions2.any { it.word == "team" })
        }

    @Test
    fun `removeFromBlacklist includes word in cached suggestion lists`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("te", 5))
                .thenReturn(listOf("test" to 100, "team" to 80))

            spellCheckManager.blacklistSuggestion("test")

            val suggestions1 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
            assertFalse(suggestions1.any { it.word == "test" })

            spellCheckManager.removeFromBlacklist("test")

            val suggestions2 = spellCheckManager.getSpellingSuggestionsWithConfidence("te")
            assertTrue(suggestions2.any { it.word == "test" })
        }

    @Test
    fun `corrections from symspell have varied confidence based on frequency`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any()))
                .thenReturn(emptyList())

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("helo")

            val symspellSuggestions = suggestions.filter { it.source == "symspell" }
            assertTrue("Should have multiple SymSpell suggestions", symspellSuggestions.size >= 2)

            val confidences = symspellSuggestions.map { it.confidence }.distinct()
            assertTrue(
                "SymSpell suggestions should have different confidence scores based on frequency, not all capped at same value",
                confidences.size > 1,
            )
        }

    @Test
    fun `learned word contraction gets guaranteed confidence`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("dont", 5))
                .thenReturn(listOf("don't" to 5))

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")

            val contractionSuggestion = suggestions.find { it.word == "don't" && it.source == "learned" }
            assertNotNull("Should find learned contraction don't", contractionSuggestion)
            assertEquals(
                "Learned contraction should have guaranteed confidence",
                SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                contractionSuggestion!!.confidence,
                0.001,
            )
        }

    @Test
    fun `prefix completion contraction gets guaranteed confidence`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any()))
                .thenReturn(emptyList())

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")

            val contractionSuggestion = suggestions.find { it.word == "don't" }
            assertNotNull("Should find contraction don't from dictionary", contractionSuggestion)
            assertEquals(
                "Prefix completion contraction should have guaranteed confidence",
                SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                contractionSuggestion!!.confidence,
                0.001,
            )
        }

    @Test
    fun `symspell contraction gets guaranteed confidence`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any()))
                .thenReturn(emptyList())

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("havent")

            val contractionSuggestion = suggestions.find { it.word == "haven't" }
            assertNotNull("Should find contraction haven't from SymSpell", contractionSuggestion)
            assertEquals(
                "SymSpell contraction should have guaranteed confidence",
                SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                contractionSuggestion!!.confidence,
                0.001,
            )
        }

    @Test
    fun `contraction ranks higher than high frequency learned words`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("dont", 5))
                .thenReturn(
                    listOf(
                        "donate" to 100,
                        "donkey" to 90,
                        "done" to 80,
                    ),
                )

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("dont")

            val contractionSuggestion = suggestions.find { it.word == "don't" }
            assertNotNull("Should find contraction don't", contractionSuggestion)

            val contractionIndex = suggestions.indexOfFirst { it.word == "don't" }
            assertEquals(
                "Contraction should rank first despite lower learned word frequencies",
                0,
                contractionIndex,
            )
        }

    @Test
    fun `contraction appears when many high confidence suggestions exist`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("cant", 5))
                .thenReturn(
                    listOf(
                        "canteen" to 100,
                        "canton" to 90,
                        "cantor" to 80,
                        "cantaloupe" to 70,
                    ),
                )

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("cant")

            val contractionSuggestion = suggestions.find { it.word == "can't" }
            assertNotNull("Should find contraction can't despite many learned words", contractionSuggestion)

            val contractionIndex = suggestions.indexOfFirst { it.word == "can't" }
            assertTrue(
                "Contraction should appear in top 3 positions",
                contractionIndex < 3,
            )
        }

    @Test
    fun `contraction not boosted when user types with apostrophe`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any()))
                .thenReturn(emptyList())

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("don't")

            val dontSuggestion = suggestions.find { it.word == "don't" }
            if (dontSuggestion != null) {
                assertNotEquals(
                    "Should not apply contraction boost when input already has apostrophe",
                    SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                    dontSuggestion.confidence,
                    0.001,
                )
            }
        }

    @Test
    fun `reverse direction contraction not boosted`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("don't", 5))
                .thenReturn(listOf("dont" to 10))

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("don't")

            val dontSuggestion = suggestions.find { it.word == "dont" }
            if (dontSuggestion != null) {
                assertNotEquals(
                    "Should not apply contraction boost for reverse direction (don't -> dont)",
                    SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                    dontSuggestion.confidence,
                    0.001,
                )
            }
        }

    @Test
    fun `multiple contractions can coexist with correct ranking`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency(any(), any()))
                .thenReturn(emptyList())

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("youre")

            val contractionSuggestion = suggestions.find { it.word == "you're" }
            assertNotNull("Should find you're contraction", contractionSuggestion)
            assertEquals(
                "you're should have guaranteed confidence",
                SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                contractionSuggestion!!.confidence,
                0.001,
            )
        }

    @Test
    fun `i18n French contraction l'homme gets guaranteed confidence`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("lhomme", 5))
                .thenReturn(listOf("l'homme" to 10))

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("lhomme")

            val contractionSuggestion = suggestions.find { it.word == "l'homme" }
            assertNotNull("Should find French contraction l'homme", contractionSuggestion)
            assertEquals(
                "French contraction should have guaranteed confidence",
                SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                contractionSuggestion!!.confidence,
                0.001,
            )
        }

    @Test
    fun `i18n German hyphenated compound gets guaranteed confidence`() =
        runTest {
            whenever(wordLearningEngine.getSimilarLearnedWordsWithFrequency("coworker", 5))
                .thenReturn(listOf("co-worker" to 10))

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("coworker")

            val hyphenatedSuggestion = suggestions.find { it.word == "co-worker" }
            assertNotNull("Should find hyphenated word co-worker", hyphenatedSuggestion)
            assertEquals(
                "Hyphenated compound should have guaranteed confidence",
                SpellCheckConstants.CONTRACTION_GUARANTEED_CONFIDENCE,
                hyphenatedSuggestion!!.confidence,
                0.001,
            )
        }
}
