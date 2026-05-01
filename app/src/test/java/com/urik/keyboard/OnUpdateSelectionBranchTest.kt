package com.urik.keyboard

import com.urik.keyboard.service.InputStateManager
import com.urik.keyboard.service.OutputBridge
import com.urik.keyboard.service.TestInputMethodService
import com.urik.keyboard.service.ViewCallback
import com.urik.keyboard.utils.SelectionChangeResult
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnUpdateSelectionBranchTest {
    private lateinit var service: TestInputMethodService
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        service = TestInputMethodService()

        val mockViewCallback = mock(ViewCallback::class.java)
        val realInputState = InputStateManager(
            viewCallback = mockViewCallback,
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )

        mockOutputBridge = mock(OutputBridge::class.java)

        service.inputState = realInputState
        service.outputBridge = mockOutputBridge
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handleDirectCommitInProgress_returnsTrue_whenRequiresDirectCommit() {
        service.inputState.isSecureField = true
        assertTrue(service.handleDirectCommitInProgress())
    }

    @Test
    fun handleDirectCommitInProgress_returnsFalse_whenNotRequired() {
        service.inputState.isSecureField = false
        service.inputState.isDirectCommitField = false
        assertFalse(service.handleDirectCommitInProgress())
    }

    @Test
    fun handleAppSelectionExtended_returnsFalse_whenNotAppSelectionExtended() {
        val result = SelectionChangeResult.Sequential
        assertFalse(service.handleAppSelectionExtended(result, newSelStart = 5))
    }

    @Test
    fun handleAppSelectionExtended_returnsTrue_andClearsState_whenAppSelectionExtended() {
        val result = SelectionChangeResult.AppSelectionExtended(anchorPosition = 3, selectionEnd = 8)
        assertTrue(service.handleAppSelectionExtended(result, newSelStart = 3))
        assertFalse(service.inputState.isActivelyEditing)
    }

    @Test
    fun handleUrlOrEmailField_returnsTrue_whenUrlOrEmailField() {
        service.inputState.isUrlOrEmailField = true
        assertTrue(service.handleUrlOrEmailField())
    }

    @Test
    fun handleUrlOrEmailField_returnsFalse_whenNotUrlOrEmailField() {
        service.inputState.isUrlOrEmailField = false
        assertFalse(service.handleUrlOrEmailField())
    }

    @Test
    fun handleNonSequentialJump_returnsFalse_whenNotNonSequentialJump() {
        val result = SelectionChangeResult.Sequential
        assertFalse(service.handleNonSequentialJump(result, newSelStart = 5, newSelEnd = 5))
    }

    @Test
    fun handleNonSequentialJump_returnsTrue_andCallsAttemptRecomposition() {
        val result = SelectionChangeResult.NonSequentialJump(previousPosition = 0, newPosition = 5, distance = 5)
        val newSelStart = 5
        val newSelEnd = 5

        val returned = service.handleNonSequentialJump(result, newSelStart, newSelEnd)

        assertTrue(returned)
        verify(mockOutputBridge).attemptRecompositionAtCursor(newSelStart)
    }

    @Test
    fun handleNonSequentialJump_returnsTrue_noRecomposition_whenSelectionSpan() {
        val result = SelectionChangeResult.NonSequentialJump(previousPosition = 0, newPosition = 5, distance = 5)
        val newSelStart = 3
        val newSelEnd = 8

        val returned = service.handleNonSequentialJump(result, newSelStart, newSelEnd)

        assertTrue(returned)
        verify(mockOutputBridge, never()).attemptRecompositionAtCursor(newSelStart)
    }

    @Test
    fun handleTypingOusConsumed_returnsTrue_whenConsumed() {
        assertTrue(service.handleTypingOusConsumed(ousConsumed = true, newSelStart = 5))
    }

    @Test
    fun handleTypingOusConsumed_returnsFalse_whenNotConsumed() {
        assertFalse(service.handleTypingOusConsumed(ousConsumed = false, newSelStart = 5))
    }

    @Test
    fun handleActivelyEditing_returnsTrue_andClearsFlag_whenActivelyEditing() {
        service.inputState.isActivelyEditing = true
        assertTrue(service.handleActivelyEditing(newSelStart = 5))
        assertFalse(service.inputState.isActivelyEditing)
    }

    @Test
    fun handleActivelyEditing_returnsFalse_whenNotActivelyEditing() {
        service.inputState.isActivelyEditing = false
        assertFalse(service.handleActivelyEditing(newSelStart = 5))
    }
}
