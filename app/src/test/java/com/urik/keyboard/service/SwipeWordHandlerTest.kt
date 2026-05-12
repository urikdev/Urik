package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CaseTransformer
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
class SwipeWordHandlerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var handler: SwipeWordHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var mockWordLearningEngine: WordLearningEngine
    private lateinit var mockLanguageManager: LanguageManager
    private lateinit var mockCaseTransformer: CaseTransformer
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var mockSwipeDetector: SwipeDetector
    private lateinit var closeable: AutoCloseable
    private val coordinateStateClearCalls = mutableListOf<Unit>()
    private val autoCapArgs = mutableListOf<String>()
    private val disableShiftCalls = mutableListOf<Unit>()

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
        mockTextInputProcessor = mock(TextInputProcessor::class.java)
        mockWordLearningEngine = mock(WordLearningEngine::class.java)
        mockLanguageManager = mock(LanguageManager::class.java)
        mockCaseTransformer = mock(CaseTransformer::class.java)
        mockSwipeSpaceManager = mock(SwipeSpaceManager::class.java)
        mockSwipeDetector = mock(SwipeDetector::class.java)
        handler = SwipeWordHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            textInputProcessor = mockTextInputProcessor,
            wordLearningEngine = mockWordLearningEngine,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            swipeSpaceManager = mockSwipeSpaceManager,
            swipeDetector = mockSwipeDetector,
            serviceScope = testScope,
            onGetKeyboardState = { KeyboardState() },
            onCoordinateStateClear = { coordinateStateClearCalls.add(Unit) },
            onCheckAutoCapitalization = { autoCapArgs.add(it) },
            onDisableShiftAfterSwipe = { disableShiftCalls.add(Unit) }
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
        assertEquals(0, disableShiftCalls.size)
    }

    @Test
    fun handle_directCommitField_commitsWordDirectly() {
        realInputState.isDirectCommitField = true
        org.mockito.Mockito.`when`(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("a")
        org.mockito.Mockito.`when`(mockSwipeSpaceManager.isWhitespace("a")).thenReturn(false)
        handler.handle("hello")
        org.mockito.Mockito.verify(mockOutputBridge).commitText("hello", 1)
    }

    @Test
    fun handle_emptyValidatedWord_returnsEarly() {
        handler.handle("")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, coordinateStateClearCalls.size)
    }
}
