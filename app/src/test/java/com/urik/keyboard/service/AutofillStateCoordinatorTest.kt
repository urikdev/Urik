package com.urik.keyboard.service

import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AutofillStateCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val tracker: AutofillStateTracker = mock()
    private val candidateBarController: CandidateBarController = mock()

    private var displayCallCount = 0
    private val displaySuggestions: (List<InlineSuggestion>) -> Unit = { displayCallCount++ }

    private lateinit var coordinator: AutofillStateCoordinator

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        displayCallCount = 0
        coordinator = AutofillStateCoordinator(
            tracker = tracker,
            candidateBarController = candidateBarController,
            serviceScope = testScope,
            displaySuggestions = displaySuggestions
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onFieldChanged delegates to tracker`() {
        coordinator.onFieldChanged(1, 0, 100, 999)

        verify(tracker).cancelPendingClear()
        verify(tracker).onFieldChanged(1, 0, 100, 999)
    }

    @Test
    fun `onInputViewStarted drains and displays when view is ready and suggestions not dismissed`() {
        val response = mock<InlineSuggestionsResponse>()
        whenever(response.inlineSuggestions).thenReturn(listOf(mock()))
        whenever(tracker.drainPendingResponse()).thenReturn(response)
        whenever(tracker.isDismissed()).thenReturn(false)

        coordinator.onInputViewStarted(isViewReady = true)

        assertTrue(displayCallCount > 0)
    }

    @Test
    fun `onInputViewStarted skips display when dismissed`() {
        val response = mock<InlineSuggestionsResponse>()
        whenever(response.inlineSuggestions).thenReturn(listOf(mock()))
        whenever(tracker.drainPendingResponse()).thenReturn(response)
        whenever(tracker.isDismissed()).thenReturn(true)

        coordinator.onInputViewStarted(isViewReady = true)

        assertTrue(displayCallCount == 0)
    }

    @Test
    fun `onInputViewStarted skips display when view not ready`() {
        coordinator.onInputViewStarted(isViewReady = false)

        verify(tracker, never()).drainPendingResponse()
        assertTrue(displayCallCount == 0)
    }

    @Test
    fun `onInputViewFinished schedules clear on tracker`() {
        coordinator.onInputViewFinished()

        verify(tracker).scheduleClear(any(), any())
    }

    @Test
    fun `onInlineSuggestionsResponse with empty suggestions calls forceClearAllSuggestions and returns false`() {
        val response = mock<InlineSuggestionsResponse>()
        whenever(response.inlineSuggestions).thenReturn(emptyList())

        val result = coordinator.onInlineSuggestionsResponse(response, false)
        testScope.advanceUntilIdle()

        verify(candidateBarController).forceClearAllSuggestions()
        assertFalse(result)
    }

    @Test
    fun `onInlineSuggestionsResponse when dismissed returns true without buffering or displaying`() {
        val response = mock<InlineSuggestionsResponse>()
        whenever(response.inlineSuggestions).thenReturn(listOf(mock()))
        whenever(tracker.isDismissed()).thenReturn(true)

        val result = coordinator.onInlineSuggestionsResponse(response, true)

        assertTrue(result)
        verify(tracker, never()).bufferResponse(any())
        assertTrue(displayCallCount == 0)
    }

    @Test
    fun `onInlineSuggestionsResponse buffers when view not ready`() {
        val response = mock<InlineSuggestionsResponse>()
        whenever(response.inlineSuggestions).thenReturn(listOf(mock()))
        whenever(tracker.isDismissed()).thenReturn(false)

        val result = coordinator.onInlineSuggestionsResponse(response, isViewReady = false)

        assertTrue(result)
        verify(tracker).bufferResponse(response)
    }

    @Test
    fun `onInlineSuggestionsResponse displays when view is ready`() {
        val response = mock<InlineSuggestionsResponse>()
        whenever(response.inlineSuggestions).thenReturn(listOf(mock()))
        whenever(tracker.isDismissed()).thenReturn(false)

        val result = coordinator.onInlineSuggestionsResponse(response, isViewReady = true)

        assertTrue(result)
        assertTrue(displayCallCount > 0)
    }

    @Test
    fun `onKeyInput calls clearAutofillIfShowing and dismisses tracker when showing`() {
        whenever(candidateBarController.clearAutofillIfShowing()).thenReturn(true)

        coordinator.onKeyInput()

        verify(tracker).dismiss()
    }

    @Test
    fun `onKeyInput does not dismiss tracker when autofill not showing`() {
        whenever(candidateBarController.clearAutofillIfShowing()).thenReturn(false)

        coordinator.onKeyInput()

        verify(tracker, never()).dismiss()
    }

    @Test
    fun `cleanup delegates to tracker`() {
        coordinator.cleanup()

        verify(tracker).cleanup()
    }
}
