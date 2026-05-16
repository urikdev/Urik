package com.urik.keyboard.service

import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceInputHandlerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var handler: SpaceInputHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockAutoCorrectionEngine: AutoCorrectionEngine
    private lateinit var mockTextInputProcessor: TextInputProcessor
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
        mockTextInputProcessor = mock(TextInputProcessor::class.java)
        mockSwipeSpaceManager = mock(SwipeSpaceManager::class.java)
        mockSwipeDetector = mock(SwipeDetector::class.java)
        mockCandidateBarController = mock(CandidateBarController::class.java)
        mockLanguageManager = mock(LanguageManager::class.java)
        handler = SpaceInputHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            autoCorrectionEngine = mockAutoCorrectionEngine,
            textInputProcessor = mockTextInputProcessor,
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
}
