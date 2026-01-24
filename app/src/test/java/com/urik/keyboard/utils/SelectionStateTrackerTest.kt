package com.urik.keyboard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectionStateTrackerTest {
    private lateinit var tracker: SelectionStateTracker

    @Before
    fun setup() {
        tracker = SelectionStateTracker()
    }

    @Test
    fun `initial update returns Initial result`() {
        val result =
            tracker.updateSelection(
                newSelStart = 10,
                newSelEnd = 10,
                candidatesStart = -1,
                candidatesEnd = -1,
            )

        assertEquals(SelectionChangeResult.Initial, result)
    }

    @Test
    fun `sequential cursor movement returns Sequential result`() {
        tracker.updateSelection(10, 10, -1, -1)

        val result =
            tracker.updateSelection(
                newSelStart = 11,
                newSelEnd = 11,
                candidatesStart = -1,
                candidatesEnd = -1,
            )

        assertEquals(SelectionChangeResult.Sequential, result)
    }

    @Test
    fun `large cursor jump returns NonSequentialJump result`() {
        tracker.updateSelection(10, 10, -1, -1)

        val result =
            tracker.updateSelection(
                newSelStart = 100,
                newSelEnd = 100,
                candidatesStart = -1,
                candidatesEnd = -1,
            )

        assertTrue(result is SelectionChangeResult.NonSequentialJump)
        val jump = result as SelectionChangeResult.NonSequentialJump
        assertEquals(10, jump.previousPosition)
        assertEquals(100, jump.newPosition)
        assertEquals(90, jump.distance)
    }

    @Test
    fun `cursor leaving composing region returns CursorLeftComposingRegion`() {
        tracker.updateSelection(15, 15, 10, 20)

        val result =
            tracker.updateSelection(
                newSelStart = 5,
                newSelEnd = 5,
                candidatesStart = 10,
                candidatesEnd = 20,
            )

        assertTrue(result is SelectionChangeResult.CursorLeftComposingRegion)
    }

    @Test
    fun `composing region lost returns ComposingRegionLost`() {
        tracker.updateSelection(15, 15, 10, 20)

        val result =
            tracker.updateSelection(
                newSelStart = 15,
                newSelEnd = 15,
                candidatesStart = -1,
                candidatesEnd = -1,
            )

        assertEquals(SelectionChangeResult.ComposingRegionLost, result)
    }

    @Test
    fun `selection change returns SelectionChanged`() {
        tracker.updateSelection(10, 10, -1, -1)

        val result =
            tracker.updateSelection(
                newSelStart = 10,
                newSelEnd = 20,
                candidatesStart = -1,
                candidatesEnd = -1,
            )

        assertEquals(SelectionChangeResult.SelectionChanged, result)
    }

    @Test
    fun `validateOperationPosition returns true for matching position`() {
        tracker.updateSelection(50, 50, -1, -1)

        assertTrue(tracker.validateOperationPosition(50, 50))
    }

    @Test
    fun `validateOperationPosition returns true within tolerance`() {
        tracker.updateSelection(50, 50, -1, -1)

        assertTrue(tracker.validateOperationPosition(49, 49, tolerance = 1))
        assertTrue(tracker.validateOperationPosition(51, 51, tolerance = 1))
    }

    @Test
    fun `validateOperationPosition returns false for mismatched position`() {
        tracker.updateSelection(50, 50, -1, -1)

        assertFalse(tracker.validateOperationPosition(100, 100))
    }

    @Test
    fun `validateComposingRegionIntegrity returns true for matching region`() {
        tracker.updateSelection(15, 15, 10, 20)

        assertTrue(tracker.validateComposingRegionIntegrity(10, 20))
    }

    @Test
    fun `validateComposingRegionIntegrity returns false for mismatched region`() {
        tracker.updateSelection(15, 15, 10, 20)

        assertFalse(tracker.validateComposingRegionIntegrity(5, 15))
    }

    @Test
    fun `reset clears all state`() {
        tracker.updateSelection(50, 50, 40, 60)

        tracker.reset()

        val state = tracker.getCurrentState()
        assertEquals(SelectionState.INVALID, state)
    }

    @Test
    fun `expected position prevents false positive jump detection`() {
        tracker.updateSelection(10, 10, 5, 15)

        tracker.setExpectedPositionAfterOperation(50)

        val result =
            tracker.updateSelection(
                newSelStart = 50,
                newSelEnd = 50,
                candidatesStart = -1,
                candidatesEnd = -1,
            )

        assertEquals(SelectionChangeResult.ComposingRegionLost, result)
    }

    @Test
    fun `isCursorAtParagraphBoundary returns true after newline`() {
        assertTrue(tracker.isCursorAtParagraphBoundary("Hello\n"))
        assertTrue(tracker.isCursorAtParagraphBoundary(""))
    }

    @Test
    fun `isCursorAtParagraphBoundary returns false mid-paragraph`() {
        assertFalse(tracker.isCursorAtParagraphBoundary("Hello"))
        assertFalse(tracker.isCursorAtParagraphBoundary("Hello world"))
    }

    @Test
    fun `findParagraphBoundaryBefore returns correct position`() {
        val text = "First paragraph\nSecond paragraph"
        val boundary = tracker.findParagraphBoundaryBefore(text)

        assertEquals(16, boundary)
    }

    @Test
    fun `findParagraphBoundaryBefore returns 0 for no newline`() {
        val text = "No newlines here"
        val boundary = tracker.findParagraphBoundaryBefore(text)

        assertEquals(0, boundary)
    }

    @Test
    fun `findParagraphBoundaryBefore handles empty string`() {
        assertEquals(0, tracker.findParagraphBoundaryBefore(""))
    }

    @Test
    fun `getCurrentSequence increments on each update`() {
        val initial = tracker.getCurrentSequence()

        tracker.updateSelection(10, 10, -1, -1)
        val afterFirst = tracker.getCurrentSequence()

        tracker.updateSelection(11, 11, -1, -1)
        val afterSecond = tracker.getCurrentSequence()

        assertEquals(initial + 1, afterFirst)
        assertEquals(initial + 2, afterSecond)
    }

    @Test
    fun `requiresStateInvalidation returns true for destructive results`() {
        assertTrue(SelectionChangeResult.NonSequentialJump(0, 100, 100).requiresStateInvalidation())
        assertTrue(SelectionChangeResult.ComposingRegionLost.requiresStateInvalidation())
        assertTrue(SelectionChangeResult.CursorLeftComposingRegion(0, 10).requiresStateInvalidation())
    }

    @Test
    fun `requiresStateInvalidation returns false for non-destructive results`() {
        assertFalse(SelectionChangeResult.Initial.requiresStateInvalidation())
        assertFalse(SelectionChangeResult.Sequential.requiresStateInvalidation())
        assertFalse(SelectionChangeResult.SelectionChanged.requiresStateInvalidation())
    }

    @Test
    fun `movement within composing region is not detected as jump`() {
        tracker.updateSelection(12, 12, 10, 20)

        val result =
            tracker.updateSelection(
                newSelStart = 18,
                newSelEnd = 18,
                candidatesStart = 10,
                candidatesEnd = 20,
            )

        assertEquals(SelectionChangeResult.Sequential, result)
    }

    @Test
    fun `getLastKnownValidPosition returns last valid cursor`() {
        tracker.updateSelection(25, 25, -1, -1)
        tracker.updateSelection(30, 30, -1, -1)

        assertEquals(30, tracker.getLastKnownValidPosition())
    }
}
