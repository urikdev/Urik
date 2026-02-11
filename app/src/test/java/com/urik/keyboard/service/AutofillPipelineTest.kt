@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests [AutofillStateTracker] state transitions, buffering, dismiss persistence,
 * and soft transition lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutofillPipelineTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var tracker: AutofillStateTracker

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tracker = AutofillStateTracker()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `bufferResponse stores response and drainPendingResponse returns it`() {
        val response = mock<android.view.inputmethod.InlineSuggestionsResponse>()
        tracker.bufferResponse(response)

        val drained = tracker.drainPendingResponse()
        assertEquals(response, drained)
    }

    @Test
    fun `drainPendingResponse clears buffer after returning`() {
        val response = mock<android.view.inputmethod.InlineSuggestionsResponse>()
        tracker.bufferResponse(response)
        tracker.drainPendingResponse()

        assertNull(tracker.drainPendingResponse())
    }

    @Test
    fun `new bufferResponse replaces existing buffered response`() {
        val first = mock<android.view.inputmethod.InlineSuggestionsResponse>()
        val second = mock<android.view.inputmethod.InlineSuggestionsResponse>()
        tracker.bufferResponse(first)
        tracker.bufferResponse(second)

        assertEquals(second, tracker.drainPendingResponse())
    }

    @Test
    fun `drainPendingResponse returns null when nothing buffered`() {
        assertNull(tracker.drainPendingResponse())
    }

    @Test
    fun `dismiss sets dismissed state`() {
        assertFalse(tracker.isDismissed())
        tracker.dismiss()
        assertTrue(tracker.isDismissed())
    }

    @Test
    fun `dismiss persists on same-field refocus`() {
        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        tracker.dismiss()

        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        assertTrue(tracker.isDismissed())
    }

    @Test
    fun `dismiss resets on different field`() {
        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        tracker.dismiss()

        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 200, packageHash = 999)
        assertFalse(tracker.isDismissed())
    }

    @Test
    fun `onFieldChanged returns true for new field`() {
        val changed = tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        assertTrue(changed)
    }

    @Test
    fun `onFieldChanged returns false for same field`() {
        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        val changed = tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        assertFalse(changed)
    }

    @Test
    fun `different inputType produces different field identity`() {
        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        tracker.dismiss()

        val changed = tracker.onFieldChanged(inputType = 2, imeOptions = 0, fieldId = 100, packageHash = 999)
        assertTrue(changed)
        assertFalse(tracker.isDismissed())
    }

    @Test
    fun `different packageHash produces different field identity`() {
        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)
        tracker.dismiss()

        val changed = tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 888)
        assertTrue(changed)
        assertFalse(tracker.isDismissed())
    }

    @Test
    fun `scheduleClear executes after grace period`() =
        testScope.runTest {
            var cleared = false
            tracker.scheduleClear(this) { cleared = true }

            advanceTimeBy(50)
            assertFalse(cleared)

            advanceTimeBy(60)
            assertTrue(cleared)
        }

    @Test
    fun `cancelPendingClear prevents scheduled clear`() =
        testScope.runTest {
            var cleared = false
            tracker.scheduleClear(this) { cleared = true }

            advanceTimeBy(50)
            tracker.cancelPendingClear()

            advanceTimeBy(200)
            assertFalse(cleared)
        }

    @Test
    fun `rapid field switch cancels previous clear job`() =
        testScope.runTest {
            var clearCount = 0
            tracker.scheduleClear(this) { clearCount++ }

            advanceTimeBy(50)
            tracker.scheduleClear(this) { clearCount++ }

            advanceTimeBy(200)
            assertEquals(1, clearCount)
        }

    @Test
    fun `cleanup resets all state`() {
        val response = mock<android.view.inputmethod.InlineSuggestionsResponse>()
        tracker.bufferResponse(response)
        tracker.dismiss()
        tracker.onFieldChanged(inputType = 1, imeOptions = 0, fieldId = 100, packageHash = 999)

        tracker.cleanup()

        assertNull(tracker.drainPendingResponse())
        assertFalse(tracker.isDismissed())
    }

    @Test
    fun `cleanup cancels pending clear job`() =
        testScope.runTest {
            var cleared = false
            tracker.scheduleClear(this) { cleared = true }

            tracker.cleanup()
            advanceUntilIdle()

            assertFalse(cleared)
        }
}
