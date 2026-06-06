@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.view.inputmethod.InputConnection
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CaseTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SuggestionPipelineTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockIc: InputConnection
    private lateinit var mockSwipeDetector: SwipeDetector
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var mockSpellCheckManager: SpellCheckManager
    private lateinit var mockWordLearningEngine: WordLearningEngine
    private lateinit var mockWordFrequencyRepository: WordFrequencyRepository
    private lateinit var mockLanguageManager: LanguageManager
    private lateinit var mockCaseTransformer: CaseTransformer
    private lateinit var mockScriptConverterRegistry: ScriptConverterRegistry

    private lateinit var inputState: InputStateManager
    private lateinit var outputBridge: OutputBridge
    private lateinit var pipeline: SuggestionPipeline

    private var capturedSuggestions: List<String> = emptyList()
    private var suggestionsCleared = false
    private var capturedDegradedIndicator: Boolean? = null

    @Before
    fun setup() = runBlocking {
        Dispatchers.setMain(testDispatcher)

        mockIc = mock()
        mockSwipeDetector = mock()
        mockSwipeSpaceManager = mock()
        mockTextInputProcessor = mock()
        mockSpellCheckManager = mock()
        mockWordLearningEngine = mock()
        mockWordFrequencyRepository = mock()
        mockLanguageManager = mock()
        mockCaseTransformer = mock()
        mockScriptConverterRegistry = mock()

        whenever(mockIc.beginBatchEdit()).thenReturn(true)
        whenever(mockIc.endBatchEdit()).thenReturn(true)
        whenever(mockIc.commitText(any(), any())).thenReturn(true)
        whenever(mockIc.deleteSurroundingText(any(), any())).thenReturn(true)
        whenever(mockIc.finishComposingText()).thenReturn(true)
        whenever(mockLanguageManager.currentLanguage).thenReturn(
            kotlinx.coroutines.flow.MutableStateFlow("en")
        )
        whenever(mockWordLearningEngine.learnWord(any(), any())).thenReturn(Result.success(null as LearnedWord?))

        val viewCallback = object : ViewCallback {
            override fun clearSuggestions() {
                suggestionsCleared = true
            }

            override fun updateSuggestions(suggestions: List<String>) {
                capturedSuggestions = suggestions
            }

            override fun showDegradedIndicator(degraded: Boolean) {
                capturedDegradedIndicator = degraded
            }
        }

        inputState = InputStateManager(
            viewCallback = viewCallback,
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )

        outputBridge = OutputBridge(
            state = inputState,
            swipeDetector = mockSwipeDetector,
            swipeSpaceManager = mockSwipeSpaceManager,
            icProvider = { mockIc }
        )

        pipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeSuggestionPipelineHost()
        )
    }

    private inner class FakeSuggestionPipelineHost : SuggestionPipelineHost {
        override fun showSuggestions(): Boolean = true
        override fun effectiveSuggestionCount(): Int = 3
        override fun getKeyboardState(): KeyboardState = KeyboardState()
        override fun shouldAutoCapitalize(text: String): Boolean = false
        override fun currentLanguage(): String = "en"
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `coordinatePostCommitReplacement learns word on autocorrect undo`() = runTest(testDispatcher) {
        val replacementState = PostCommitReplacementState(
            originalWord = "teh",
            committedWord = "the"
        )
        whenever(mockIc.getTextBeforeCursor(4, 0)).thenReturn("the ")
        whenever(mockTextInputProcessor.getCurrentSettings()).thenReturn(
            com.urik.keyboard.settings.KeyboardSettings()
        )
        whenever(mockSpellCheckManager.isWordInDictionary(any())).thenReturn(false)

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "teh",
            replacementState = replacementState,
            checkAutoCapitalization = {}
        )

        verify(mockWordLearningEngine).learnWord("teh", InputMethod.TYPED)
        verify(mockWordFrequencyRepository, times(3)).incrementFrequency("teh", "en")
    }

    @Test
    fun `coordinatePostCommitReplacement does not learn on non-autocorrect replacement`() = runTest(testDispatcher) {
        val replacementState = PostCommitReplacementState(
            originalWord = "hello",
            committedWord = "hello"
        )
        whenever(mockIc.getTextBeforeCursor(6, 0)).thenReturn("hello ")
        whenever(mockTextInputProcessor.getCurrentSettings()).thenReturn(
            com.urik.keyboard.settings.KeyboardSettings()
        )

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "help",
            replacementState = replacementState,
            checkAutoCapitalization = {}
        )

        verify(mockWordLearningEngine, never()).learnWord(any(), any())
    }

    @Test
    fun `coordinatePostCommitReplacement clears postCommitReplacementState`() = runTest(testDispatcher) {
        inputState.postCommitReplacementState = PostCommitReplacementState("teh", "the")
        whenever(mockIc.getTextBeforeCursor(4, 0)).thenReturn("the ")
        whenever(mockTextInputProcessor.getCurrentSettings()).thenReturn(
            com.urik.keyboard.settings.KeyboardSettings()
        )
        whenever(mockSpellCheckManager.isWordInDictionary(any())).thenReturn(false)

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "teh",
            replacementState = inputState.postCommitReplacementState!!,
            checkAutoCapitalization = {}
        )

        assertNull(inputState.postCommitReplacementState)
    }

    @Test
    fun `coordinatePostCommitReplacement aborts on stale text`() = runTest(testDispatcher) {
        val replacementState = PostCommitReplacementState(
            originalWord = "teh",
            committedWord = "the"
        )
        whenever(mockIc.getTextBeforeCursor(4, 0)).thenReturn("oops")

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "teh",
            replacementState = replacementState,
            checkAutoCapitalization = {}
        )

        verify(mockIc, never()).deleteSurroundingText(any(), any())
        verify(mockWordLearningEngine, never()).learnWord(any(), any())
    }

    @Test
    fun `requestSuggestions forwards isDegradedMode to ViewCallback`() = runTest(testDispatcher) {
        whenever(mockSpellCheckManager.isDegradedMode).thenReturn(true)

        pipeline.requestSuggestions(buffer = "test", inputMethod = InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, capturedDegradedIndicator)

        whenever(mockSpellCheckManager.isDegradedMode).thenReturn(false)

        pipeline.requestSuggestions(buffer = "test", inputMethod = InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, capturedDegradedIndicator)
    }

    @Test
    fun `coordinateSuggestionSelection records word usage`() = runTest(testDispatcher) {
        inputState.displayBuffer = "helo"
        inputState.composingRegionStart = 0
        whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("helo")
        whenever(mockIc.commitText(any(), any())).thenReturn(true)
        whenever(mockIc.finishComposingText()).thenReturn(true)

        val extractedText = android.view.inputmethod.ExtractedText().apply {
            startOffset = 0
            selectionStart = 4
        }
        whenever(mockIc.getExtractedText(any(), eq(0))).thenReturn(extractedText)

        pipeline.coordinateSuggestionSelection("hello", checkAutoCapitalization = {})

        verify(mockWordFrequencyRepository).incrementFrequency("hello", "en")
        assertEquals("hello", inputState.lastCommittedWord)
    }

    @Test
    fun `capitalizeSuggestions skips capitalization for Arabic`() {
        val arabicPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = object : SuggestionPipelineHost {
                override fun showSuggestions() = true
                override fun effectiveSuggestionCount() = 3
                override fun getKeyboardState() = KeyboardState()
                override fun shouldAutoCapitalize(text: String) = false
                override fun currentLanguage() = "ar"
            }
        )
        val suggestions = listOf(
            SpellingSuggestion("مرحبا", 0.9, 0, "dictionary", preserveCase = false)
        )

        val result = arabicPipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("مرحبا"), result)
    }

    @Test
    fun `capitalizeSuggestions skips capitalization for Persian`() {
        val faPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = object : SuggestionPipelineHost {
                override fun showSuggestions() = true
                override fun effectiveSuggestionCount() = 3
                override fun getKeyboardState() = KeyboardState()
                override fun shouldAutoCapitalize(text: String) = false
                override fun currentLanguage() = "fa"
            }
        )
        val suggestions = listOf(
            SpellingSuggestion("سلام", 0.9, 0, "dictionary", preserveCase = false)
        )

        val result = faPipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("سلام"), result)
    }

    @Test
    fun `capitalizeSuggestions skips capitalization for Japanese`() {
        val jaPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = object : SuggestionPipelineHost {
                override fun showSuggestions() = true
                override fun effectiveSuggestionCount() = 3
                override fun getKeyboardState() = KeyboardState()
                override fun shouldAutoCapitalize(text: String) = false
                override fun currentLanguage() = "ja"
            }
        )
        val suggestions = listOf(
            SpellingSuggestion("こんにちは", 0.9, 0, "dictionary", preserveCase = false)
        )

        val result = jaPipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("こんにちは"), result)
    }

    @Test
    fun `capitalizeSuggestions capitalizes for English at sentence start`() {
        val suggestions = listOf(
            SpellingSuggestion("hello", 0.9, 0, "dictionary", preserveCase = false)
        )
        whenever(mockCaseTransformer.applyCasingToSuggestions(any(), any(), any(), any()))
            .thenReturn(listOf("Hello"))

        val result = pipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("Hello"), result)
    }

    private inner class FakeJapanesePipelineHost : SuggestionPipelineHost {
        override fun showSuggestions(): Boolean = true
        override fun effectiveSuggestionCount(): Int = 5
        override fun getKeyboardState(): KeyboardState = KeyboardState()
        override fun shouldAutoCapitalize(text: String): Boolean = false
        override fun currentLanguage(): String = "ja"
    }

    @Test
    fun `requestJapaneseSuggestions appends hiragana reading and katakana as last candidates`() =
        runTest(testDispatcher) {
            val japanesePipeline = SuggestionPipeline(
                state = inputState,
                outputBridge = outputBridge,
                textInputProcessor = mockTextInputProcessor,
                spellCheckManager = mockSpellCheckManager,
                wordLearningEngine = mockWordLearningEngine,
                wordFrequencyRepository = mockWordFrequencyRepository,
                languageManager = mockLanguageManager,
                caseTransformer = mockCaseTransformer,
                scriptConverterRegistry = mockScriptConverterRegistry,
                serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                host = FakeJapanesePipelineHost()
            )
            japanesePipeline.setJapaneseLayout(true)

            val mockConverter = mock<ScriptConverter>()
            whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
            whenever(mockConverter.getCandidates("か", "ja")).thenReturn(
                listOf(ConversionCandidate(surface = "化", reading = "か", frequency = 19992, source = "dictionary"))
            )
            whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("か")).thenReturn(emptyList())

            inputState.updateDisplayBuffer("か")
            japanesePipeline.requestSuggestions("か", InputMethod.TYPED)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("化", "か", "カ"), capturedSuggestions.take(3))
        }

    @Test
    fun `requestJapaneseSuggestions katakana slot reflects full buffer`() = runTest(testDispatcher) {
        val japanesePipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeJapanesePipelineHost()
        )
        japanesePipeline.setJapaneseLayout(true)

        val mockConverter = mock<ScriptConverter>()
        whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
        whenever(mockConverter.getCandidates("がっこう", "ja")).thenReturn(
            listOf(ConversionCandidate(surface = "学校", reading = "がっこう", frequency = 50000, source = "dictionary"))
        )
        whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("がっこう")).thenReturn(emptyList())

        inputState.updateDisplayBuffer("がっこう")
        japanesePipeline.requestSuggestions("がっこう", InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("学校", "がっこう", "ガッコウ"), capturedSuggestions.take(3))
    }

    @Test
    fun `requestJapaneseSuggestions deduplicates if hiragana matches a kanji candidate`() = runTest(testDispatcher) {
        val japanesePipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeJapanesePipelineHost()
        )
        japanesePipeline.setJapaneseLayout(true)

        val mockConverter = mock<ScriptConverter>()
        whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
        whenever(mockConverter.getCandidates("か", "ja")).thenReturn(
            listOf(ConversionCandidate(surface = "か", reading = "か", frequency = 1000, source = "dictionary"))
        )
        whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("か")).thenReturn(emptyList())

        inputState.updateDisplayBuffer("か")
        japanesePipeline.requestSuggestions("か", InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        val suggestions = capturedSuggestions
        assertEquals(1, suggestions.count { it == "か" })
        assert(suggestions.contains("カ")) { "katakana カ must be present" }
    }

    @Test
    fun `requestSuggestions isSuggestionsDisabled emits no suggestions`() = runTest(testDispatcher) {
        inputState.isSuggestionsDisabled = true
        pipeline.requestSuggestions("hello", InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockTextInputProcessor, never()).processWordInput(any(), any())
        assertEquals(emptyList<String>(), capturedSuggestions)
    }

    @Test
    fun `coordinateStateTransition isSuggestionsDisabled does not update suggestions`() {
        inputState.isSuggestionsDisabled = true
        val wordState = WordState(
            buffer = "hello",
            suggestions = listOf(SpellingSuggestion("hello", 0.9, 0, "dict", preserveCase = false))
        )

        pipeline.coordinateStateTransition(wordState)

        assertEquals(emptyList<String>(), capturedSuggestions)
    }

    @Test
    fun `showBigramPredictions isSuggestionsDisabled emits no predictions`() = runTest(testDispatcher) {
        inputState.isSuggestionsDisabled = true
        inputState.lastCommittedWord = "hello"
        whenever(mockWordFrequencyRepository.getBigramPredictions(any(), any(), any()))
            .thenReturn(setOf("world"))

        pipeline.showBigramPredictions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<String>(), capturedSuggestions)
    }
}
