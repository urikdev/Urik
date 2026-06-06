package com.urik.keyboard.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any

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
    fun handle_doesNotInvokeAutoCapLambda_whenTerminalField() {
        realInputState.isTerminalField = true
        `when`(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")
        `when`(mockOutputBridge.safeGetTextAfterCursor(50)).thenReturn("")

        handler.handle(0, 0, -1, -1)

        assertEquals(0, autoCapArgs.size)
    }

    @Test
    fun handle_invokesAutoCapLambda_whenCursorAtZero_andNotTerminalField_andNotUrlField() {
        realInputState.isTerminalField = false
        realInputState.isUrlOrEmailField = false
        `when`(mockOutputBridge.safeGetTextBeforeCursor(50)).thenReturn("")
        `when`(mockOutputBridge.safeGetTextAfterCursor(50)).thenReturn("")

        handler.handle(0, 0, -1, -1)

        assertEquals(1, autoCapArgs.size)
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

    // Mid-word cursor tap

    @Test
    fun handle_tracksCursorAtMidWord_whenRequiresInvalidation_butCursorInComposingRegion() {
        realInputState.displayBuffer = "hello"
        realInputState.composingRegionStart = 10
        realInputState.selectionStateTracker.updateSelection(15, 15, -1, -1)

        handler.handle(newSelStart = 12, newSelEnd = 12, candidatesStart = 10, candidatesEnd = 15)

        assertEquals(12, realInputState.lastKnownCursorPosition)
        verify(mockImeStateCoordinator, never()).invalidateComposingStateOnCursorJump()
    }

    @Test
    fun handle_tracksCursorAtMidWord_whenAndroidReportsMinusOneCandidates_butCursorInTrackedComposingRegion() {
        realInputState.displayBuffer = "hello"
        realInputState.composingRegionStart = 10
        realInputState.selectionStateTracker.updateSelection(15, 15, 10, 15)

        handler.handle(newSelStart = 12, newSelEnd = 12, candidatesStart = -1, candidatesEnd = -1)

        assertEquals(12, realInputState.lastKnownCursorPosition)
        verify(mockImeStateCoordinator, never()).invalidateComposingStateOnCursorJump()
        verify(mockOutputBridge, never()).reassertComposingRegion(any())
    }

    @Test
    fun handle_doesNotCallReassertComposingRegion_whenCursorInComposingRegion_andRequiresInvalidation() {
        realInputState.displayBuffer = "hello"
        realInputState.composingRegionStart = 10
        realInputState.selectionStateTracker.updateSelection(15, 15, -1, -1)

        handler.handle(newSelStart = 12, newSelEnd = 12, candidatesStart = 10, candidatesEnd = 15)

        verify(mockOutputBridge, never()).reassertComposingRegion(any())
    }

    @Test
    fun handle_callsReassertComposingRegion_whenCursorOutsideComposingRegion_andRequiresInvalidation() {
        realInputState.displayBuffer = "hello"
        realInputState.composingRegionStart = 10
        realInputState.selectionStateTracker.updateSelection(12, 12, 10, 15)
        `when`(mockOutputBridge.reassertComposingRegion(5)).thenReturn(true)

        handler.handle(newSelStart = 5, newSelEnd = 5, candidatesStart = -1, candidatesEnd = -1)

        verify(mockOutputBridge).reassertComposingRegion(5)
    }

    @Test
    fun handle_tracksCursor_whenTapAtEndOfComposingWord_andCursorInComposingRegion() {
        realInputState.displayBuffer = "hello"
        realInputState.composingRegionStart = 10
        realInputState.selectionStateTracker.updateSelection(15, 15, -1, -1)

        handler.handle(newSelStart = 15, newSelEnd = 15, candidatesStart = 10, candidatesEnd = 15)

        assertEquals(15, realInputState.lastKnownCursorPosition)
        verify(mockImeStateCoordinator, never()).invalidateComposingStateOnCursorJump()
    }

    @Test
    fun handle_invalidatesState_whenCursorOutsideComposingRegion_afterSequentialMove() {
        realInputState.displayBuffer = "hello"
        realInputState.composingRegionStart = 10
        realInputState.selectionStateTracker.updateSelection(15, 15, -1, -1)
        `when`(mockOutputBridge.reassertComposingRegion(3)).thenReturn(false)

        handler.handle(newSelStart = 3, newSelEnd = 3, candidatesStart = -1, candidatesEnd = -1)

        verify(mockImeStateCoordinator).invalidateComposingStateOnCursorJump()
    }

    @Test
    fun handle_tracksCursorAtComposingStart_whenAndroidReportsMinusOneCandidates_andCursorAtBoundary() {
        realInputState.displayBuffer = "hello"
        realInputState.composingRegionStart = 10
        realInputState.selectionStateTracker.updateSelection(15, 15, 10, 15)

        handler.handle(newSelStart = 10, newSelEnd = 10, candidatesStart = -1, candidatesEnd = -1)

        assertEquals(10, realInputState.lastKnownCursorPosition)
        verify(mockImeStateCoordinator, never()).invalidateComposingStateOnCursorJump()
    }
}
