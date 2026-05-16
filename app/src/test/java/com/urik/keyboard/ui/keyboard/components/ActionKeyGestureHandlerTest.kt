package com.urik.keyboard.ui.keyboard.components

import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.settings.CursorSpeed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionKeyGestureHandlerTest {
    private val cursorMoves = mutableListOf<Int>()
    private var backspaceSwipeFired = false
    private lateinit var handler: ActionKeyGestureHandler

    @Before
    fun setup() {
        cursorMoves.clear()
        backspaceSwipeFired = false
        handler = ActionKeyGestureHandler(
            onSpacebarCursorMove = { cursorMoves.add(it) },
            onBackspaceSwipeDelete = { backspaceSwipeFired = true },
            getAdaptiveDimensions = { null },
            getDensity = { 3f },
            getCurrentCursorSpeed = { CursorSpeed.MEDIUM }
        )
    }

    @Test
    fun `isActive is false before any interaction`() {
        assertFalse(handler.isActive)
    }

    @Test
    fun `handleDown with spacebar key does not immediately activate gesture`() {
        handler.handleDown(KeyboardKey.Action(KeyboardKey.ActionType.SPACE), 0f, 0f)
        assertFalse(handler.isActive)
    }

    @Test
    fun `handleMove right on spacebar beyond threshold activates gesture and moves cursor positive`() {
        handler.handleDown(KeyboardKey.Action(KeyboardKey.ActionType.SPACE), 0f, 0f)
        // threshold = 20dp * 3f density = 60px; move 100px to trigger
        handler.handleMove(100f, 0f)
        assertTrue("gesture should be active after threshold crossed", handler.isActive)
        assertTrue("cursor should move positive for rightward drag", cursorMoves.isNotEmpty())
        assertTrue("cursor position delta should be positive", cursorMoves.last() > 0)
    }

    @Test
    fun `handleMove left on spacebar beyond threshold moves cursor negative`() {
        handler.handleDown(KeyboardKey.Action(KeyboardKey.ActionType.SPACE), 200f, 0f)
        handler.handleMove(100f, 0f) // move 100px left — threshold 60px
        assertTrue("cursor moves should be non-empty", cursorMoves.isNotEmpty())
        assertTrue("cursor position delta should be negative for leftward drag", cursorMoves.last() < 0)
    }

    @Test
    fun `handleUp left swipe on backspace fires onBackspaceSwipeDelete`() {
        // minDistance = 30f * 3f density = 90px; dx = -150px qualifies
        handler.handleDown(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE), 200f, 50f)
        handler.handleUp(50f, 50f)
        assertTrue("backspace swipe delete should fire on sufficient left swipe", backspaceSwipeFired)
    }

    @Test
    fun `handleUp short left swipe on backspace does not fire onBackspaceSwipeDelete`() {
        // dx = -20px, minDistance = 90px — too short
        handler.handleDown(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE), 100f, 50f)
        handler.handleUp(80f, 50f)
        assertFalse("short swipe should not trigger backspace swipe delete", backspaceSwipeFired)
    }

    @Test
    fun `cancel resets isActive to false`() {
        handler.handleDown(KeyboardKey.Action(KeyboardKey.ActionType.SPACE), 0f, 0f)
        handler.handleMove(100f, 0f)
        assertTrue(handler.isActive)
        handler.cancel()
        assertFalse("cancel should reset isActive", handler.isActive)
    }

    @Test
    fun `handleDown with null key does not start gesture`() {
        handler.handleDown(null, 0f, 0f)
        handler.handleMove(100f, 0f)
        assertFalse("no gesture for null key", handler.isActive)
        assertTrue("no cursor moves for null key", cursorMoves.isEmpty())
    }
}
