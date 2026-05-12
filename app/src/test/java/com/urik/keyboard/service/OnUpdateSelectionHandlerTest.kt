package com.urik.keyboard.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

class OnUpdateSelectionHandlerTest {
    private lateinit var handler: OnUpdateSelectionHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockImeStateCoordinator: ImeStateCoordinator
    private lateinit var closeable: AutoCloseable
    private var autoCapArgs = mutableListOf<String>()

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
        mockImeStateCoordinator = mock(ImeStateCoordinator::class.java)
        handler = OnUpdateSelectionHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            imeStateCoordinator = mockImeStateCoordinator,
            onCheckAutoCapitalization = { autoCapArgs.add(it) }
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handle_shortCircuits_whenDirectCommitInProgress() {
        realInputState.isSecureField = true
        handler.handle(5, 5, -1, -1)
        verifyNoInteractions(mockOutputBridge)
    }

    @Test
    fun handle_invokesAutoCapLambda_whenCursorAtZero_andNotUrlField() {
        realInputState.isUrlOrEmailField = false
        `when`(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")
        `when`(mockOutputBridge.safeGetTextAfterCursor(50)).thenReturn("")
        handler.handle(0, 0, -1, -1)
        assertEquals(1, autoCapArgs.size)
        assertEquals("", autoCapArgs[0])
    }

    @Test
    fun handle_doesNotInvokeAutoCapLambda_whenUrlOrEmailField() {
        realInputState.isUrlOrEmailField = true
        `when`(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")
        `when`(mockOutputBridge.safeGetTextAfterCursor(50)).thenReturn("")
        handler.handle(0, 0, -1, -1)
        assertEquals(0, autoCapArgs.size)
    }

    @Test
    fun handle_clearsIsActivelyEditing_whenSet() {
        realInputState.isActivelyEditing = true
        handler.handle(5, 5, -1, -1)
        assertFalse(realInputState.isActivelyEditing)
    }

    @Test
    fun handle_callsAttemptRecompositionAtCursor_whenNoComposingText_andNotActivelyEditing_andCollapsedCursor() {
        realInputState.isActivelyEditing = false
        handler.handle(5, 5, -1, -1)
        verify(mockOutputBridge).attemptRecompositionAtCursor(5)
    }

    @Test
    fun handle_invokesInvalidateOnJump_whenNonSequentialJump_andDisplayBufferNonEmpty() {
        realInputState.displayBuffer = "hello"
        realInputState.selectionStateTracker.updateSelection(0, 0, -1, -1)
        handler.handle(10, 10, -1, -1)
        verify(mockImeStateCoordinator).invalidateComposingStateOnCursorJump()
    }
}
