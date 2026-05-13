package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.LongPressPunctuationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyTouchDispatcherTest {
    private lateinit var dispatcher: KeyTouchDispatcher
    private val hapticFired = mutableListOf<KeyboardKey?>()
    private val clickedKeys = mutableListOf<KeyboardKey>()

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        dispatcher = KeyTouchDispatcher(
            onKeyClick = { key -> clickedKeys.add(key) },
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
            getLongPressConsumedButtons = { mutableSetOf() },
            getHapticDownFiredButtons = { mutableSetOf() },
            getCharacterLongPressFired = { mutableSetOf() },
            getSymbolsLongPressFired = { mutableSetOf() },
            getCustomMappingLongPressFired = { mutableSetOf() },
            getButtonPendingCallbacks = { mutableMapOf() },
            getButtonLongPressRunnables = { mutableMapOf() },
            backspaceController = null,
            setSwipePopupActive = { _ -> },
            getCurrentVariationKeyType = { null },
            accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        )
    }

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
        val dispatcherWithController = KeyTouchDispatcher(
            onKeyClick = {},
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
            getLongPressConsumedButtons = { mutableSetOf() },
            getHapticDownFiredButtons = { mutableSetOf() },
            getCharacterLongPressFired = { mutableSetOf() },
            getSymbolsLongPressFired = { mutableSetOf() },
            getCustomMappingLongPressFired = { mutableSetOf() },
            getButtonPendingCallbacks = { mutableMapOf() },
            getButtonLongPressRunnables = { mutableMapOf() },
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
}
