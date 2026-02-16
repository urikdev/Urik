package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.MotionEvent
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.service.WordNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SwipeDetectorTest {
    @Mock
    private lateinit var spellCheckManager: SpellCheckManager

    @Mock
    private lateinit var wordLearningEngine: WordLearningEngine

    @Mock
    private lateinit var pathGeometryAnalyzer: PathGeometryAnalyzer

    @Mock
    private lateinit var wordNormalizer: WordNormalizer

    @Mock
    private lateinit var wordFrequencyRepository: WordFrequencyRepository

    @Mock
    private lateinit var residualScorer: ResidualScorer

    @Mock
    private lateinit var zipfCheck: ZipfCheck

    @Mock
    private lateinit var swipeListener: SwipeDetector.SwipeListener

    private lateinit var swipeDetector: SwipeDetector
    private lateinit var closeable: AutoCloseable

    @Before
    fun setup() {
        closeable = MockitoAnnotations.openMocks(this)
        swipeDetector =
            SwipeDetector(
                spellCheckManager,
                wordLearningEngine,
                pathGeometryAnalyzer,
                wordFrequencyRepository,
                residualScorer,
                zipfCheck,
                wordNormalizer,
            )
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
    fun `updateKeyPositions stores character positions`() {
        val positions =
            mapOf(
                KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) to PointF(10f, 20f),
                KeyboardKey.Character("b", KeyboardKey.KeyType.LETTER) to PointF(30f, 40f),
                KeyboardKey.Character("", KeyboardKey.KeyType.LETTER) to PointF(50f, 60f),
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

    @Test
    fun `dwell-then-jump peck does not activate swipe`() {
        swipeDetector.updateDisplayMetrics(2.0f)

        val keyAt: (Float, Float) -> KeyboardKey? = { x, _ ->
            if (x < 150f) {
                KeyboardKey.Character("e", KeyboardKey.KeyType.LETTER)
            } else {
                KeyboardKey.Character("h", KeyboardKey.KeyType.LETTER)
            }
        }

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        swipeDetector.handleTouchEvent(downEvent, keyAt)
        downEvent.recycle()

        Thread.sleep(60)

        val dwellMove = createMotionEvent(MotionEvent.ACTION_MOVE, 105f, 201f)
        swipeDetector.handleTouchEvent(dwellMove, keyAt)
        dwellMove.recycle()

        Thread.sleep(60)

        val jumpMove = createMotionEvent(MotionEvent.ACTION_MOVE, 250f, 200f)
        val swipeActivated = swipeDetector.handleTouchEvent(jumpMove, keyAt)
        jumpMove.recycle()

        assertFalse("Dwell-then-jump peck should not activate swipe", swipeActivated)
    }

    @Test
    fun `distributed swipe motion activates normally`() {
        swipeDetector.updateDisplayMetrics(2.0f)

        val keyAt: (Float, Float) -> KeyboardKey? = { x, _ ->
            if (x < 150f) {
                KeyboardKey.Character("e", KeyboardKey.KeyType.LETTER)
            } else {
                KeyboardKey.Character("h", KeyboardKey.KeyType.LETTER)
            }
        }

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        swipeDetector.handleTouchEvent(downEvent, keyAt)
        downEvent.recycle()

        var swipeActivated = false
        val steps =
            arrayOf(
                floatArrayOf(120f, 202f),
                floatArrayOf(140f, 204f),
                floatArrayOf(160f, 200f),
                floatArrayOf(180f, 202f),
                floatArrayOf(200f, 204f),
                floatArrayOf(220f, 200f),
                floatArrayOf(240f, 202f),
                floatArrayOf(260f, 204f),
            )

        for (step in steps) {
            Thread.sleep(20)
            val moveEvent = createMotionEvent(MotionEvent.ACTION_MOVE, step[0], step[1])
            if (swipeDetector.handleTouchEvent(moveEvent, keyAt)) {
                swipeActivated = true
            }
            moveEvent.recycle()
            if (swipeActivated) break
        }

        assertTrue("Distributed swipe motion should activate swipe", swipeActivated)
    }

    @Test
    fun `peck rejection falls through to tap handler`() {
        swipeDetector.updateDisplayMetrics(2.0f)

        val keyAt: (Float, Float) -> KeyboardKey? = { x, _ ->
            if (x < 150f) {
                KeyboardKey.Character("e", KeyboardKey.KeyType.LETTER)
            } else {
                KeyboardKey.Character("h", KeyboardKey.KeyType.LETTER)
            }
        }

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        swipeDetector.handleTouchEvent(downEvent, keyAt)
        downEvent.recycle()

        Thread.sleep(60)

        val dwellMove = createMotionEvent(MotionEvent.ACTION_MOVE, 105f, 201f)
        swipeDetector.handleTouchEvent(dwellMove, keyAt)
        dwellMove.recycle()

        Thread.sleep(60)

        val jumpMove = createMotionEvent(MotionEvent.ACTION_MOVE, 250f, 200f)
        swipeDetector.handleTouchEvent(jumpMove, keyAt)
        jumpMove.recycle()

        val upEvent = createMotionEvent(MotionEvent.ACTION_UP, 250f, 200f)
        val result = swipeDetector.handleTouchEvent(upEvent, keyAt)
        upEvent.recycle()

        assertTrue("Peck rejection should allow tap fallthrough on ACTION_UP", result)
        verify(swipeListener).onTap(any())
    }

    private fun createMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long = System.currentTimeMillis(),
    ): MotionEvent =
        MotionEvent.obtain(
            eventTime - 100,
            eventTime,
            action,
            x,
            y,
            0,
        )
}
