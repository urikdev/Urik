package com.urik.keyboard.ui.keyboard.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.LongPressPunctuationMode

@Suppress("LongParameterList")
internal class KeyTouchDispatcher(
    private val onKeyClick: (KeyboardKey) -> Unit,
    private val performHaptic: (KeyboardKey?) -> Unit,
    private val getLongPressDuration: () -> LongPressDuration,
    private val getLongPressPunctuationMode: () -> LongPressPunctuationMode,
    private val getActiveLanguages: () -> List<String>,
    private val getShowLanguageSwitchKey: () -> Boolean,
    private val getPopupSelectionMode: () -> Boolean,
    private val setPopupSelectionMode: (Boolean) -> Unit,
    private val getVariationPopup: () -> CharacterVariationPopup?,
    private val setVariationPopup: (CharacterVariationPopup?) -> Unit,
    private val getActivePunctuationPopup: () -> CharacterVariationPopup?,
    private val setActivePunctuationPopup: (CharacterVariationPopup?) -> Unit,
    private val getLongPressConsumedButtons: () -> MutableSet<Button>,
    private val getHapticDownFiredButtons: () -> MutableSet<Button>,
    private val getCharacterLongPressFired: () -> MutableSet<Button>,
    private val getSymbolsLongPressFired: () -> MutableSet<Button>,
    private val getCustomMappingLongPressFired: () -> MutableSet<Button>,
    private val getButtonPendingCallbacks: () -> MutableMap<Button, PendingCallbacks>,
    private val getButtonLongPressRunnables: () -> MutableMap<Button, Runnable>,
    private val backspaceController: BackspaceController?,
    private val setSwipePopupActive: (Boolean) -> Unit,
    private val getCurrentVariationKeyType: () -> KeyboardKey.KeyType?,
    private val accessibilityManager: AccessibilityManager
) {
    internal var shiftLongPressFired = false
    internal var commaLongPressFired = false

    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private val longPressCancelThresholdPx = 20f
    private var spaceGestureStartX = 0f
    private var spaceGestureStartY = 0f

    private val handler = Handler(Looper.getMainLooper())

    @VisibleForTesting
    internal val keyClickListener =
        View.OnClickListener { view ->
            if (getLongPressConsumedButtons().remove(view as? Button)) return@OnClickListener

            val key = view.getTag(R.id.key_data) as? KeyboardKey ?: return@OnClickListener
            val skipHaptic = getHapticDownFiredButtons().remove(view as? Button)
            if (!skipHaptic) {
                performHaptic(key)
            }

            if (accessibilityManager.isEnabled) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
                event.contentDescription = view.contentDescription
                accessibilityManager.sendAccessibilityEvent(event)
            }

            onKeyClick(key)
        }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val characterLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val key = view.getTag(R.id.key_data) as? KeyboardKey.Character ?: return@OnTouchListener false
                    val button = view as Button
                    getCharacterLongPressFired().remove(button)
                    getCustomMappingLongPressFired().remove(button)
                    getLongPressConsumedButtons().remove(button)
                    getHapticDownFiredButtons().add(button)
                    performHaptic(key)
                    longPressStartX = event.rawX
                    longPressStartY = event.rawY
                    val runnable = getButtonLongPressRunnables()[button] ?: return@OnTouchListener false
                    getButtonPendingCallbacks()[button] = PendingCallbacks(handler, runnable)
                    handler.postDelayed(runnable, getLongPressDuration().durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val button = view as Button
                    if (getPopupSelectionMode() && getVariationPopup()?.isShowing == true) {
                        val previousChar = getVariationPopup()?.getHighlightedCharacter()
                        val char = getVariationPopup()?.getCharacterAt(event.rawX, event.rawY)
                        getVariationPopup()?.setHighlighted(char)
                        if (char != null && char != previousChar) {
                            performHaptic(null)
                        }
                        return@OnTouchListener true
                    }
                    if (getCharacterLongPressFired().contains(button)) {
                        return@OnTouchListener true
                    }
                    val dx = event.rawX - longPressStartX
                    val dy = event.rawY - longPressStartY
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (distance > longPressCancelThresholdPx) {
                        getButtonPendingCallbacks().remove(button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    val button = view as Button
                    getButtonPendingCallbacks().remove(button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }

                    val longPressConsumed =
                        getCharacterLongPressFired().remove(button) ||
                            getLongPressConsumedButtons().contains(button)

                    if (getPopupSelectionMode() && getVariationPopup()?.isShowing == true) {
                        val selectedChar = getVariationPopup()?.getHighlightedCharacter()
                        if (selectedChar != null) {
                            getVariationPopup()?.dismiss()
                            setVariationPopup(null)
                            setPopupSelectionMode(false)
                            setSwipePopupActive(false)

                            val keyType = getCurrentVariationKeyType() ?: KeyboardKey.KeyType.LETTER
                            val selectedKey = KeyboardKey.Character(selectedChar, keyType)
                            performHaptic(selectedKey)
                            onKeyClick(selectedKey)
                        } else {
                            setPopupSelectionMode(false)
                            setSwipePopupActive(false)
                        }
                        getCustomMappingLongPressFired().remove(button)
                        return@OnTouchListener true
                    }

                    getCustomMappingLongPressFired().remove(button)
                    longPressConsumed
                }

                MotionEvent.ACTION_CANCEL -> {
                    val button = view as Button
                    getButtonPendingCallbacks().remove(button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }

                    val longPressConsumed =
                        getCharacterLongPressFired().remove(button) ||
                            getLongPressConsumedButtons().contains(button)

                    if (getPopupSelectionMode() && getVariationPopup()?.isShowing == true) {
                        getVariationPopup()?.dismiss()
                        setVariationPopup(null)
                        setPopupSelectionMode(false)
                        setSwipePopupActive(false)
                    }

                    getCustomMappingLongPressFired().remove(button)
                    longPressConsumed
                }

                else -> {
                    false
                }
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    internal val spaceLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setPopupSelectionMode(false)
                    getLongPressConsumedButtons().remove(view as Button)
                    spaceGestureStartX = event.x
                    spaceGestureStartY = event.y
                    val runnable = getButtonLongPressRunnables()[view as Button] ?: return@OnTouchListener false
                    getButtonPendingCallbacks()[view] = PendingCallbacks(handler, runnable)
                    handler.postDelayed(runnable, getLongPressDuration().durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (getPopupSelectionMode() && getActivePunctuationPopup() != null) {
                        val char = getActivePunctuationPopup()?.getCharacterAt(event.rawX, event.rawY)
                        getActivePunctuationPopup()?.setHighlighted(char)
                        return@OnTouchListener true
                    }

                    if (!getPopupSelectionMode()) {
                        val dx = event.x - spaceGestureStartX
                        val dy = event.y - spaceGestureStartY
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (distance > 20f) {
                            getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                                pending.handler.removeCallbacks(pending.runnable)
                            }
                        }
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    if (getPopupSelectionMode() && getActivePunctuationPopup() != null) {
                        val selectedChar = getActivePunctuationPopup()?.getHighlightedCharacter()

                        getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }

                        if (selectedChar != null) {
                            getActivePunctuationPopup()?.dismiss()
                            setActivePunctuationPopup(null)
                            setPopupSelectionMode(false)
                            setSwipePopupActive(false)

                            val punctuationKey = KeyboardKey.Character(selectedChar, KeyboardKey.KeyType.PUNCTUATION)
                            performHaptic(punctuationKey)
                            onKeyClick(punctuationKey)
                        } else {
                            getActivePunctuationPopup()?.setHighlighted(null)
                        }

                        return@OnTouchListener true
                    }

                    getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (getPopupSelectionMode() && getActivePunctuationPopup() != null) {
                        getActivePunctuationPopup()?.dismiss()
                        setActivePunctuationPopup(null)
                        setPopupSelectionMode(false)
                        setSwipePopupActive(false)
                    }

                    getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    false
                }

                else -> {
                    false
                }
            }
        }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val punctuationLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setPopupSelectionMode(false)
                    getLongPressConsumedButtons().remove(view as Button)
                    val key = view.getTag(R.id.key_data) as? KeyboardKey.Character ?: return@OnTouchListener false
                    getHapticDownFiredButtons().add(view as Button)
                    performHaptic(key)
                    val runnable = getButtonLongPressRunnables()[view as Button] ?: return@OnTouchListener false
                    getButtonPendingCallbacks()[view] = PendingCallbacks(handler, runnable)
                    handler.postDelayed(runnable, getLongPressDuration().durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (getPopupSelectionMode() && getActivePunctuationPopup() != null) {
                        val char = getActivePunctuationPopup()?.getCharacterAt(event.rawX, event.rawY)
                        getActivePunctuationPopup()?.setHighlighted(char)
                        return@OnTouchListener true
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    if (getPopupSelectionMode() && getActivePunctuationPopup() != null) {
                        val selectedChar = getActivePunctuationPopup()?.getHighlightedCharacter()

                        getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }

                        if (selectedChar != null) {
                            getActivePunctuationPopup()?.dismiss()
                            setActivePunctuationPopup(null)
                            setPopupSelectionMode(false)
                            setSwipePopupActive(false)

                            val punctuationKey = KeyboardKey.Character(selectedChar, KeyboardKey.KeyType.PUNCTUATION)
                            performHaptic(punctuationKey)
                            onKeyClick(punctuationKey)
                        } else {
                            getActivePunctuationPopup()?.setHighlighted(null)
                        }

                        return@OnTouchListener true
                    }

                    getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (getPopupSelectionMode() && getActivePunctuationPopup() != null) {
                        getActivePunctuationPopup()?.dismiss()
                        setActivePunctuationPopup(null)
                        setPopupSelectionMode(false)
                        setSwipePopupActive(false)
                    }

                    getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    false
                }

                else -> {
                    false
                }
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    internal val shiftLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    shiftLongPressFired = false
                    getLongPressConsumedButtons().remove(view as Button)
                    val runnable = getButtonLongPressRunnables()[view as Button] ?: return@OnTouchListener false
                    getButtonPendingCallbacks()[view] = PendingCallbacks(handler, runnable)
                    handler.postDelayed(runnable, getLongPressDuration().durationMs)
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    val shouldConsume =
                        shiftLongPressFired ||
                            getLongPressConsumedButtons().contains(view)
                    shiftLongPressFired = false
                    shouldConsume
                }

                else -> {
                    false
                }
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    internal val symbolsLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val button = view as Button
                    getSymbolsLongPressFired().remove(button)
                    getLongPressConsumedButtons().remove(button)
                    val runnable = getButtonLongPressRunnables()[button] ?: return@OnTouchListener false
                    getButtonPendingCallbacks()[button] = PendingCallbacks(handler, runnable)
                    handler.postDelayed(runnable, getLongPressDuration().durationMs)
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val button = view as Button
                    getButtonPendingCallbacks().remove(button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    val longPressFired = getSymbolsLongPressFired().remove(button)
                    longPressFired
                }

                else -> {
                    false
                }
            }
        }

    @VisibleForTesting
    internal val backspaceLongClickListener =
        View.OnLongClickListener { _ ->
            performHaptic(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE))
            backspaceController?.start()
            true
        }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val backspaceTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    getHapticDownFiredButtons().add(view as Button)
                    performHaptic(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE))
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    backspaceController?.stop()
                    false
                }

                else -> {
                    false
                }
            }
        }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val commaLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    commaLongPressFired = false
                    getLongPressConsumedButtons().remove(view as Button)
                    val commaKey = view.getTag(R.id.key_data) as? KeyboardKey.Character
                    getHapticDownFiredButtons().add(view as Button)
                    if (commaKey != null) performHaptic(commaKey)
                    longPressStartX = event.rawX
                    longPressStartY = event.rawY
                    val runnable = getButtonLongPressRunnables()[view as Button] ?: return@OnTouchListener false
                    getButtonPendingCallbacks()[view] = PendingCallbacks(handler, runnable)
                    handler.postDelayed(runnable, getLongPressDuration().durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - longPressStartX
                    val dy = event.rawY - longPressStartY
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (distance > longPressCancelThresholdPx) {
                        getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }
                    }
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    getButtonPendingCallbacks().remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    val shouldConsume = commaLongPressFired
                    commaLongPressFired = false
                    shouldConsume
                }

                else -> {
                    false
                }
            }
        }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val punctuationTapHapticTouchListener =
        View.OnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val key = view.getTag(R.id.key_data) as? KeyboardKey.Character
                getHapticDownFiredButtons().add(view as Button)
                if (key != null) performHaptic(key)
            }
            false
        }

    @SuppressLint("ClickableViewAccessibility")
    fun attachListeners(button: Button, key: KeyboardKey) {
        button.apply {
            setOnClickListener(keyClickListener)

            if (key is KeyboardKey.Character &&
                (
                    key.type == KeyboardKey.KeyType.LETTER ||
                        key.type == KeyboardKey.KeyType.NUMBER ||
                        key.type == KeyboardKey.KeyType.SYMBOL
                    )
            ) {
                setOnTouchListener(characterLongPressTouchListener)
            }

            if (key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.PUNCTUATION) {
                if (getLongPressPunctuationMode() == LongPressPunctuationMode.PERIOD && key.value == ".") {
                    setOnTouchListener(punctuationLongPressTouchListener)
                } else if (key.value == ",") {
                    setOnTouchListener(commaLongPressTouchListener)
                } else {
                    setOnTouchListener(punctuationTapHapticTouchListener)
                }
            }

            if (key is KeyboardKey.Action &&
                key.action == KeyboardKey.ActionType.SPACE &&
                getLongPressPunctuationMode() == LongPressPunctuationMode.SPACEBAR
            ) {
                setOnTouchListener(spaceLongPressTouchListener)
            }

            if (key is KeyboardKey.Action &&
                key.action == KeyboardKey.ActionType.SHIFT &&
                !getShowLanguageSwitchKey() &&
                getActiveLanguages().size > 1
            ) {
                setOnTouchListener(shiftLongPressTouchListener)
            }

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS) {
                setOnTouchListener(symbolsLongPressTouchListener)
            }

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.BACKSPACE) {
                setOnLongClickListener(backspaceLongClickListener)
                setOnTouchListener(backspaceTouchListener)
                isHapticFeedbackEnabled = false
            }
        }
    }
}
