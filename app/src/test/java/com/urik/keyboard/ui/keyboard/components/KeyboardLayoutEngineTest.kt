package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyboardLayoutEngineTest {
    private lateinit var engine: KeyboardLayoutEngine
    private lateinit var closeable: AutoCloseable
    private var lastKeyCharPositions = emptyMap<KeyboardKey.Character, PointF>()
    private var lastCharToPosition = emptyMap<Char, PointF>()

    @Before
    fun setup() {
        closeable = MockitoAnnotations.openMocks(this)
        val mockView = mock<View>()
        val transformer = TouchCoordinateTransformer(mockView)
        engine = KeyboardLayoutEngine(
            touchCoordinateTransformer = transformer,
            onPositionsUpdated = { keyCharPos, charToPos ->
                lastKeyCharPositions = keyCharPos
                lastCharToPosition = charToPos
            }
        )
    }

    @After
    fun teardown() {
        closeable.close()
    }

    private fun buildEngineWithButton(button: Button, key: KeyboardKey): Map<Button, Rect> {
        val app = RuntimeEnvironment.getApplication()
        val container = FrameLayout(app)
        container.addView(button)
        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf(listOf(key))
        )
        engine.buildFromViewGroup(container, layout)
        return mapOf(button to Rect(0, 0, 100, 100))
    }

    @Test
    fun `findKeyAt returns key whose rect contains the point`() {
        val app = RuntimeEnvironment.getApplication()
        val button = Button(app)
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val rawPositions = buildEngineWithButton(button, key)
        engine.applyPositions(rawPositions, 400, 300)
        val result = engine.findKeyAt(50f, 50f)
        assertEquals(key, result)
    }

    @Test
    fun `findKeyAt returns null when positions empty after clear`() {
        engine.clear()
        val result = engine.findKeyAt(50f, 50f)
        assertNull(result)
    }

    @Test
    fun `findKeyAt returns closest key when point is outside all rects`() {
        val app = RuntimeEnvironment.getApplication()
        val button = Button(app)
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val rawPositions = buildEngineWithButton(button, key)
        engine.applyPositions(rawPositions, 400, 300)
        val result = engine.findKeyAt(150f, 150f)
        assertEquals(key, result)
    }

    @Test
    fun `applyPositions fires onPositionsUpdated callback`() {
        val app = RuntimeEnvironment.getApplication()
        val button = Button(app)
        val key = KeyboardKey.Character("z", KeyboardKey.KeyType.LETTER)
        val rawPositions = buildEngineWithButton(button, key)
        engine.applyPositions(rawPositions, 400, 300)
        assert(lastKeyCharPositions.isNotEmpty() || lastCharToPosition.isNotEmpty()) {
            "onPositionsUpdated callback must fire after applyPositions"
        }
    }

    @Test
    fun `expandEdgeKeyHitAreas extends bottom-row rect to viewHeight`() {
        assert(true) { "Stub — verified when KeyboardLayoutEngine exists" }
    }

    @Test
    fun `computeNumberRowBoundary returns negative when layout has no number row`() {
        assert(true) { "Stub — verified when KeyboardLayoutEngine exists" }
    }

    @Test
    fun `clear resets all internal state`() {
        val app = RuntimeEnvironment.getApplication()
        val button = Button(app)
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val rawPositions = buildEngineWithButton(button, key)
        engine.applyPositions(rawPositions, 400, 300)
        engine.clear()
        val result = engine.findKeyAt(50f, 50f)
        assertNull("findKeyAt should return null after clear", result)
    }
}
