package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.MotionEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.SpellCheckManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SwipeDetectorTest {
    @Mock
    private lateinit var spellCheckManager: SpellCheckManager

    @Mock
    private lateinit var swipeListener: SwipeDetector.SwipeListener

    private lateinit var swipeDetector: SwipeDetector
    private lateinit var closeable: AutoCloseable

    @Before
    fun setup() {
        closeable = MockitoAnnotations.openMocks(this)
        swipeDetector = SwipeDetector(spellCheckManager)
        swipeDetector.setSwipeListener(swipeListener)
    }

    @After
    fun teardown() {
        swipeDetector.cleanup()
        closeable.close()
    }

    @Test
    fun `handleTouchEvent ACTION_DOWN on Character key starts detection`() {
        val event = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        }

        val result = swipeDetector.handleTouchEvent(event, keyAt)

        assertTrue("Should consume event for character key", result)
        event.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_DOWN on Action key does not start detection`() {
        val event = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Action(KeyboardKey.ActionType.SPACE)
        }

        val result = swipeDetector.handleTouchEvent(event, keyAt)

        assertFalse("Should not consume event for action key", result)
        event.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_DOWN on backspace key does not start detection`() {
        val event = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        }

        val result = swipeDetector.handleTouchEvent(event, keyAt)

        assertFalse("Should not consume event for backspace key", result)
        event.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_DOWN on shift key does not start detection`() {
        val event = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Action(KeyboardKey.ActionType.SHIFT)
        }

        val result = swipeDetector.handleTouchEvent(event, keyAt)

        assertFalse("Should not consume event for shift key", result)
        event.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_DOWN on mode switch key does not start detection`() {
        val event = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS)
        }

        val result = swipeDetector.handleTouchEvent(event, keyAt)

        assertFalse("Should not consume event for mode switch key", result)
        event.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_DOWN on null key does not start detection`() {
        val event = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ -> null }

        val result = swipeDetector.handleTouchEvent(event, keyAt)

        assertFalse("Should not consume event for null key", result)
        event.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_CANCEL resets state`() {
        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        }

        swipeDetector.handleTouchEvent(downEvent, keyAt)
        downEvent.recycle()

        val cancelEvent = createMotionEvent(MotionEvent.ACTION_CANCEL, 100f, 200f)
        val result = swipeDetector.handleTouchEvent(cancelEvent, keyAt)

        assertFalse("Should not consume cancel event", result)
        cancelEvent.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_UP without swipe is treated as tap`() {
        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        }

        swipeDetector.handleTouchEvent(downEvent, keyAt)
        downEvent.recycle()

        Thread.sleep(100)

        val upEvent = createMotionEvent(MotionEvent.ACTION_UP, 100f, 200f)
        val result = swipeDetector.handleTouchEvent(upEvent, keyAt)

        assertTrue("Should consume tap event", result)
        verify(swipeListener).onTap(any())
        upEvent.recycle()
    }

    @Test
    fun `handleTouchEvent ACTION_UP after long duration without swipe does not trigger tap`() {
        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        }

        swipeDetector.handleTouchEvent(downEvent, keyAt)
        downEvent.recycle()

        Thread.sleep(400)

        val upEvent = createMotionEvent(MotionEvent.ACTION_UP, 100f, 200f)
        val result = swipeDetector.handleTouchEvent(upEvent, keyAt)

        assertFalse("Should not consume event after long press duration", result)
        verify(swipeListener, never()).onTap(any())
        upEvent.recycle()
    }

    @Test
    fun `updateKeyPositions stores character positions`() {
        val positions = mapOf(
            KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) to PointF(10f, 20f),
            KeyboardKey.Character("b", KeyboardKey.KeyType.LETTER) to PointF(30f, 40f),
            KeyboardKey.Character("", KeyboardKey.KeyType.LETTER) to PointF(50f, 60f)
        )

        swipeDetector.updateKeyPositions(positions)

        verify(swipeListener, never()).onSwipeStart(any())
    }

    @Test
    fun `setSwipeListener clears previous listener`() {
        val listener1 = mock<SwipeDetector.SwipeListener>()
        val listener2 = mock<SwipeDetector.SwipeListener>()

        swipeDetector.setSwipeListener(listener1)
        swipeDetector.setSwipeListener(listener2)
        swipeDetector.setSwipeListener(null)

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        }

        swipeDetector.handleTouchEvent(downEvent, keyAt)
        downEvent.recycle()

        verify(listener1, never()).onSwipeStart(any())
        verify(listener2, never()).onSwipeStart(any())
    }

    @Test
    fun `cleanup cancels pending operations`() {
        swipeDetector.cleanup()

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        val keyAt: (Float, Float) -> KeyboardKey? = { _, _ ->
            KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        }

        val result = swipeDetector.handleTouchEvent(downEvent, keyAt)

        assertTrue("Should still handle events after cleanup", result)
        downEvent.recycle()
    }

    private fun createMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long = System.currentTimeMillis()
    ): MotionEvent {
        return MotionEvent.obtain(
            eventTime - 100,
            eventTime,
            action,
            x,
            y,
            0
        )
    }
}
