package com.urik.keyboard.ui.keyboard.components

import android.view.MotionEvent
import com.urik.keyboard.model.KeyboardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlickGestureDetectorTest {
    private lateinit var detector: FlickGestureDetector
    private var committedKey: KeyboardKey.FlickKey? = null
    private var committedDirection: FlickGestureDetector.FlickDirection? = null
    private var lastDirectionChanged: FlickGestureDetector.FlickDirection? = null
    private var cancelFired = false
    private var actionTapped: KeyboardKey.Action? = null

    private val aKey = KeyboardKey.FlickKey(
        center = "あ",
        up = "い",
        right = "う",
        down = "え",
        left = "お",
        type = KeyboardKey.KeyType.LETTER
    )
    private val spaceAction = KeyboardKey.Action(KeyboardKey.ActionType.SPACE)

    @Before
    fun setup() {
        detector = FlickGestureDetector()
        detector.updateDisplayMetrics(density = 3f) // 3f → FLICK_COMMIT_DP * 3 = 60px
        detector.setFlickListener(object : FlickGestureDetector.FlickListener {
            override fun onFlickStart(key: KeyboardKey.FlickKey, anchorX: Float, anchorY: Float) {}
            override fun onFlickDirectionChanged(
                key: KeyboardKey.FlickKey,
                direction: FlickGestureDetector.FlickDirection
            ) {
                lastDirectionChanged = direction
            }
            override fun onFlickCommit(key: KeyboardKey.FlickKey, direction: FlickGestureDetector.FlickDirection) {
                committedKey = key
                committedDirection = direction
            }
            override fun onFlickCancel() {
                cancelFired = true
            }
            override fun onActionTap(key: KeyboardKey.Action) {
                actionTapped = key
            }
        })
    }

    private fun down(x: Float, y: Float, key: KeyboardKey? = aKey): Boolean {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        return detector.handleTouchEvent(event) { _, _ -> key }
    }

    private fun move(x: Float, y: Float, key: KeyboardKey? = aKey): Boolean {
        val event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, x, y, 0)
        return detector.handleTouchEvent(event) { _, _ -> key }
    }

    private fun up(x: Float, y: Float, key: KeyboardKey? = aKey): Boolean {
        val event = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, x, y, 0)
        return detector.handleTouchEvent(event) { _, _ -> key }
    }

    @Test
    fun `tap with no movement commits NONE direction`() {
        down(100f, 100f)
        up(100f, 100f)
        assertEquals(aKey, committedKey)
        assertEquals(FlickGestureDetector.FlickDirection.NONE, committedDirection)
    }

    @Test
    fun `upward flick commits UP direction`() {
        down(100f, 100f)
        move(100f, 40f) // 60px up = exactly at commit threshold (20dp × 3density)
        up(100f, 38f)
        assertEquals(FlickGestureDetector.FlickDirection.UP, committedDirection)
    }

    @Test
    fun `downward flick commits DOWN direction`() {
        down(100f, 100f)
        move(100f, 161f)
        up(100f, 163f)
        assertEquals(FlickGestureDetector.FlickDirection.DOWN, committedDirection)
    }

    @Test
    fun `leftward flick commits LEFT direction`() {
        down(100f, 100f)
        move(38f, 100f)
        up(36f, 100f)
        assertEquals(FlickGestureDetector.FlickDirection.LEFT, committedDirection)
    }

    @Test
    fun `rightward flick commits RIGHT direction`() {
        down(100f, 100f)
        move(162f, 100f)
        up(164f, 100f)
        assertEquals(FlickGestureDetector.FlickDirection.RIGHT, committedDirection)
    }

    @Test
    fun `movement below threshold still commits NONE on up`() {
        down(100f, 100f)
        move(100f, 70f) // 30px — below 60px threshold
        up(100f, 70f)
        assertEquals(FlickGestureDetector.FlickDirection.NONE, committedDirection)
    }

    @Test
    fun `direction locked once threshold crossed — late curve still commits original direction`() {
        down(100f, 100f)
        move(100f, 10f) // strong upward, locks UP
        move(100f, 10f)
        move(180f, 10f) // curves right after lock
        up(180f, 10f)
        assertEquals(FlickGestureDetector.FlickDirection.UP, committedDirection)
    }

    @Test
    fun `ACTION_CANCEL fires onFlickCancel`() {
        down(100f, 100f)
        val event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, 100f, 100f, 0)
        detector.handleTouchEvent(event) { _, _ -> aKey }
        assertEquals(true, cancelFired)
        assertNull(committedKey)
    }

    @Test
    fun `action key tap fires onActionTap`() {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        detector.handleTouchEvent(event) { _, _ -> spaceAction }
        val upEvent = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 100f, 100f, 0)
        detector.handleTouchEvent(upEvent) { _, _ -> spaceAction }
        assertEquals(spaceAction, actionTapped)
        assertNull(committedKey)
    }

    @Test
    fun `onFlickDirectionChanged fires with current direction during move`() {
        down(100f, 100f)
        move(100f, 30f) // crosses threshold going up
        assertEquals(FlickGestureDetector.FlickDirection.UP, lastDirectionChanged)
    }
}
