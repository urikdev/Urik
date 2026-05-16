package com.urik.keyboard

import com.urik.keyboard.service.ImeStateCoordinator
import com.urik.keyboard.service.InputStateManager
import com.urik.keyboard.service.OnUpdateSelectionHandler
import com.urik.keyboard.service.OutputBridge
import com.urik.keyboard.service.ViewCallback
import com.urik.keyboard.utils.SelectionChangeResult
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class OnUpdateSelectionBranchTest {
    private lateinit var handler: OnUpdateSelectionHandler
    private lateinit var inputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockImeStateCoordinator: ImeStateCoordinator
    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)

        val mockViewCallback = mock(ViewCallback::class.java)
        inputState = InputStateManager(
            viewCallback = mockViewCallback,
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )

        mockOutputBridge = mock(OutputBridge::class.java)
        mockImeStateCoordinator = mock(ImeStateCoordinator::class.java)

        handler = OnUpdateSelectionHandler(
            inputState = inputState,
            outputBridge = mockOutputBridge,
            imeStateCoordinator = mockImeStateCoordinator,
            onCheckAutoCapitalization = {}
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handleDirectCommitInProgress_returnsTrue_whenRequiresDirectCommit() {
        inputState.isSecureField = true
        assertTrue(handler.handleDirectCommitInProgress())
    }

    @Test
    fun handleDirectCommitInProgress_returnsFalse_whenNotRequired() {
        inputState.isSecureField = false
        inputState.isDirectCommitField = false
        assertFalse(handler.handleDirectCommitInProgress())
    }

    @Test
    fun handleAppSelectionExtended_returnsFalse_whenNotAppSelectionExtended() {
        val result = SelectionChangeResult.Sequential
        assertFalse(handler.handleAppSelectionExtended(result, newSelStart = 5))
    }

    @Test
    fun handleAppSelectionExtended_returnsTrue_andClearsState_whenAppSelectionExtended() {
        val result = SelectionChangeResult.AppSelectionExtended(anchorPosition = 3, selectionEnd = 8)
        assertTrue(handler.handleAppSelectionExtended(result, newSelStart = 3))
        assertFalse(inputState.isActivelyEditing)
    }

    @Test
    fun handleUrlOrEmailField_returnsTrue_whenUrlOrEmailField() {
        inputState.isUrlOrEmailField = true
        assertTrue(handler.handleUrlOrEmailField())
    }

    @Test
    fun handleUrlOrEmailField_returnsFalse_whenNotUrlOrEmailField() {
        inputState.isUrlOrEmailField = false
        assertFalse(handler.handleUrlOrEmailField())
    }

    @Test
    fun handleNonSequentialJump_returnsFalse_whenNotNonSequentialJump() {
        val result = SelectionChangeResult.Sequential
        assertFalse(handler.handleNonSequentialJump(result, newSelStart = 5, newSelEnd = 5))
    }

    @Test
    fun handleNonSequentialJump_returnsTrue_andCallsAttemptRecomposition() {
        val result = SelectionChangeResult.NonSequentialJump(previousPosition = 0, newPosition = 5, distance = 5)
        val newSelStart = 5
        val newSelEnd = 5

        val returned = handler.handleNonSequentialJump(result, newSelStart, newSelEnd)

        assertTrue(returned)
        verify(mockOutputBridge).attemptRecompositionAtCursor(newSelStart)
    }

    @Test
    fun handleNonSequentialJump_returnsTrue_noRecomposition_whenSelectionSpan() {
        val result = SelectionChangeResult.NonSequentialJump(previousPosition = 0, newPosition = 5, distance = 5)
        val newSelStart = 3
        val newSelEnd = 8

        val returned = handler.handleNonSequentialJump(result, newSelStart, newSelEnd)

        assertTrue(returned)
        verify(mockOutputBridge, never()).attemptRecompositionAtCursor(newSelStart)
    }

    @Test
    fun handleTypingOusConsumed_returnsTrue_whenConsumed() {
        assertTrue(handler.handleTypingOusConsumed(ousConsumed = true, newSelStart = 5))
    }

    @Test
    fun handleTypingOusConsumed_returnsFalse_whenNotConsumed() {
        assertFalse(handler.handleTypingOusConsumed(ousConsumed = false, newSelStart = 5))
    }

    @Test
    fun handleActivelyEditing_returnsTrue_andClearsFlag_whenActivelyEditing() {
        inputState.isActivelyEditing = true
        assertTrue(handler.handleActivelyEditing(newSelStart = 5))
        assertFalse(inputState.isActivelyEditing)
    }

    @Test
    fun handleActivelyEditing_returnsFalse_whenNotActivelyEditing() {
        inputState.isActivelyEditing = false
        assertFalse(handler.handleActivelyEditing(newSelStart = 5))
    }
}
