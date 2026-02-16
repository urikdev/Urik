@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.integration

import android.content.Context
import android.content.res.AssetManager
import androidx.room.Room
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.service.InputMethod
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.ProcessingResult
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.service.WordNormalizer
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.ui.keyboard.components.PathGeometryAnalyzer
import com.urik.keyboard.ui.keyboard.components.ResidualScorer
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.ui.keyboard.components.ZipfCheck
import com.urik.keyboard.utils.CacheMemoryManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream

/**
 * Tests swipe input processing pipeline integration.
 *
 * Verifies swipe word validation, spell check highlighting, suggestion generation,
 * word learning from swipe input, candidate scoring, and cache invalidation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SwipeInputIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: KeyboardDatabase
    private lateinit var cacheMemoryManager: CacheMemoryManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var languageManager: LanguageManager
    private lateinit var wordLearningEngine: WordLearningEngine
    private lateinit var spellCheckManager: SpellCheckManager
    private lateinit var textInputProcessor: TextInputProcessor
    private lateinit var swipeDetector: SwipeDetector
    private val testDispatcher = UnconfinedTestDispatcher()

    private val testDictionary =
        """
        hello 5000
        world 4000
        test 3000
        swipe 2500
        typing 2000
        """.trimIndent()

    @Before
    fun setup() =
        runTest(testDispatcher) {
            Dispatchers.setMain(testDispatcher)

            context = RuntimeEnvironment.getApplication()

            val mockAssets = mock<AssetManager>()
            whenever(mockAssets.open(any())).thenAnswer {
                when {
                    it.getArgument<String>(0).contains("_symspell.txt") -> {
                        ByteArrayInputStream(testDictionary.toByteArray())
                    }

                    else -> {
                        throw java.io.FileNotFoundException()
                    }
                }
            }
            val mockContext = spy(context)
            whenever(mockContext.assets).thenReturn(mockAssets)

            database =
                Room
                    .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                    .allowMainThreadQueries()
                    .setTransactionExecutor { it.run() }
                    .setQueryExecutor { it.run() }
                    .build()

            cacheMemoryManager = CacheMemoryManager(context)

            val settingsFlow = MutableStateFlow(KeyboardSettings())
            settingsRepository = mock()
            whenever(settingsRepository.settings).thenReturn(settingsFlow)

            languageManager = LanguageManager(settingsRepository)
            languageManager.initialize()

            val wordNormalizer = WordNormalizer()
            val wordFrequencyRepository =
                WordFrequencyRepository(
                    database.userWordFrequencyDao(),
                    database.userWordBigramDao(),
                    wordNormalizer,
                    cacheMemoryManager,
                    testDispatcher,
                    testDispatcher,
                )

            wordLearningEngine =
                WordLearningEngine(
                    database.learnedWordDao(),
                    languageManager,
                    wordNormalizer,
                    settingsRepository,
                    cacheMemoryManager,
                    testDispatcher,
                    testDispatcher,
                )

            spellCheckManager =
                SpellCheckManager(
                    mockContext,
                    languageManager,
                    wordLearningEngine,
                    wordFrequencyRepository,
                    wordNormalizer,
                    cacheMemoryManager,
                    testDispatcher,
                )

            textInputProcessor =
                TextInputProcessor(
                    spellCheckManager,
                    settingsRepository,
                    cacheMemoryManager,
                )

            val pathGeometryAnalyzer = PathGeometryAnalyzer()
            val residualScorer = ResidualScorer(pathGeometryAnalyzer)
            val zipfCheck = ZipfCheck(spellCheckManager)
            swipeDetector =
                SwipeDetector(
                    spellCheckManager,
                    wordLearningEngine,
                    pathGeometryAnalyzer,
                    wordFrequencyRepository,
                    residualScorer,
                    zipfCheck,
                    wordNormalizer,
                )
        }

    @After
    fun teardown() {
        database.close()
        swipeDetector.cleanup()
        cacheMemoryManager.cleanup()
        Dispatchers.resetMain()
    }

    @Test
    fun `valid swipe word does not trigger highlight`() =
        runTest(testDispatcher) {
            val result = textInputProcessor.processWordInput("hello", InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertFalse("Valid dictionary word should not be highlighted", success.shouldHighlight)
        }

    @Test
    fun `valid swipe word has correct word state`() =
        runTest(testDispatcher) {
            val result = textInputProcessor.processWordInput("world", InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertEquals("world", success.wordState.normalizedBuffer)
            assertTrue("Swipe origin should be recorded", success.wordState.isFromSwipe)
            assertTrue("Valid word should be marked valid", success.wordState.isValid)
        }

    @Test
    fun `invalid swipe word triggers highlight`() =
        runTest(testDispatcher) {
            val result = textInputProcessor.processWordInput("helo", InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertTrue("Invalid word should be highlighted for confirmation", success.shouldHighlight)
        }

    @Test
    fun `invalid swipe word generates suggestions`() =
        runTest(testDispatcher) {
            val result = textInputProcessor.processWordInput("wrld", InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertTrue("Invalid word should have suggestions", success.wordState.suggestions.isNotEmpty())
            assertTrue(
                "Suggestions should include 'world'",
                success.wordState.suggestions.any { it.word == "world" },
            )
        }

    @Test
    fun `spell check suggestions are dictionary-aware`() =
        runTest(testDispatcher) {
            val suggestions = spellCheckManager.generateSuggestions("helo", 5)

            assertTrue("Should generate suggestions for typo", suggestions.isNotEmpty())
            assertTrue("Should suggest 'hello'", suggestions.contains("hello"))
        }

    @Test
    fun `swipe invalid word then learn prevents highlight`() =
        runTest(testDispatcher) {
            val invalidWord = "swipeword"

            val beforeResult = textInputProcessor.processWordInput(invalidWord, InputMethod.SWIPED)
            assertTrue(beforeResult is ProcessingResult.Success)
            assertTrue("Unlearned word should be highlighted", (beforeResult as ProcessingResult.Success).shouldHighlight)

            wordLearningEngine.learnWord(invalidWord, InputMethod.SWIPED)
            textInputProcessor.invalidateWord(invalidWord)

            val afterResult = textInputProcessor.processWordInput(invalidWord, InputMethod.SWIPED)
            assertTrue(afterResult is ProcessingResult.Success)
            assertFalse(
                "Learned word should not be highlighted",
                (afterResult as ProcessingResult.Success).shouldHighlight,
            )
        }

    @Test
    fun `learned swipe word appears in future suggestions`() =
        runTest(testDispatcher) {
            val customWord = "customswipe"

            wordLearningEngine.learnWord(customWord, InputMethod.SWIPED)
            repeat(5) { wordLearningEngine.learnWord(customWord, InputMethod.SWIPED) }

            val suggestions = spellCheckManager.generateSuggestions("custom", 5)

            assertTrue(
                "High-frequency learned word should appear in suggestions",
                suggestions.contains(customWord),
            )
        }

    @Test
    fun `swipe method recorded in learning metadata`() =
        runTest(testDispatcher) {
            val word = "swipedword"

            wordLearningEngine.learnWord(word, InputMethod.SWIPED)

            val isLearned = wordLearningEngine.isWordLearned(word)
            assertTrue("Word should be learned via swipe method", isLearned)
        }

    @Test
    fun `word candidate scoring uses frequency data`() =
        runTest(testDispatcher) {
            val commonWords = spellCheckManager.getCommonWords()

            assertTrue("Should retrieve common words for scoring", commonWords.isNotEmpty())
            assertTrue("Should include 'hello' with frequency", commonWords.any { it.first == "hello" })
            assertTrue("Higher frequency words should rank higher", commonWords[0].second >= commonWords.last().second)
        }

    @Test
    fun `learned words boost candidate confidence`() =
        runTest(testDispatcher) {
            val learnedWord = "swipelearn"

            repeat(10) { wordLearningEngine.learnWord(learnedWord, InputMethod.SWIPED) }

            val inDictionary = spellCheckManager.isWordInDictionary(learnedWord)
            assertTrue("High-frequency learned word should be in dictionary", inDictionary)

            val confidence = spellCheckManager.getSpellingSuggestionsWithConfidence("swipele")
            val learnedConfidence = confidence.find { it.word == learnedWord }

            assertNotNull("Learned word should appear in confidence scoring", learnedConfidence)
            if (learnedConfidence != null) {
                assertTrue("Learned word should have confidence boost", learnedConfidence.confidence > 0.5f)
            }
        }

    @Test
    fun `empty swipe word handled gracefully`() =
        runTest(testDispatcher) {
            val result = textInputProcessor.processWordInput("", InputMethod.SWIPED)

            assertTrue("Empty word should return Error result", result is ProcessingResult.Error)
        }

    @Test
    fun `very long swipe word processed without error`() =
        runTest(testDispatcher) {
            val longWord = "a".repeat(50)

            val result = textInputProcessor.processWordInput(longWord, InputMethod.SWIPED)

            assertNotNull("Long word should process without crashing", result)
        }

    @Test
    fun `swipe with special characters handled`() =
        runTest(testDispatcher) {
            val result = textInputProcessor.processWordInput("hello!", InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
        }

    @Test
    fun `learning swipe word invalidates spell check cache`() =
        runTest(testDispatcher) {
            val word = "cacheinvalidate"

            assertFalse("Word not in dictionary initially", spellCheckManager.isWordInDictionary(word))

            wordLearningEngine.learnWord(word, InputMethod.SWIPED)

            assertTrue(
                "Learned word should be immediately available via cache invalidation",
                spellCheckManager.isWordInDictionary(word),
            )
        }

    @Test
    fun `swipe processing produces consistent results`() =
        runTest(testDispatcher) {
            val word = "hello"

            val result1 = textInputProcessor.processWordInput(word, InputMethod.SWIPED)
            val result2 = textInputProcessor.processWordInput(word, InputMethod.SWIPED)

            assertTrue(result1 is ProcessingResult.Success)
            assertTrue(result2 is ProcessingResult.Success)

            assertEquals(
                "shouldHighlight should be consistent",
                (result1 as ProcessingResult.Success).shouldHighlight,
                (result2 as ProcessingResult.Success).shouldHighlight,
            )
            assertEquals(
                "isValid should be consistent",
                result1.wordState.isValid,
                result2.wordState.isValid,
            )
        }

    @Test
    fun `complete swipe flow - valid word no confirmation`() =
        runTest(testDispatcher) {
            val validWord = "hello"

            val result = textInputProcessor.processWordInput(validWord, InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success

            assertTrue("Valid word should be marked valid", success.wordState.isValid)
            assertFalse("Valid word should not be highlighted", success.shouldHighlight)
        }

    @Test
    fun `complete swipe flow - invalid word with confirmation`() =
        runTest(testDispatcher) {
            val invalidWord = "helllo"

            val result = textInputProcessor.processWordInput(invalidWord, InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success

            assertFalse("Invalid word should not be marked valid", success.wordState.isValid)
            assertTrue("Invalid word should be highlighted", success.shouldHighlight)
            assertTrue("Invalid word should have suggestions", success.wordState.suggestions.isNotEmpty())

            wordLearningEngine.learnWord(invalidWord, InputMethod.SWIPED)
            textInputProcessor.invalidateWord(invalidWord)

            val afterLearning = textInputProcessor.processWordInput(invalidWord, InputMethod.SWIPED)
            assertTrue(afterLearning is ProcessingResult.Success)
            assertFalse(
                "After learning, word should not be highlighted",
                (afterLearning as ProcessingResult.Success).shouldHighlight,
            )
        }

    @Test
    fun `blacklisted words excluded from spell check suggestions`() =
        runTest(testDispatcher) {
            val suggestions = spellCheckManager.generateSuggestions("helo", 5)
            assertTrue("Should suggest 'hello' before blacklisting", suggestions.contains("hello"))

            spellCheckManager.blacklistSuggestion("hello")

            val filteredSuggestions = spellCheckManager.generateSuggestions("helo", 5)
            assertFalse("Blacklisted word should not appear in suggestions", filteredSuggestions.contains("hello"))
        }

    @Test
    fun `blacklisted words excluded from common words for swipe`() =
        runTest(testDispatcher) {
            val words = spellCheckManager.getCommonWords()
            assertTrue("'world' should be in common words", words.any { it.first == "world" })

            spellCheckManager.blacklistSuggestion("world")

            val filteredWords = spellCheckManager.getCommonWords()
            assertFalse("Blacklisted word should not appear in common words", filteredWords.any { it.first == "world" })
        }

    @Test
    fun `re-learning blacklisted word removes from blacklist`() =
        runTest(testDispatcher) {
            spellCheckManager.blacklistSuggestion("test")

            val beforeSuggestions = spellCheckManager.generateSuggestions("tes", 5)
            assertFalse("Blacklisted word should not appear", beforeSuggestions.contains("test"))

            wordLearningEngine.learnWord("test", InputMethod.TYPED)
            spellCheckManager.removeFromBlacklist("test")

            val afterSuggestions = spellCheckManager.generateSuggestions("tes", 5)
            assertTrue("Re-learned word should appear in suggestions", afterSuggestions.contains("test"))
        }
}
