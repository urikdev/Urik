package com.urik.keyboard.service

import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceInputHandlerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var handler: SpaceInputHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockAutoCorrectionEngine: AutoCorrectionEngine
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var mockSwipeDetector: SwipeDetector
    private lateinit var mockCandidateBarController: CandidateBarController
    private lateinit var mockLanguageManager: LanguageManager
    private lateinit var closeable: AutoCloseable
    private val coordinateStateClearCalls = mutableListOf<Unit>()
    private val autoCapArgs = mutableListOf<String>()
    private val disableCapsLockCalls = mutableListOf<Unit>()

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        realInputState = InputStateManager(
            viewCallback = mock(ViewCallback::class.java),
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )
        mockOutputBridge = mock(OutputBridge::class.java)
        mockSuggestionPipeline = mock(SuggestionPipeline::class.java)
        mockAutoCorrectionEngine = mock(AutoCorrectionEngine::class.java)
        mockSwipeSpaceManager = mock(SwipeSpaceManager::class.java)
        mockSwipeDetector = mock(SwipeDetector::class.java)
        mockCandidateBarController = mock(CandidateBarController::class.java)
        mockLanguageManager = mock(LanguageManager::class.java)
        handler = SpaceInputHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            autoCorrectionEngine = mockAutoCorrectionEngine,
            swipeSpaceManager = mockSwipeSpaceManager,
            swipeDetector = mockSwipeDetector,
            candidateBarController = mockCandidateBarController,
            languageManager = mockLanguageManager,
            serviceScope = testScope,
            onGetCurrentSettings = { KeyboardSettings() },
            onCheckAutoCapitalization = { autoCapArgs.add(it) }
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handle_constructsWithFullDependencySet() {
        assertEquals(0, coordinateStateClearCalls.size)
        assertEquals(0, autoCapArgs.size)
        assertEquals(0, disableCapsLockCalls.size)
    }

    @Test
    fun handle_directCommitField_sendsSpaceDirectly() {
        realInputState.isDirectCommitField = true
        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()
        org.mockito.Mockito.verify(mockOutputBridge).sendSpace()
    }

    @Test
    fun handle_emptyDisplayBuffer_simpleSpaceBranch_commitsSpace() {
        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()
        org.mockito.Mockito.verify(mockOutputBridge).commitText(" ", 1)
    }

    @Test
    fun `handle isSuggestionsDisabled sends space directly`() {
        realInputState.isSuggestionsDisabled = true
        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()
        org.mockito.Mockito.verify(mockOutputBridge).sendSpace()
    }

    @Test
    fun `handle isSuggestionsDisabled double space does not produce period`() {
        realInputState.isSuggestionsDisabled = true
        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()
        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()
        org.mockito.Mockito.verify(mockOutputBridge, org.mockito.Mockito.times(2)).sendSpace()
        org.mockito.Mockito.verify(mockOutputBridge, org.mockito.Mockito.never()).commitText(". ", 1)
    }

    private suspend fun stubDecisionPath(decision: AutocorrectDecision, buffer: String) {
        realInputState.displayBuffer = buffer
        whenever(mockLanguageManager.currentLanguage).thenReturn(MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(any(), any())).thenReturn(buffer)
        whenever(mockOutputBridge.safeGetTextAfterCursor(any(), any())).thenReturn("")
        whenever(mockSuggestionPipeline.storeAndCapitalizeSuggestions(any(), any()))
            .thenReturn(decisionSuggestionWords(decision))
        whenever(
            mockAutoCorrectionEngine.decide(any(), any(), any(), any(), anyOrNull(), any(), any())
        ).thenReturn(decision)
        whenever(mockSuggestionPipeline.learnWordAndInvalidateCache(any(), any())).thenReturn(true)
    }

    private fun decisionSuggestionWords(decision: AutocorrectDecision): List<String> = when (decision) {
        is AutocorrectDecision.Pause -> decision.suggestions.map { it.word }
        is AutocorrectDecision.ContractionBypass -> decision.suggestions.map { it.word }
        is AutocorrectDecision.Suggestions -> decision.list.map { it.word }
        else -> emptyList()
    }

    @Test
    fun `handle Pause decision reuses precomputed suggestions without recomputing`() = testScope.runTest {
        val suggestion = SpellingSuggestion("hello", 1.0, 0)
        stubDecisionPath(AutocorrectDecision.Pause(listOf(suggestion)), "helo")

        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SpellConfirmationState.AWAITING_CONFIRMATION, realInputState.spellConfirmationState)
        assertEquals(listOf("hello"), realInputState.pendingSuggestions)
        verify(mockSuggestionPipeline).storeAndCapitalizeSuggestions(eq(listOf(suggestion)), any())
        verify(mockCandidateBarController).updateSuggestions(listOf("hello"))
    }

    @Test
    fun `handle ContractionBypass decision reuses precomputed suggestions without recomputing`() = testScope.runTest {
        val suggestion = SpellingSuggestion("don't", 1.0, 0)
        stubDecisionPath(AutocorrectDecision.ContractionBypass(listOf(suggestion)), "dont")

        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("don't"), realInputState.pendingSuggestions)
        assertEquals("dont", realInputState.postCommitReplacementState?.originalWord)
        verify(mockSuggestionPipeline).storeAndCapitalizeSuggestions(eq(listOf(suggestion)), any())
        verify(mockCandidateBarController).updateSuggestions(listOf("don't"))
    }

    @Test
    fun `handle Correct decision applies English pronoun capitalization to corrected word`() = testScope.runTest {
        stubDecisionPath(AutocorrectDecision.Correct("i'm"), "im")

        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("I'm ", 1)
        assertEquals("I'm", realInputState.lastAutocorrection?.correctedWord)
        assertEquals("im", realInputState.lastAutocorrection?.originalTypedWord)
    }

    @Test
    fun `handle Correct decision commits non-pronoun correction unchanged`() = testScope.runTest {
        stubDecisionPath(AutocorrectDecision.Correct("hello"), "helo")

        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("hello ", 1)
        assertEquals("hello", realInputState.lastAutocorrection?.correctedWord)
    }

    @Test
    fun `handle Suggestions decision reuses precomputed list without recomputing`() = testScope.runTest {
        val suggestion = SpellingSuggestion("hello", 1.0, 0)
        stubDecisionPath(AutocorrectDecision.Suggestions(listOf(suggestion)), "helo")

        handler.handle()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("hello"), realInputState.pendingSuggestions)
        assertEquals("helo", realInputState.postCommitReplacementState?.originalWord)
        verify(mockSuggestionPipeline).storeAndCapitalizeSuggestions(eq(listOf(suggestion)), any())
        verify(mockCandidateBarController).updateSuggestions(listOf("hello"))
    }
}
