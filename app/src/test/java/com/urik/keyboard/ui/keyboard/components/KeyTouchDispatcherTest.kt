package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.LongPressPunctuationMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyTouchDispatcherTest {
    private lateinit var dispatcher: KeyTouchDispatcher
    private val hapticFired = mutableListOf<KeyboardKey?>()
    private val clickedKeys = mutableListOf<KeyboardKey>()

    private val longPressConsumedButtons = mutableSetOf<Button>()
    private val hapticDownFiredButtons = mutableSetOf<Button>()
    private val characterLongPressFired = mutableSetOf<Button>()
    private val symbolsLongPressFired = mutableSetOf<Button>()
    private val customMappingLongPressFired = mutableSetOf<Button>()
    private val buttonPendingCallbacks = mutableMapOf<Button, PendingCallbacks>()
    private val buttonLongPressRunnables = mutableMapOf<Button, Runnable>()
    private val pressStartTimes = mutableMapOf<Button, Long>()

    @Before
    fun setup() {
        longPressConsumedButtons.clear()
        hapticDownFiredButtons.clear()
        characterLongPressFired.clear()
        symbolsLongPressFired.clear()
        customMappingLongPressFired.clear()
        buttonPendingCallbacks.clear()
        buttonLongPressRunnables.clear()
        pressStartTimes.clear()
        hapticFired.clear()
        clickedKeys.clear()

        val context = RuntimeEnvironment.getApplication()
        dispatcher = makeDispatcher(context, clickedKeys)
    }

    private fun makeDispatcher(
        context: Context,
        clicks: MutableList<KeyboardKey> = mutableListOf()
    ): KeyTouchDispatcher = KeyTouchDispatcher(
        onKeyClick = { key -> clicks.add(key) },
        performHaptic = { key -> hapticFired.add(key) },
        getLongPressDuration = { LongPressDuration.MEDIUM },
        getLongPressPunctuationMode = { LongPressPunctuationMode.PERIOD },
        getActiveLanguages = { listOf("en") },
        getShowLanguageSwitchKey = { false },
        getPopupSelectionMode = { false },
        setPopupSelectionMode = { _ -> },
        getVariationPopup = { null },
        setVariationPopup = { _ -> },
        getActivePunctuationPopup = { null },
        setActivePunctuationPopup = { _ -> },
        getLongPressConsumedButtons = { longPressConsumedButtons },
        getHapticDownFiredButtons = { hapticDownFiredButtons },
        getCharacterLongPressFired = { characterLongPressFired },
        getSymbolsLongPressFired = { symbolsLongPressFired },
        getCustomMappingLongPressFired = { customMappingLongPressFired },
        getButtonPendingCallbacks = { buttonPendingCallbacks },
        getButtonLongPressRunnables = { buttonLongPressRunnables },
        getPressStartTimes = { pressStartTimes },
        backspaceController = null,
        setSwipePopupActive = { _ -> },
        getCurrentVariationKeyType = { null },
        accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    )

    private fun motionEvent(action: Int): MotionEvent = MotionEvent.obtain(0L, 0L, action, 100f, 100f, 0)

    private fun buttonFor(key: KeyboardKey): Button {
        val button = Button(RuntimeEnvironment.getApplication())
        button.setTag(R.id.key_data, key)
        return button
    }

    @Test
    fun `attachListeners sets touch listener for letter key`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)

        dispatcher.attachListeners(button, key)

        val downEvent = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(downEvent)
        assertNotNull("Haptic should fire on letter key touch", hapticFired.firstOrNull())
    }

    @Test
    fun `attachListeners sets long-click listener for backspace key`() {
        val key = KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        val button = buttonFor(key)

        dispatcher.attachListeners(button, key)

        assertNotNull("Button should have a long click listener after attachListeners", button)
    }

    @Test
    fun `attachListeners sets punctuation tap haptic for non-period punctuation`() {
        val key = KeyboardKey.Character("!", KeyboardKey.KeyType.PUNCTUATION)
        val button = buttonFor(key)

        dispatcher.attachListeners(button, key)

        val downEvent = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(downEvent)
        assertNotNull("Haptic should fire on punctuation tap", hapticFired.firstOrNull())
    }

    @Test
    fun `attachListeners sets punctuation long press listener for period key`() {
        val key = KeyboardKey.Character(".", KeyboardKey.KeyType.PUNCTUATION)
        val button = buttonFor(key)

        dispatcher.attachListeners(button, key)

        val downEvent = motionEvent(MotionEvent.ACTION_DOWN)
        button.dispatchTouchEvent(downEvent)
        assertNotNull("Haptic should fire on period key touch", hapticFired.firstOrNull())
    }

    @Test
    fun `backspace ACTION_CANCEL stops accelerated deletion`() {
        val stopCalled = mutableListOf<Boolean>()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val controller = BackspaceController(
            onBackspaceKey = {},
            onAcceleratedDeletionChanged = { stopCalled.add(it) },
            vibrateEffect = {},
            cancelVibration = {},
            getHapticEnabled = { false },
            getHapticAmplitude = { 0 },
            getSupportsAmplitudeControl = { false },
            getBackgroundScope = { scope }
        )
        val context = RuntimeEnvironment.getApplication()
        val localClicks = mutableListOf<KeyboardKey>()
        val dispatcherWithController = KeyTouchDispatcher(
            onKeyClick = { localClicks.add(it) },
            performHaptic = {},
            getLongPressDuration = { LongPressDuration.MEDIUM },
            getLongPressPunctuationMode = { LongPressPunctuationMode.PERIOD },
            getActiveLanguages = { listOf("en") },
            getShowLanguageSwitchKey = { false },
            getPopupSelectionMode = { false },
            setPopupSelectionMode = {},
            getVariationPopup = { null },
            setVariationPopup = {},
            getActivePunctuationPopup = { null },
            setActivePunctuationPopup = {},
            getLongPressConsumedButtons = { longPressConsumedButtons },
            getHapticDownFiredButtons = { hapticDownFiredButtons },
            getCharacterLongPressFired = { characterLongPressFired },
            getSymbolsLongPressFired = { symbolsLongPressFired },
            getCustomMappingLongPressFired = { customMappingLongPressFired },
            getButtonPendingCallbacks = { buttonPendingCallbacks },
            getButtonLongPressRunnables = { buttonLongPressRunnables },
            getPressStartTimes = { pressStartTimes },
            backspaceController = controller,
            setSwipePopupActive = {},
            getCurrentVariationKeyType = { null },
            accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        )

        val key = KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        val button = buttonFor(key)
        dispatcherWithController.attachListeners(button, key)

        controller.start()

        val cancelEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 100f, 100f, 0)
        button.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()

        assertTrue(
            "ACTION_CANCEL on backspace must stop accelerated deletion",
            stopCalled.contains(false)
        )

        controller.cleanup()
    }

    @Test
    fun `custom mapping long press on number key prevents click even when longPressConsumedButtons is empty`() {
        val key = KeyboardKey.Character("1", KeyboardKey.KeyType.NUMBER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        customMappingLongPressFired.add(button)

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        val consumed = dispatcher.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertTrue("ACTION_UP must be consumed when custom mapping long press fired", consumed)
        assertTrue("keyClickListener must not fire when custom mapping long press fired", clickedKeys.isEmpty())
    }

    // keyClickListener blocked by longPressConsumedButtons alone

    @Test
    fun `keyClickListener - blocked when longPressConsumedButtons has button and characterLongPressFired empty`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        longPressConsumedButtons.add(button)

        dispatcher.keyClickListener.onClick(button)

        assertTrue("onKeyClick must NOT be called when longPressConsumedButtons contains button", clickedKeys.isEmpty())
        assertFalse(
            "longPressConsumedButtons should be cleared after click guard",
            longPressConsumedButtons.contains(button)
        )
    }

    // Elapsed-time guard tests

    @Test
    fun `characterLongPressTouchListener ACTION_UP - consumed when elapsed time equals long press threshold`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        val durationMs = LongPressDuration.MEDIUM.durationMs
        pressStartTimes[button] = SystemClock.uptimeMillis() - durationMs

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        val consumed = dispatcher.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertTrue("ACTION_UP must be consumed when elapsed equals threshold", consumed)
        assertTrue("onKeyClick must NOT fire when elapsed equals threshold", clickedKeys.isEmpty())
    }

    @Test
    fun `characterLongPressTouchListener ACTION_UP - consumed when elapsed time exceeds long press threshold`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        val durationMs = LongPressDuration.MEDIUM.durationMs
        pressStartTimes[button] = SystemClock.uptimeMillis() - durationMs - 50L

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        val consumed = dispatcher.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertTrue("ACTION_UP must be consumed when elapsed exceeds threshold", consumed)
        assertTrue("onKeyClick must NOT fire when elapsed exceeds threshold", clickedKeys.isEmpty())
    }

    @Test
    fun `characterLongPressTouchListener ACTION_UP - not consumed when elapsed below threshold`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        val durationMs = LongPressDuration.MEDIUM.durationMs
        pressStartTimes[button] = SystemClock.uptimeMillis() - (durationMs - 50L)

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        val consumed = dispatcher.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertFalse("ACTION_UP must NOT be consumed when elapsed is below threshold", consumed)
        dispatcher.keyClickListener.onClick(button)
        assertTrue("onKeyClick must fire when short press (elapsed below threshold)", clickedKeys.isNotEmpty())
    }

    // Cleanup correctness tests

    @Test
    fun `characterLongPressTouchListener ACTION_DOWN records press timestamp`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        val downEvent = motionEvent(MotionEvent.ACTION_DOWN)
        dispatcher.characterLongPressTouchListener.onTouch(button, downEvent)

        assertTrue("pressStartTimes must contain button after ACTION_DOWN", pressStartTimes.containsKey(button))
    }

    @Test
    fun `characterLongPressTouchListener ACTION_UP removes press timestamp`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        pressStartTimes[button] = SystemClock.uptimeMillis()

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        dispatcher.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertFalse("pressStartTimes must NOT contain button after ACTION_UP", pressStartTimes.containsKey(button))
    }

    @Test
    fun `characterLongPressTouchListener ACTION_CANCEL removes press timestamp`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        dispatcher.attachListeners(button, key)

        pressStartTimes[button] = SystemClock.uptimeMillis()

        val cancelEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 100f, 100f, 0)
        dispatcher.characterLongPressTouchListener.onTouch(button, cancelEvent)
        cancelEvent.recycle()

        assertFalse("pressStartTimes must NOT contain button after ACTION_CANCEL", pressStartTimes.containsKey(button))
    }

    @Test
    fun `long press runnable fires after delay elapses via ShadowLooper`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        val runnableFired = mutableListOf<Boolean>()
        buttonLongPressRunnables[button] = Runnable { runnableFired.add(true) }

        dispatcher.attachListeners(button, key)

        val downEvent = motionEvent(MotionEvent.ACTION_DOWN)
        dispatcher.characterLongPressTouchListener.onTouch(button, downEvent)

        ShadowLooper.idleMainLooper(LongPressDuration.MEDIUM.durationMs, TimeUnit.MILLISECONDS)

        assertTrue("Long press runnable must fire after delay", runnableFired.isNotEmpty())
    }

    @Test
    fun `long press runnable does NOT fire before delay via ShadowLooper`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        val runnableFired = mutableListOf<Boolean>()
        buttonLongPressRunnables[button] = Runnable { runnableFired.add(true) }

        dispatcher.attachListeners(button, key)

        val downEvent = motionEvent(MotionEvent.ACTION_DOWN)
        dispatcher.characterLongPressTouchListener.onTouch(button, downEvent)

        ShadowLooper.idleMainLooper(LongPressDuration.MEDIUM.durationMs - 100L, TimeUnit.MILLISECONDS)

        assertTrue("Long press runnable must NOT fire before delay", runnableFired.isEmpty())
    }
}
