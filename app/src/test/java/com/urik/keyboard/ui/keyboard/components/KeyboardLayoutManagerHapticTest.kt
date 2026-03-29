package com.urik.keyboard.ui.keyboard.components

import android.view.MotionEvent
import android.widget.Button
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyboardLayoutManagerHapticTest {
    private lateinit var manager: KeyboardLayoutManager
    private val firedKeys = mutableListOf<KeyboardKey?>()

    @Before
    fun setup() {
        firedKeys.clear()
        manager = createManager()
        manager.onHapticFired = { key -> firedKeys.add(key) }
        manager.updateHapticSettings(enabled = true, amplitude = 170)
    }

    private fun createManager(): KeyboardLayoutManager {
        val context = RuntimeEnvironment.getApplication()
        val languageManager = mock<LanguageManager>()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))
        val themeManager = mock<ThemeManager>()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))
        return KeyboardLayoutManager(
            context = context,
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = mock<CharacterVariationService>(),
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = CacheMemoryManager(context)
        )
    }

    private fun motionEvent(action: Int, x: Float = 100f, y: Float = 100f): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    private fun buttonFor(key: KeyboardKey): Button {
        val button = Button(RuntimeEnvironment.getApplication())
        button.setTag(R.id.key_data, key)
        return button
    }

    // ── Step 2 — cleanupButton resets isHapticFeedbackEnabled ────────────────

    @Test
    fun `cleanupButton resets isHapticFeedbackEnabled to true`() {
        val button = Button(RuntimeEnvironment.getApplication())
        button.isHapticFeedbackEnabled = false

        manager.cleanupButton(button)

        assertTrue(button.isHapticFeedbackEnabled)
    }

    @Test
    fun `button recycled from backspace slot has isHapticFeedbackEnabled true before configure`() {
        val button = Button(RuntimeEnvironment.getApplication())
        button.isHapticFeedbackEnabled = false

        manager.cleanupButton(button)

        assertTrue("Pool entry must have isHapticFeedbackEnabled reset", button.isHapticFeedbackEnabled)
    }

    // ── Step 4 group A — ACTION_DOWN fires haptic for Character keys ──────────

    @Test
    fun `LETTER key ACTION_DOWN fires haptic before ACTION_UP`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.characterLongPressTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
        assertEquals(key, firedKeys[0])

        val up = motionEvent(MotionEvent.ACTION_UP)
        button.dispatchTouchEvent(up)
        up.recycle()

        assertEquals("Haptic must not fire again on ACTION_UP", 1, firedKeys.size)
    }

    @Test
    fun `NUMBER key ACTION_DOWN fires haptic before ACTION_UP`() {
        val key = KeyboardKey.Character("5", KeyboardKey.KeyType.NUMBER)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.characterLongPressTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
    }

    @Test
    fun `SYMBOL key ACTION_DOWN fires haptic before ACTION_UP`() {
        val key = KeyboardKey.Character("@", KeyboardKey.KeyType.SYMBOL)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.characterLongPressTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
    }

    // ── Step 4 group B — keyClickListener does NOT double-fire ───────────────

    @Test
    fun `keyClickListener skips haptic for LETTER key already fired at ACTION_DOWN`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.characterLongPressTouchListener)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)

        button.performClick()

        assertEquals("keyClickListener must not fire a second haptic", 1, firedKeys.size)
    }

    @Test
    fun `keyClickListener skips haptic for NUMBER key already fired at ACTION_DOWN`() {
        val key = KeyboardKey.Character("5", KeyboardKey.KeyType.NUMBER)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.characterLongPressTouchListener)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
        button.performClick()
        assertEquals(1, firedKeys.size)
    }

    @Test
    fun `keyClickListener skips haptic for SYMBOL key already fired at ACTION_DOWN`() {
        val key = KeyboardKey.Character("@", KeyboardKey.KeyType.SYMBOL)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.characterLongPressTouchListener)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
        button.performClick()
        assertEquals(1, firedKeys.size)
    }

    // ── Step 4 group C — Action keys still fire via keyClickListener ──────────

    @Test
    fun `SPACE key haptic fires via keyClickListener not ACTION_DOWN`() {
        val key = KeyboardKey.Action(KeyboardKey.ActionType.SPACE)
        val button = buttonFor(key)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals("No haptic at ACTION_DOWN for action keys", 0, firedKeys.size)

        button.performClick()

        assertEquals("Haptic fires via keyClickListener for action key", 1, firedKeys.size)
    }

    @Test
    fun `ENTER key haptic fires via keyClickListener not ACTION_DOWN`() {
        val key = KeyboardKey.Action(KeyboardKey.ActionType.ENTER)
        val button = buttonFor(key)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(0, firedKeys.size)
        button.performClick()
        assertEquals(1, firedKeys.size)
    }

    @Test
    fun `SHIFT key haptic fires via keyClickListener not ACTION_DOWN`() {
        val key = KeyboardKey.Action(KeyboardKey.ActionType.SHIFT)
        val button = buttonFor(key)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(0, firedKeys.size)
        button.performClick()
        assertEquals(1, firedKeys.size)
    }

    // ── Step 4 group D — tap-backspace haptic at ACTION_DOWN ──────────────────

    @Test
    fun `backspace tap ACTION_DOWN fires haptic`() {
        val key = KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.backspaceTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
    }

    @Test
    fun `backspace tap does not double-fire haptic when click follows ACTION_DOWN`() {
        val key = KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.backspaceTouchListener)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)

        button.performClick()

        assertEquals("keyClickListener must not fire second haptic for backspace", 1, firedKeys.size)
    }

    @Test
    fun `backspace long-press fires additional haptic after ACTION_DOWN haptic`() {
        val key = KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.backspaceTouchListener)
        button.setOnLongClickListener(manager.backspaceLongClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals("ACTION_DOWN fires haptic once", 1, firedKeys.size)

        button.performLongClick()

        assertEquals("Long-press fires a second distinct haptic", 2, firedKeys.size)
    }

    // ── Step 4 group E — PUNCTUATION keys ─────────────────────────────────────

    @Test
    fun `period key ACTION_DOWN fires haptic`() {
        val key = KeyboardKey.Character(".", KeyboardKey.KeyType.PUNCTUATION)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.punctuationLongPressTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
    }

    @Test
    fun `period long-press does not suppress the ACTION_DOWN haptic`() {
        val key = KeyboardKey.Character(".", KeyboardKey.KeyType.PUNCTUATION)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.punctuationLongPressTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals("ACTION_DOWN fires haptic immediately", 1, firedKeys.size)
    }

    @Test
    fun `comma key ACTION_DOWN fires haptic`() {
        val key = KeyboardKey.Character(",", KeyboardKey.KeyType.PUNCTUATION)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.commaLongPressTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
    }

    @Test
    fun `uncovered PUNCTUATION key ACTION_DOWN fires haptic`() {
        val key = KeyboardKey.Character("?", KeyboardKey.KeyType.PUNCTUATION)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.punctuationTapHapticTouchListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)
    }

    @Test
    fun `uncovered PUNCTUATION key haptic fires exactly once not duplicated by keyClickListener`() {
        val key = KeyboardKey.Character("?", KeyboardKey.KeyType.PUNCTUATION)
        val button = buttonFor(key)
        button.setOnTouchListener(manager.punctuationTapHapticTouchListener)
        button.setOnClickListener(manager.keyClickListener)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(down)
        down.recycle()

        assertEquals(1, firedKeys.size)

        button.performClick()

        assertEquals("keyClickListener must not fire second haptic for punctuation", 1, firedKeys.size)
    }
}
