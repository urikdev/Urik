package com.urik.keyboard.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class LetterInputHandlerTest {
    private lateinit var handler: LetterInputHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var closeable: AutoCloseable
    private val coordinateStateClearCalls = mutableListOf<Unit>()
    private val autoCapArgs = mutableListOf<String>()

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
        mockSwipeSpaceManager = mock(SwipeSpaceManager::class.java)
        handler = LetterInputHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            swipeSpaceManager = mockSwipeSpaceManager,
            onCoordinateStateClear = { coordinateStateClearCalls.add(Unit) },
            onCheckAutoCapitalization = { autoCapArgs.add(it) }
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handle_constructsWithRealInputStateAndMockedDependencies() {
        assertEquals(0, coordinateStateClearCalls.size)
        assertEquals(0, autoCapArgs.size)
    }

    @Test
    fun handle_directCommitField_sendsCharacterDirectly() {
        realInputState.isDirectCommitField = true
        handler.handle("a")
        verify(mockOutputBridge).sendCharacter("a")
    }

    @Test
    fun handle_normalInput_updatesDisplayBuffer() {
        handler.handle("a")
        assertEquals("a", realInputState.displayBuffer)
    }
}
