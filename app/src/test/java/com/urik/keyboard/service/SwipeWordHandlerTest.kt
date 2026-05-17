package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CaseTransformer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

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
        whenever(mockLanguageManager.currentLanguage).thenReturn(MutableStateFlow("en"))
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
    fun `constructs with full dependency set without firing callbacks`() {
        assertEquals(0, coordinateStateClearCalls.size)
        assertEquals(0, autoCapArgs.size)
        assertEquals(0, disableShiftCalls.size)
    }

    @Test
    fun `direct commit field commits word directly`() {
        realInputState.isDirectCommitField = true
        whenever(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("a")
        whenever(mockSwipeSpaceManager.isWhitespace("a")).thenReturn(false)
        whenever(mockCaseTransformer.applyCasing(any(), any(), any(), any())).thenAnswer { invocation ->
            (invocation.arguments[0] as SpellingSuggestion).word
        }
        whenever(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")
        handler.handle("hello")
        verify(mockOutputBridge).commitText("hello", 1)
    }

    @Test
    fun `empty validated word returns early without side effects`() {
        handler.handle("")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, coordinateStateClearCalls.size)
    }

    // Direct-commit casing and auto-cap

    private fun buildHandlerWithState(
        keyboardState: KeyboardState,
        localAutoCap: MutableList<String>,
        localDisableShift: MutableList<Unit>
    ): SwipeWordHandler {
        whenever(mockCaseTransformer.applyCasing(any(), any(), any(), any())).thenAnswer { invocation ->
            val suggestion = invocation.arguments[0] as SpellingSuggestion
            val state = invocation.arguments[1] as KeyboardState
            val isSentenceStart = invocation.arguments[2] as Boolean
            when {
                state.isCapsLockOn -> suggestion.word.uppercase()
                state.isShiftPressed -> suggestion.word.replaceFirstChar { it.uppercaseChar() }
                isSentenceStart -> suggestion.word.replaceFirstChar { it.uppercaseChar() }
                else -> suggestion.word
            }
        }
        return SwipeWordHandler(
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
            onGetKeyboardState = { keyboardState },
            onCoordinateStateClear = {},
            onCheckAutoCapitalization = { localAutoCap.add(it) },
            onDisableShiftAfterSwipe = { localDisableShift.add(Unit) }
        )
    }

    @Test
    fun `direct commit at sentence start capitalizes word and fires auto-cap`() {
        realInputState.isDirectCommitField = true
        val localAutoCap = mutableListOf<String>()
        val localDisableShift = mutableListOf<Unit>()
        val localHandler = buildHandlerWithState(
            KeyboardState(isShiftPressed = true, isAutoShift = true),
            localAutoCap,
            localDisableShift
        )
        whenever(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("")
        whenever(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("Hello. ")

        localHandler.handle("world")

        verify(mockOutputBridge).commitText("World", 1)
        verify(mockOutputBridge).beginBatchEdit()
        verify(mockOutputBridge).endBatchEdit()
        assertEquals(1, localAutoCap.size)
    }

    @Test
    fun `direct commit uses batch edit to suppress onUpdateSelection during casing sequence`() {
        realInputState.isDirectCommitField = true
        val callOrder = mutableListOf<String>()
        whenever(mockCaseTransformer.applyCasing(any(), any(), any(), any())).thenAnswer { invocation ->
            (invocation.arguments[0] as SpellingSuggestion).word
        }
        whenever(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("")
        whenever(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")
        org.mockito.Mockito.doAnswer {
            callOrder.add("beginBatchEdit")
            null
        }.`when`(mockOutputBridge).beginBatchEdit()
        org.mockito.Mockito.doAnswer {
            callOrder.add("commitText")
            null
        }.`when`(mockOutputBridge).commitText(any(), any())
        org.mockito.Mockito.doAnswer {
            callOrder.add("endBatchEdit")
            null
        }.`when`(mockOutputBridge).endBatchEdit()

        handler.handle("word")

        // endBatchEdit must come after commitText — ensures batch wraps the commit
        val commitIdx = callOrder.indexOf("commitText")
        val endIdx = callOrder.indexOf("endBatchEdit")
        val beginIdx = callOrder.indexOf("beginBatchEdit")
        assertTrue("beginBatchEdit before commitText", beginIdx < commitIdx)
        assertTrue("endBatchEdit after commitText", endIdx > commitIdx)
    }

    @Test
    fun `direct commit mid-sentence commits lowercase and fires auto-cap`() {
        realInputState.isDirectCommitField = true
        val localAutoCap = mutableListOf<String>()
        val localDisableShift = mutableListOf<Unit>()
        val localHandler = buildHandlerWithState(
            KeyboardState(isShiftPressed = false, isAutoShift = false),
            localAutoCap,
            localDisableShift
        )
        whenever(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("o ")
        whenever(mockSwipeSpaceManager.isWhitespace("o ")).thenReturn(true)
        whenever(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("Hello world ")

        localHandler.handle("foo")

        verify(mockOutputBridge).commitText("foo", 1)
        assertEquals(1, localAutoCap.size)
    }

    @Test
    fun `direct commit with manual shift capitalizes word and disables shift`() {
        realInputState.isDirectCommitField = true
        val localAutoCap = mutableListOf<String>()
        val localDisableShift = mutableListOf<Unit>()
        val localHandler = buildHandlerWithState(
            KeyboardState(isShiftPressed = true, isAutoShift = false),
            localAutoCap,
            localDisableShift
        )
        whenever(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("")
        whenever(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")

        localHandler.handle("hello")

        verify(mockOutputBridge).commitText("Hello", 1)
        assertEquals(1, localDisableShift.size)
        assertEquals(1, localAutoCap.size)
    }

    @Test
    fun `direct commit shift is disabled before commitText`() {
        realInputState.isDirectCommitField = true
        val callOrder = mutableListOf<String>()
        whenever(mockCaseTransformer.applyCasing(any(), any(), any(), any())).thenAnswer { invocation ->
            (invocation.arguments[0] as SpellingSuggestion).word
        }
        whenever(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("")
        whenever(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")
        org.mockito.Mockito.doAnswer {
            callOrder.add("commitText")
            null
        }.`when`(mockOutputBridge).commitText(any(), any())

        val localHandler = SwipeWordHandler(
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
            onGetKeyboardState = { KeyboardState(isShiftPressed = true, isAutoShift = false) },
            onCoordinateStateClear = {},
            onCheckAutoCapitalization = {},
            onDisableShiftAfterSwipe = { callOrder.add("disableShift") }
        )

        localHandler.handle("hello")

        val disableIdx = callOrder.indexOf("disableShift")
        val commitIdx = callOrder.indexOf("commitText")
        assertTrue("disableShift must be called before commitText", disableIdx < commitIdx)
    }

    @Test
    fun `direct commit with caps lock uppercases word`() {
        realInputState.isDirectCommitField = true
        val localAutoCap = mutableListOf<String>()
        val localDisableShift = mutableListOf<Unit>()
        val localHandler = buildHandlerWithState(
            KeyboardState(isCapsLockOn = true, isShiftPressed = false),
            localAutoCap,
            localDisableShift
        )
        whenever(mockOutputBridge.safeGetTextBeforeCursor(1)).thenReturn("")
        whenever(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")

        localHandler.handle("hello")

        verify(mockOutputBridge).commitText("HELLO", 1)
        assertEquals(0, localDisableShift.size)
    }

    @Test
    fun `secure field commits word verbatim without auto-cap`() {
        realInputState.isSecureField = true
        val localAutoCap = mutableListOf<String>()
        val localDisableShift = mutableListOf<Unit>()
        val localHandler = buildHandlerWithState(
            KeyboardState(isShiftPressed = true, isAutoShift = true),
            localAutoCap,
            localDisableShift
        )

        localHandler.handle("hello")

        verify(mockOutputBridge).commitText("hello", 1)
        verify(mockOutputBridge, org.mockito.Mockito.never()).beginBatchEdit()
        assertEquals(0, localAutoCap.size)
    }

    @Test
    fun `terminal direct-commit field commits word verbatim without auto-cap`() {
        realInputState.isDirectCommitField = true
        realInputState.isTerminalField = true
        val localAutoCap = mutableListOf<String>()
        val localDisableShift = mutableListOf<Unit>()
        val localHandler = buildHandlerWithState(
            KeyboardState(isShiftPressed = true, isAutoShift = true),
            localAutoCap,
            localDisableShift
        )

        localHandler.handle("hello")

        verify(mockOutputBridge).commitText("hello", 1)
        verify(mockOutputBridge, org.mockito.Mockito.never()).beginBatchEdit()
        assertEquals(0, localAutoCap.size)
    }

    @Test
    fun `raw key event field is a no-op and does not commit anything`() {
        realInputState.isRawKeyEventField = true
        val localAutoCap = mutableListOf<String>()
        val localDisableShift = mutableListOf<Unit>()
        val localHandler = buildHandlerWithState(
            KeyboardState(isShiftPressed = true, isAutoShift = true),
            localAutoCap,
            localDisableShift
        )

        localHandler.handle("hello")

        verify(mockOutputBridge, org.mockito.Mockito.never()).commitText(any(), any())
        verify(mockOutputBridge, org.mockito.Mockito.never()).beginBatchEdit()
        assertEquals(0, localAutoCap.size)
    }
}
