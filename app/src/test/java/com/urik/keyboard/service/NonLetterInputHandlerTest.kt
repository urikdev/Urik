package com.urik.keyboard.service

import com.urik.keyboard.settings.KeyboardSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class NonLetterInputHandlerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var handler: NonLetterInputHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockAutoCorrectionEngine: AutoCorrectionEngine
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var mockLanguageManager: LanguageManager
    private lateinit var mockCandidateBarController: CandidateBarController
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var closeable: AutoCloseable
    private val getCurrentSettingsCalls = mutableListOf<Unit>()
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
        mockLanguageManager = mock(LanguageManager::class.java)
        mockCandidateBarController = mock(CandidateBarController::class.java)
        mockTextInputProcessor = mock(TextInputProcessor::class.java)
        whenever(mockOutputBridge.safeGetTextBeforeCursor(any(), any())).thenReturn("")
        whenever(mockOutputBridge.safeGetTextAfterCursor(any(), any())).thenReturn("")
        handler = NonLetterInputHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            autoCorrectionEngine = mockAutoCorrectionEngine,
            textInputProcessor = mockTextInputProcessor,
            swipeSpaceManager = mockSwipeSpaceManager,
            languageManager = mockLanguageManager,
            candidateBarController = mockCandidateBarController,
            serviceScope = testScope,
            onGetCurrentSettings = {
                getCurrentSettingsCalls.add(Unit)
                KeyboardSettings()
            },
            onCoordinateStateClear = { coordinateStateClearCalls.add(Unit) },
            onCheckAutoCapitalization = { autoCapArgs.add(it) },
            onDisableCapsLockAfterPunctuation = { disableCapsLockCalls.add(Unit) }
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handle_constructsWithFullDependencySet() {
        handler.handle(".")
    }

    @Test
    fun handle_directCommitField_sendsCharacterDirectly() {
        realInputState.isDirectCommitField = true
        handler.handle(".")
        verify(mockOutputBridge).sendCharacter(".")
    }

    @Test
    fun handle_invokesOnGetCurrentSettings_whenDisplayBufferNonEmpty() {
        realInputState.displayBuffer = "hello"
        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()
        assert(getCurrentSettingsCalls.isNotEmpty())
    }

    @Test
    fun `handle isSuggestionsDisabled sends character directly without suggestions`() {
        realInputState.isSuggestionsDisabled = true
        handler.handle(".")
        verify(mockOutputBridge).sendCharacter(".")
        verify(mockSuggestionPipeline, org.mockito.Mockito.never()).showBigramPredictions()
    }
}
