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
    private lateinit var mockKanaKanjiConverter: KanaKanjiConverter

    private lateinit var inputState: InputStateManager
    private lateinit var outputBridge: OutputBridge
    private lateinit var pipeline: SuggestionPipeline

    private var capturedSuggestions: List<String> = emptyList()
    private var suggestionsCleared = false

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
        mockKanaKanjiConverter = mock()

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
            kanaKanjiConverter = mockKanaKanjiConverter,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            showSuggestions = { true },
            effectiveSuggestionCount = { 3 },
            getKeyboardState = { KeyboardState() },
            shouldAutoCapitalize = { false },
            currentLanguageProvider = { "en" }
        )
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
        whenever(mockSpellCheckManager.isWordInSymSpellDictionary(any())).thenReturn(false)

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
        whenever(mockSpellCheckManager.isWordInSymSpellDictionary(any())).thenReturn(false)

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
}
