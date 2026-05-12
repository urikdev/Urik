package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class BackspaceHandlerTest {
    private lateinit var handler: BackspaceHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockCandidateBarController: CandidateBarController
    private lateinit var mockLayoutManager: KeyboardLayoutManager
    private lateinit var closeable: AutoCloseable
    private val coordinateStateClearCalls = mutableListOf<Unit>()
    private val invalidateCalls = mutableListOf<Unit>()
    private val disableShiftCalls = mutableListOf<Unit>()
    private val sendDownUpKeyCodes = mutableListOf<Int>()

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
        mockCandidateBarController = mock(CandidateBarController::class.java)
        mockLayoutManager = mock(KeyboardLayoutManager::class.java)
        handler = BackspaceHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            candidateBarController = mockCandidateBarController,
            layoutManager = mockLayoutManager,
            onCoordinateStateClear = { coordinateStateClearCalls.add(Unit) },
            onInvalidateComposingState = { invalidateCalls.add(Unit) },
            onDisableShiftAfterBackspace = { disableShiftCalls.add(Unit) },
            onGetKeyboardState = { KeyboardState() },
            onSendDownUpKeyEvents = { keyCode -> sendDownUpKeyCodes.add(keyCode) }
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handle_constructsWithRealInputStateAndMockedDependencies() {
        assertEquals(0, coordinateStateClearCalls.size)
        assertEquals(0, invalidateCalls.size)
        assertEquals(0, disableShiftCalls.size)
    }

    @Test
    fun handle_terminalField_sendsBackspaceDirectly() {
        realInputState.isTerminalField = true
        handler.handle()
        verify(mockOutputBridge).sendBackspace()
    }

    @Test
    fun handle_committedTextBranch_emptyDisplayBuffer_invokesOutputBridge() {
        realInputState.composingRegionStart = -1
        handler.handle()
    }

    @Test
    fun handle_selectedText_clearsSelectionAndCoordinatesState() {
        org.mockito.Mockito.`when`(mockOutputBridge.getSelectedText(0)).thenReturn("selected")
        org.mockito.Mockito.`when`(mockOutputBridge.safeGetCursorPosition()).thenReturn(5)
        handler.handle()
        verify(mockOutputBridge).commitText("", 1)
        assertEquals(1, coordinateStateClearCalls.size)
    }
}
