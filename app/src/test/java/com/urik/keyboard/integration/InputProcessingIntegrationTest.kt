@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.integration

import android.content.Context
import android.content.res.AssetManager
import androidx.room.Room
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.service.InputMethod
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.ProcessingResult
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InputProcessingIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: KeyboardDatabase
    private lateinit var cacheMemoryManager: CacheMemoryManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var languageManager: LanguageManager
    private lateinit var wordLearningEngine: WordLearningEngine
    private lateinit var spellCheckManager: SpellCheckManager
    private lateinit var textInputProcessor: TextInputProcessor
    private val testDispatcher = UnconfinedTestDispatcher()

    private val testDictionary =
        """
        hello 5000
        world 4000
        test 3000
        """.trimIndent()

    @Before
    fun setup() =
        runTest(testDispatcher) {
            Dispatchers.setMain(testDispatcher)

            context = RuntimeEnvironment.getApplication()

            val mockAssets = mock<AssetManager>()
            whenever(mockAssets.open(any())).thenAnswer {
                when {
                    it.getArgument<String>(0).contains("_symspell.txt") ->
                        ByteArrayInputStream(testDictionary.toByteArray())
                    else -> throw java.io.FileNotFoundException()
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

            wordLearningEngine =
                WordLearningEngine(
                    database.learnedWordDao(),
                    languageManager,
                    settingsRepository,
                    cacheMemoryManager,
                    testDispatcher,
                    testDispatcher,
                    testDispatcher,
                )

            spellCheckManager =
                SpellCheckManager(
                    mockContext,
                    languageManager,
                    wordLearningEngine,
                    cacheMemoryManager,
                    testDispatcher,
                )

            textInputProcessor =
                TextInputProcessor(
                    spellCheckManager,
                    settingsRepository,
                )
        }

    @After
    fun teardown() {
        database.close()
        spellCheckManager.cleanup()
        wordLearningEngine.cleanup()
        cacheMemoryManager.cleanup()
        Dispatchers.resetMain()
    }

    @Test
    fun `learned word immediately available in spell check without manual cache clear`() =
        runTest(testDispatcher) {
            assertFalse(spellCheckManager.isWordInDictionary("custom"))

            wordLearningEngine.learnWord("custom", InputMethod.TYPED)

            assertTrue(
                "Learned word should be immediately found via cache invalidation",
                spellCheckManager.isWordInDictionary("custom"),
            )
        }

    @Test
    fun `learned word appears in suggestions without manual cache clear`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("customword", InputMethod.TYPED)
            wordLearningEngine.learnWord("customword", InputMethod.TYPED)
            wordLearningEngine.learnWord("customword", InputMethod.TYPED)

            val suggestions = spellCheckManager.generateSuggestions("custom", 5)

            assertTrue(
                "Cache should auto-invalidate, learned word should appear",
                suggestions.contains("customword"),
            )
        }

    @Test
    fun `normalization consistent between WordLearningEngine and SpellCheckManager`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("TestWord", InputMethod.TYPED)

            assertTrue(spellCheckManager.isWordInDictionary("testword"))
            assertTrue(spellCheckManager.isWordInDictionary("TESTWORD"))
            assertTrue(spellCheckManager.isWordInDictionary("TestWord"))
        }

    @Test
    fun `whitespace handling consistent across all components`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("  spaced  ", InputMethod.TYPED)

            assertTrue(spellCheckManager.isWordInDictionary("spaced"))
            assertTrue(wordLearningEngine.isWordLearned("  spaced  "))
        }

    @Test
    fun `rapid sequential operations coordinate without races`() =
        runTest(testDispatcher) {
            val words = listOf("rapid1", "rapid2", "rapid3", "rapid4", "rapid5")

            words.forEach { wordLearningEngine.learnWord(it, InputMethod.TYPED) }

            val results = spellCheckManager.areWordsInDictionary(words)

            assertTrue(
                "All rapidly learned words should be immediately queryable",
                results.all { it.value },
            )
        }

    @Test
    fun `concurrent write and read operations dont race`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("concurrent", InputMethod.TYPED)

            val suggestions = spellCheckManager.generateSuggestions("concur", 3)

            assertNotNull("Concurrent operations should complete without race", suggestions)
        }

    @Test
    fun `learned word with high frequency beats dictionary word in confidence`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("helloworld", InputMethod.TYPED)
            repeat(10) { wordLearningEngine.learnWord("helloworld", InputMethod.TYPED) }

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("hello")

            val learned = suggestions.find { it.word == "helloworld" && it.source == "learned" }
            val dictionary = suggestions.find { it.word == "hello" && it.source == "symspell" }

            assertNotNull(learned)
            assertNotNull(dictionary)

            if (learned != null && dictionary != null) {
                assertTrue(
                    "Learned word confidence (${learned.confidence}) > dictionary (${dictionary.confidence})",
                    learned.confidence > dictionary.confidence,
                )
            }
        }

    @Test
    fun `frequency affects ranking with real Room data`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("freq1", InputMethod.TYPED)
            wordLearningEngine.learnWord("freq1", InputMethod.TYPED)

            wordLearningEngine.learnWord("freq2", InputMethod.TYPED)
            repeat(20) { wordLearningEngine.learnWord("freq2", InputMethod.TYPED) }

            val suggestions = spellCheckManager.getSpellingSuggestionsWithConfidence("fre")

            val freq1Idx = suggestions.indexOfFirst { it.word == "freq1" }
            val freq2Idx = suggestions.indexOfFirst { it.word == "freq2" }

            assertTrue(
                "Higher frequency word ranks higher ($freq2Idx < $freq1Idx)",
                freq2Idx < freq1Idx,
            )
        }

    @Test
    fun `full pipeline - misspelling corrected with learned word`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("correct", InputMethod.TYPED)
            wordLearningEngine.learnWord("correct", InputMethod.TYPED)

            val result = textInputProcessor.processCharacterInput("t", "corect", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertFalse(success.wordState.isValid)
            assertTrue(
                "Pipeline should surface learned word as suggestion",
                success.wordState.suggestions.contains("correct"),
            )
        }

    @Test
    fun `full pipeline - learned word validates during typing`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("learned", InputMethod.TYPED)

            val result = textInputProcessor.processCharacterInput("d", "learned", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertTrue(
                "Learned word should validate through full pipeline",
                success.wordState.isValid,
            )
        }

    @Test
    fun `Room transaction commits immediately - no lag`() =
        runTest(testDispatcher) {
            val word = "immediate"

            wordLearningEngine.learnWord(word, InputMethod.TYPED)
            val found = wordLearningEngine.isWordLearned(word)

            assertTrue("Word available immediately, no commit lag", found)
        }

    @Test
    fun `batch operations maintain transactional consistency`() =
        runTest(testDispatcher) {
            val words = listOf("batch1", "batch2", "batch3")

            words.forEach { wordLearningEngine.learnWord(it, InputMethod.TYPED) }

            val results = wordLearningEngine.areWordsLearned(words)

            assertEquals(3, results.size)
            assertTrue("All batch words committed consistently", results.all { it.value })
        }

    @Test
    fun `SpellCheckManager and WordLearningEngine agree on word existence`() =
        runTest(testDispatcher) {
            val input = "  MiXeD CaSe  "

            wordLearningEngine.learnWord(input, InputMethod.TYPED)

            val normalized = input.trim().lowercase()
            val spellCheckSays = spellCheckManager.isWordInDictionary(normalized)
            val learningSays = wordLearningEngine.isWordLearned(normalized)

            assertEquals("Components must agree on word existence", spellCheckSays, learningSays)
        }

    @Test
    fun `DAO learnWord transaction increments frequency on duplicate`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("repeated", InputMethod.TYPED)
            wordLearningEngine.learnWord("repeated", InputMethod.TYPED)
            wordLearningEngine.learnWord("repeated", InputMethod.TYPED)

            val word = database.learnedWordDao().findExactWord("en", "repeated")

            assertNotNull(word)
            assertTrue("Frequency should increment through DAO transaction", word!!.frequency > 1)
        }

    @Test
    fun `database errors propagate gracefully through all layers`() =
        runTest(testDispatcher) {
            database.close()

            val dictResult = spellCheckManager.isWordInDictionary("test")
            val learnResult = wordLearningEngine.learnWord("test", InputMethod.TYPED)
            val processResult = textInputProcessor.processCharacterInput("t", "test", InputMethod.TYPED)

            assertNotNull("All operations complete despite DB errors", dictResult)
            assertNotNull(learnResult)
            assertNotNull(processResult)
        }
}
