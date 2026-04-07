@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.TextViewCompat
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.settings.KeyLabelSize
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.LongPressPunctuationMode
import com.urik.keyboard.settings.SpaceBarSize
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingCallbacks(val handler: Handler, val runnable: Runnable)

class KeyboardLayoutManager(
    private val context: Context,
    private val onKeyClick: (KeyboardKey) -> Unit,
    private val onAcceleratedDeletionChanged: (Boolean) -> Unit,
    private val onSymbolsLongPress: () -> Unit,
    private val onLanguageSwitch: (String) -> Unit = {},
    private val onShowInputMethodPicker: () -> Unit = {},
    private val characterVariationService: CharacterVariationService,
    private val languageManager: LanguageManager,
    private val themeManager: ThemeManager,
    cacheMemoryManager: CacheMemoryManager
) {
    private var clipboardEnabled = false
    private var activeLanguages: List<String> = emptyList()
    private var showLanguageSwitchKey = false
    private var showNumberHints = false
    private var hasMultipleImes = false
    private var pressHighlightEnabled = true

    var effectiveLayout: KeyboardLayout? = null
        private set

    @Volatile
    private var customKeyMappings: Map<String, String> = emptyMap()
    private val keyHintRenderer = KeyHintRenderer(context)

    private val vibrator =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    private val supportsAmplitudeControl = vibrator?.hasAmplitudeControl() == true
    private val vibrationAttributes =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH)
        } else {
            null
        }
    private var hapticEnabled = true
    private var hapticAmplitude = 170

    var onDeleteWord: (() -> Unit)? = null

    @VisibleForTesting
    internal var onHapticFired: ((KeyboardKey?) -> Unit)? = null

    private var shiftLongPressFired = false

    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    private var currentLongPressDuration = LongPressDuration.MEDIUM
    private var currentKeySize = KeySize.MEDIUM
    private var currentSpaceBarSize = SpaceBarSize.STANDARD
    private var currentKeyLabelSize = KeyLabelSize.MEDIUM
    private var longPressPunctuationMode = LongPressPunctuationMode.PERIOD
    private var splitGapPx = 0
    private var adaptiveDimensions: AdaptiveDimensions? = null

    private var activePunctuationPopup: CharacterVariationPopup? = null
    private var popupSelectionMode = false
    private var swipeKeyboardView: SwipeKeyboardView? = null
    private var spaceGestureStartX = 0f
    private var spaceGestureStartY = 0f

    private var backgroundJob = SupervisorJob()
    private var backgroundScope = CoroutineScope(Dispatchers.IO + backgroundJob)

    private val buttonPool = mutableListOf<Button>()
    private val activeButtons = mutableSetOf<Button>()
    private val buttonPendingCallbacks = ConcurrentHashMap<Button, PendingCallbacks>()
    private val buttonLongPressRunnables = HashMap<Button, Runnable>()
    private val symbolsLongPressFired = ConcurrentHashMap.newKeySet<Button>()
    private val customMappingLongPressFired = ConcurrentHashMap.newKeySet<Button>()
    private val characterLongPressFired = ConcurrentHashMap.newKeySet<Button>()
    private val longPressConsumedButtons = ConcurrentHashMap.newKeySet<Button>()
    private val hapticDownFiredButtons = ConcurrentHashMap.newKeySet<Button>()

    private val cachedTextSizes = mutableMapOf<Int, Float>()
    private val cachedDimensions = mutableMapOf<String, Int>()
    private var cacheValid = false

    private var cachedCornerRadius = 0f
    private var cachedStrokeWidth = 0
    private var cachedStrokeWidthThick = 0

    private val sharedHandler = Handler(Looper.getMainLooper())

    private val backspaceController = BackspaceController(
        onBackspaceKey = { onKeyClick(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)) },
        onAcceleratedDeletionChanged = onAcceleratedDeletionChanged,
        vibrateEffect = ::vibrateEffect,
        cancelVibration = { vibrator?.cancel() },
        getHapticEnabled = { hapticEnabled },
        getHapticAmplitude = { hapticAmplitude },
        getSupportsAmplitudeControl = { supportsAmplitudeControl },
        getBackgroundScope = { backgroundScope }
    )

    private var variationPopup: CharacterVariationPopup? = null
    private var currentVariationKeyType: KeyboardKey.KeyType? = null
    private var languagePickerPopup: LanguagePickerPopup? = null
    private var lastKeyboardState: KeyboardState = KeyboardState()

    private val characterVariationCallback: (String) -> Unit = { selectedChar ->
        popupSelectionMode = false
        swipeKeyboardView?.setPopupActive(false)

        val keyType = currentVariationKeyType ?: KeyboardKey.KeyType.LETTER
        val selectedKey = KeyboardKey.Character(selectedChar, keyType)
        performContextualHaptic(selectedKey)
        onKeyClick(selectedKey)
    }

    private val punctuationVariationCallback: (String) -> Unit = { selectedPunctuation ->
        activePunctuationPopup = null
        popupSelectionMode = false
        swipeKeyboardView?.setPopupActive(false)

        val punctuationKey = KeyboardKey.Character(selectedPunctuation, KeyboardKey.KeyType.PUNCTUATION)
        performContextualHaptic(punctuationKey)
        onKeyClick(punctuationKey)
    }

    @VisibleForTesting
    internal val keyClickListener =
        View.OnClickListener { view ->
            if (longPressConsumedButtons.remove(view as? Button)) return@OnClickListener

            val key = view.getTag(R.id.key_data) as? KeyboardKey ?: return@OnClickListener
            val skipHaptic = hapticDownFiredButtons.remove(view as? Button)
            if (!skipHaptic) {
                performContextualHaptic(key)
            }

            if (accessibilityManager.isEnabled) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
                event.contentDescription = view.contentDescription
                accessibilityManager.sendAccessibilityEvent(event)
            }

            onKeyClick(key)
        }

    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private val longPressCancelThresholdPx = 20f

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val characterLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val key = view.getTag(R.id.key_data) as? KeyboardKey.Character ?: return@OnTouchListener false
                    val button = view as Button
                    characterLongPressFired.remove(button)
                    customMappingLongPressFired.remove(button)
                    longPressConsumedButtons.remove(button)
                    hapticDownFiredButtons.add(button)
                    performContextualHaptic(key)
                    longPressStartX = event.rawX
                    longPressStartY = event.rawY
                    val runnable = buttonLongPressRunnables[button] ?: return@OnTouchListener false
                    buttonPendingCallbacks[button] = PendingCallbacks(sharedHandler, runnable)
                    sharedHandler.postDelayed(runnable, currentLongPressDuration.durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val button = view as Button
                    if (popupSelectionMode && variationPopup?.isShowing == true) {
                        val previousChar = variationPopup?.getHighlightedCharacter()
                        val char = variationPopup?.getCharacterAt(event.rawX, event.rawY)
                        variationPopup?.setHighlighted(char)
                        if (char != null && char != previousChar) {
                            performContextualHaptic(null)
                        }
                        return@OnTouchListener true
                    }
                    if (characterLongPressFired.contains(button)) {
                        return@OnTouchListener true
                    }
                    val dx = event.rawX - longPressStartX
                    val dy = event.rawY - longPressStartY
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (distance > longPressCancelThresholdPx) {
                        buttonPendingCallbacks.remove(button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    val button = view as Button
                    buttonPendingCallbacks.remove(button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }

                    val longPressConsumed =
                        characterLongPressFired.remove(button) ||
                            longPressConsumedButtons.contains(button)

                    if (popupSelectionMode && variationPopup?.isShowing == true) {
                        val selectedChar = variationPopup?.getHighlightedCharacter()
                        if (selectedChar != null) {
                            variationPopup?.dismiss()
                            variationPopup = null
                            popupSelectionMode = false
                            swipeKeyboardView?.setPopupActive(false)

                            val keyType = currentVariationKeyType ?: KeyboardKey.KeyType.LETTER
                            val selectedKey = KeyboardKey.Character(selectedChar, keyType)
                            performContextualHaptic(selectedKey)
                            onKeyClick(selectedKey)
                        } else {
                            popupSelectionMode = false
                            swipeKeyboardView?.setPopupActive(false)
                        }
                        customMappingLongPressFired.remove(button)
                        return@OnTouchListener true
                    }

                    customMappingLongPressFired.remove(button)
                    longPressConsumed
                }

                MotionEvent.ACTION_CANCEL -> {
                    val button = view as Button
                    buttonPendingCallbacks.remove(button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }

                    val longPressConsumed =
                        characterLongPressFired.remove(button) ||
                            longPressConsumedButtons.contains(button)

                    if (popupSelectionMode && variationPopup?.isShowing == true) {
                        variationPopup?.dismiss()
                        variationPopup = null
                        popupSelectionMode = false
                        swipeKeyboardView?.setPopupActive(false)
                    }

                    customMappingLongPressFired.remove(button)
                    longPressConsumed
                }

                else -> {
                    false
                }
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    private val spaceLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    popupSelectionMode = false
                    longPressConsumedButtons.remove(view as Button)
                    spaceGestureStartX = event.x
                    spaceGestureStartY = event.y
                    val runnable = buttonLongPressRunnables[view as Button] ?: return@OnTouchListener false
                    buttonPendingCallbacks[view] = PendingCallbacks(sharedHandler, runnable)
                    sharedHandler.postDelayed(runnable, currentLongPressDuration.durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (popupSelectionMode && activePunctuationPopup != null) {
                        val char = activePunctuationPopup?.getCharacterAt(event.rawX, event.rawY)
                        activePunctuationPopup?.setHighlighted(char)
                        return@OnTouchListener true
                    }

                    if (!popupSelectionMode) {
                        val dx = event.x - spaceGestureStartX
                        val dy = event.y - spaceGestureStartY
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (distance > 20f) {
                            buttonPendingCallbacks.remove(view as Button)?.let { pending ->
                                pending.handler.removeCallbacks(pending.runnable)
                            }
                        }
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    if (popupSelectionMode && activePunctuationPopup != null) {
                        val selectedChar = activePunctuationPopup?.getHighlightedCharacter()

                        buttonPendingCallbacks.remove(view as Button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }

                        if (selectedChar != null) {
                            activePunctuationPopup?.dismiss()
                            activePunctuationPopup = null
                            popupSelectionMode = false
                            swipeKeyboardView?.setPopupActive(false)

                            val punctuationKey = KeyboardKey.Character(selectedChar, KeyboardKey.KeyType.PUNCTUATION)
                            performContextualHaptic(punctuationKey)
                            onKeyClick(punctuationKey)
                        } else {
                            activePunctuationPopup?.setHighlighted(null)
                        }

                        return@OnTouchListener true
                    }

                    buttonPendingCallbacks.remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (popupSelectionMode && activePunctuationPopup != null) {
                        activePunctuationPopup?.dismiss()
                        activePunctuationPopup = null
                        popupSelectionMode = false
                        swipeKeyboardView?.setPopupActive(false)
                    }

                    buttonPendingCallbacks.remove(view as Button)?.let { pending ->
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
                    popupSelectionMode = false
                    longPressConsumedButtons.remove(view as Button)
                    val key = view.getTag(R.id.key_data) as? KeyboardKey.Character ?: return@OnTouchListener false
                    hapticDownFiredButtons.add(view as Button)
                    performContextualHaptic(key)
                    val runnable = buttonLongPressRunnables[view as Button] ?: return@OnTouchListener false
                    buttonPendingCallbacks[view] = PendingCallbacks(sharedHandler, runnable)
                    sharedHandler.postDelayed(runnable, currentLongPressDuration.durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (popupSelectionMode && activePunctuationPopup != null) {
                        val char = activePunctuationPopup?.getCharacterAt(event.rawX, event.rawY)
                        activePunctuationPopup?.setHighlighted(char)
                        return@OnTouchListener true
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    if (popupSelectionMode && activePunctuationPopup != null) {
                        val selectedChar = activePunctuationPopup?.getHighlightedCharacter()

                        buttonPendingCallbacks.remove(view as Button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }

                        if (selectedChar != null) {
                            activePunctuationPopup?.dismiss()
                            activePunctuationPopup = null
                            popupSelectionMode = false
                            swipeKeyboardView?.setPopupActive(false)

                            val punctuationKey = KeyboardKey.Character(selectedChar, KeyboardKey.KeyType.PUNCTUATION)
                            performContextualHaptic(punctuationKey)
                            onKeyClick(punctuationKey)
                        } else {
                            activePunctuationPopup?.setHighlighted(null)
                        }

                        return@OnTouchListener true
                    }

                    buttonPendingCallbacks.remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (popupSelectionMode && activePunctuationPopup != null) {
                        activePunctuationPopup?.dismiss()
                        activePunctuationPopup = null
                        popupSelectionMode = false
                        swipeKeyboardView?.setPopupActive(false)
                    }

                    buttonPendingCallbacks.remove(view as Button)?.let { pending ->
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
    private val shiftLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    shiftLongPressFired = false
                    longPressConsumedButtons.remove(view as Button)
                    val runnable = buttonLongPressRunnables[view as Button] ?: return@OnTouchListener false
                    buttonPendingCallbacks[view] = PendingCallbacks(sharedHandler, runnable)
                    sharedHandler.postDelayed(runnable, currentLongPressDuration.durationMs)
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttonPendingCallbacks.remove(view as Button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    val shouldConsume =
                        shiftLongPressFired ||
                            longPressConsumedButtons.contains(view)
                    shiftLongPressFired = false
                    shouldConsume
                }

                else -> {
                    false
                }
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    private val symbolsLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val button = view as Button
                    symbolsLongPressFired.remove(button)
                    longPressConsumedButtons.remove(button)
                    val runnable = buttonLongPressRunnables[button] ?: return@OnTouchListener false
                    buttonPendingCallbacks[button] = PendingCallbacks(sharedHandler, runnable)
                    sharedHandler.postDelayed(runnable, currentLongPressDuration.durationMs)
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val button = view as Button
                    buttonPendingCallbacks.remove(button)?.let { pending ->
                        pending.handler.removeCallbacks(pending.runnable)
                    }
                    val longPressFired = symbolsLongPressFired.remove(button)
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
            performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE))
            backspaceController.start()
            true
        }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val backspaceTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hapticDownFiredButtons.add(view as Button)
                    performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE))
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    backspaceController.stop()
                    false
                }

                else -> {
                    false
                }
            }
        }

    private var commaLongPressFired = false

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal val commaLongPressTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    commaLongPressFired = false
                    longPressConsumedButtons.remove(view as Button)
                    val commaKey = view.getTag(R.id.key_data) as? KeyboardKey.Character
                    hapticDownFiredButtons.add(view as Button)
                    if (commaKey != null) performContextualHaptic(commaKey)
                    longPressStartX = event.rawX
                    longPressStartY = event.rawY
                    val runnable = buttonLongPressRunnables[view as Button] ?: return@OnTouchListener false
                    buttonPendingCallbacks[view] = PendingCallbacks(sharedHandler, runnable)
                    sharedHandler.postDelayed(runnable, currentLongPressDuration.durationMs)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - longPressStartX
                    val dy = event.rawY - longPressStartY
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (distance > longPressCancelThresholdPx) {
                        buttonPendingCallbacks.remove(view as Button)?.let { pending ->
                            pending.handler.removeCallbacks(pending.runnable)
                        }
                    }
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttonPendingCallbacks.remove(view as Button)?.let { pending ->
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
                hapticDownFiredButtons.add(view as Button)
                if (key != null) performContextualHaptic(key)
            }
            false
        }

    private val punctuationLoader = PunctuationLoader(context, cacheMemoryManager)

    fun updateAdaptiveDimensions(dimensions: AdaptiveDimensions) {
        adaptiveDimensions = dimensions
        splitGapPx = dimensions.splitGapPx
        invalidateCalculationCache()
    }

    fun updateScriptContext() {
        invalidateCalculationCache()
    }

    fun triggerHapticFeedback() {
        performContextualHaptic(null)
    }

    fun triggerBackspaceHaptic() {
        if (!hapticEnabled || hapticAmplitude == 0) return
        val amplitude = if (supportsAmplitudeControl) hapticAmplitude else android.os.VibrationEffect.DEFAULT_AMPLITUDE
        val effect = HapticSignature.BackspaceChirp.createEffect(amplitude)
        vibrateEffect(effect)
    }

    fun stopAcceleratedBackspace() {
        backspaceController.stop()
    }

    fun cancelAllPendingCallbacks() {
        buttonPendingCallbacks.values.forEach { pending ->
            pending.handler.removeCallbacks(pending.runnable)
        }
        buttonPendingCallbacks.clear()
    }

    fun dismissVariationPopup() {
        variationPopup?.dismiss()
        variationPopup = null
    }

    fun updateLongPressDuration(duration: LongPressDuration) {
        currentLongPressDuration = duration
    }

    fun updateLongPressPunctuationMode(mode: LongPressPunctuationMode) {
        longPressPunctuationMode = mode
    }

    fun setSwipeKeyboardView(view: SwipeKeyboardView) {
        swipeKeyboardView = view
    }

    fun updateKeySize(keySize: KeySize) {
        if (currentKeySize != keySize) {
            currentKeySize = keySize
            invalidateCalculationCache()
        }
    }

    fun updateSpaceBarSize(spaceBarSize: SpaceBarSize) {
        currentSpaceBarSize = spaceBarSize
    }

    fun updateSplitGapPx(gapPx: Int) {
        splitGapPx = gapPx
    }

    fun updateKeyLabelSize(keyLabelSize: KeyLabelSize) {
        if (currentKeyLabelSize != keyLabelSize) {
            currentKeyLabelSize = keyLabelSize
            invalidateCalculationCache()
        }
    }

    fun onDensityChanged() {
        invalidateCalculationCache()
    }

    fun updateHapticSettings(enabled: Boolean, amplitude: Int) {
        hapticEnabled = enabled
        hapticAmplitude = amplitude
    }

    fun updateClipboardEnabled(enabled: Boolean) {
        clipboardEnabled = enabled
    }

    fun updateHasMultipleImes(hasMultiple: Boolean) {
        hasMultipleImes = hasMultiple
    }

    fun updateActiveLanguages(languages: List<String>) {
        activeLanguages = languages
    }

    fun updateShowLanguageSwitchKey(enabled: Boolean) {
        showLanguageSwitchKey = enabled
    }

    fun updateNumberHints(enabled: Boolean) {
        showNumberHints = enabled
    }

    fun updatePressHighlight(enabled: Boolean) {
        pressHighlightEnabled = enabled
    }

    fun updateCustomKeyMappings(mappings: Map<String, String>) {
        customKeyMappings = mappings
    }

    private fun vibrateEffect(effect: android.os.VibrationEffect) {
        val v = vibrator ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                vibrationAttributes != null
            ) {
                v.vibrate(effect, vibrationAttributes)
            } else {
                v.vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }

    private fun performContextualHaptic(key: KeyboardKey?) {
        onHapticFired?.invoke(key)
        if (!hapticEnabled || hapticAmplitude == 0) return

        try {
            val signature =
                when (key) {
                    is KeyboardKey.Character -> {
                        when (key.type) {
                            KeyboardKey.KeyType.LETTER -> HapticSignature.LetterClick
                            KeyboardKey.KeyType.PUNCTUATION -> HapticSignature.PunctuationTick
                            KeyboardKey.KeyType.NUMBER -> HapticSignature.NumberClick
                            KeyboardKey.KeyType.SYMBOL -> HapticSignature.PunctuationTick
                        }
                    }

                    is KeyboardKey.Action -> {
                        when (key.action) {
                            KeyboardKey.ActionType.SPACE -> HapticSignature.SpaceThump
                            KeyboardKey.ActionType.BACKSPACE -> HapticSignature.BackspaceChirp
                            KeyboardKey.ActionType.SHIFT -> HapticSignature.ShiftPulse
                            KeyboardKey.ActionType.ENTER -> HapticSignature.EnterCompletion
                            else -> HapticSignature.LetterClick
                        }
                    }

                    KeyboardKey.Spacer -> {
                        return
                    }

                    null -> {
                        HapticSignature.LetterClick
                    }
                }

            val amplitude = if (supportsAmplitudeControl) {
                hapticAmplitude
            } else {
                android.os.VibrationEffect.DEFAULT_AMPLITUDE
            }
            val effect = signature.createEffect(amplitude)
            vibrateEffect(effect)
        } catch (_: Exception) {
        }
    }

    private fun invalidateCalculationCache() {
        cachedTextSizes.clear()
        cachedDimensions.clear()
        cacheValid = false
    }

    private fun ensureCacheValid() {
        if (cacheValid) return

        val dims = adaptiveDimensions
        if (dims != null) {
            cachedDimensions["minTarget"] = dims.minimumTouchTargetPx
            cachedDimensions["keyHeight"] = dims.keyHeightPx
            cachedDimensions["horizontalPadding"] = dims.keyMarginHorizontalPx
            cachedDimensions["verticalPadding"] =
                (dims.keyMarginHorizontalPx * 0.5f * currentKeySize.scaleFactor).toInt()
            cachedDimensions["horizontalMargin"] = dims.keyMarginHorizontalPx
            cachedDimensions["rowVerticalMargin"] = dims.keyMarginVerticalPx
            cachedDimensions["numberRowGutter"] = dims.numberRowGutterPx
        } else {
            val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
            val baseMinTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
            val baseKeyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
            val baseHorizontalMargin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)

            val keySizeMultiplier = currentKeySize.scaleFactor

            cachedDimensions["minTarget"] = (baseMinTouchTarget * keySizeMultiplier).toInt()
            cachedDimensions["keyHeight"] = (baseKeyHeight * keySizeMultiplier).toInt()
            cachedDimensions["horizontalPadding"] = basePadding
            cachedDimensions["verticalPadding"] = (basePadding * 0.5f * keySizeMultiplier).toInt()
            cachedDimensions["horizontalMargin"] = baseHorizontalMargin
            cachedDimensions["rowVerticalMargin"] =
                context.resources.getDimensionPixelSize(R.dimen.key_margin_vertical)
            cachedDimensions["numberRowGutter"] = context.resources.getDimensionPixelSize(R.dimen.number_row_gutter)
        }

        val density = context.resources.displayMetrics.density
        cachedCornerRadius = 8f * density
        cachedStrokeWidth = (1 * density).toInt()
        cachedStrokeWidthThick = (2 * density).toInt()
        cacheValid = true
    }

    private fun getCachedTextSize(keyHeight: Int): Float = cachedTextSizes.getOrPut(keyHeight) {
        val dims = adaptiveDimensions
        val ratio = dims?.keyTextBaseRatio ?: 0.38f
        val minSize = dims?.keyTextMinSp ?: 12f
        val maxSize = dims?.keyTextMaxSp ?: when (currentKeySize) {
            KeySize.EXTRA_LARGE -> 16f
            else -> 24f
        }
        val baseTextSize = keyHeight * ratio / context.resources.displayMetrics.density
        val adjusted = baseTextSize.coerceIn(minSize, maxSize)
        adjusted * currentKeyLabelSize.scaleFactor
    }

    fun createKeyboardView(layout: KeyboardLayout, state: KeyboardState): View {
        lastKeyboardState = state
        returnActiveButtonsToPool()

        val processedRows =
            layout.rows.map { row ->
                if (shouldInjectGlobeButton(row)) {
                    injectGlobeButton(row)
                } else {
                    row
                }
            }

        effectiveLayout =
            KeyboardLayout(
                mode = layout.mode,
                rows = processedRows,
                isRTL = layout.isRTL,
                script = layout.script
            )

        val keyboardContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                val dims = adaptiveDimensions
                val horizontalPadding = dims?.keyboardPaddingHorizontalPx
                    ?: context.resources.getDimensionPixelSize(R.dimen.keyboard_padding)
                val verticalPadding = dims?.keyboardPaddingVerticalPx
                    ?: context.resources.getDimensionPixelSize(R.dimen.keyboard_padding_vertical)

                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                setBackgroundColor(themeManager.currentTheme.value.colors.keyboardBackground)

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = context.getString(R.string.keyboard_description)
            }

        processedRows.forEachIndexed { index, row ->
            val hasNumberRowGutter = index == 0 && isTopNumberRow(row) && processedRows.size > 1
            val rowView = createRowView(row, state, hasNumberRowGutter)
            keyboardContainer.addView(rowView)
        }

        return keyboardContainer
    }

    private fun shouldInjectGlobeButton(row: List<KeyboardKey>): Boolean = showLanguageSwitchKey &&
        activeLanguages.size > 1 &&
        row.any { it is KeyboardKey.Action && it.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS }

    private fun injectGlobeButton(row: List<KeyboardKey>): List<KeyboardKey> = row.flatMap { key ->
        if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS) {
            listOf(key, KeyboardKey.Action(KeyboardKey.ActionType.LANGUAGE_SWITCH))
        } else {
            listOf(key)
        }
    }

    private fun createRowView(
        keys: List<KeyboardKey>,
        state: KeyboardState,
        hasNumberRowGutter: Boolean = false
    ): LinearLayout {
        val is9LetterRow = is9CharacterLetterRow(keys)
        val shouldSplit = splitGapPx > 0 && !containsSpacebar(keys)

        val rowLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            ensureCacheValid()
                            val verticalMargin = cachedDimensions["rowVerticalMargin"]
                                ?: context.resources.getDimensionPixelSize(R.dimen.key_margin_vertical)
                            val gutterMargin =
                                if (hasNumberRowGutter) {
                                    cachedDimensions["numberRowGutter"]!!
                                } else {
                                    0
                                }
                            setMargins(0, 0, 0, verticalMargin + gutterMargin)
                        }

                isBaselineAligned = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

        if (shouldSplit) {
            val midpoint = keys.size / 2
            val shouldDuplicateMiddle = keys.size % 2 == 1

            val leftKeys =
                if (shouldDuplicateMiddle) {
                    keys.subList(0, midpoint + 1)
                } else {
                    keys.subList(0, midpoint)
                }
            val rightKeys = keys.subList(midpoint, keys.size)

            val leftContainer = createHalfRowContainer(leftKeys, state)
            val gapSpacer =
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(splitGapPx, LinearLayout.LayoutParams.MATCH_PARENT)
                }
            val rightContainer = createHalfRowContainer(rightKeys, state)

            rowLayout.addView(leftContainer)
            rowLayout.addView(gapSpacer)
            rowLayout.addView(rightContainer)
        } else {
            if (is9LetterRow && splitGapPx == 0) {
                val spacer =
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 0.5f)
                    }
                rowLayout.addView(spacer)
            }

            keys.forEach { key ->
                if (key is KeyboardKey.Spacer) {
                    val spacer =
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, STANDARD_KEY_WEIGHT)
                        }
                    rowLayout.addView(spacer)
                } else {
                    val keyButton = getOrCreateKeyButton(key, state, keys)
                    rowLayout.addView(keyButton)
                }
            }

            if (is9LetterRow && splitGapPx == 0) {
                val spacer =
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 0.5f)
                    }
                rowLayout.addView(spacer)
            }
        }

        return rowLayout
    }

    private fun createHalfRowContainer(keys: List<KeyboardKey>, state: KeyboardState): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isBaselineAligned = false

            keys.forEach { key ->
                if (key is KeyboardKey.Spacer) {
                    val spacer =
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, STANDARD_KEY_WEIGHT)
                        }
                    addView(spacer)
                } else {
                    val keyButton = getOrCreateKeyButton(key, state, keys)
                    addView(keyButton)
                }
            }
        }

    private fun containsSpacebar(keys: List<KeyboardKey>): Boolean =
        keys.any { it is KeyboardKey.Action && it.action == KeyboardKey.ActionType.SPACE }

    private fun is9CharacterLetterRow(rowKeys: List<KeyboardKey>): Boolean {
        val nonSpacerKeys = rowKeys.filter { it !is KeyboardKey.Spacer }
        if (nonSpacerKeys.size != 9) return false

        return nonSpacerKeys.all { key ->
            key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.LETTER
        }
    }

    private fun getOrCreateKeyButton(key: KeyboardKey, state: KeyboardState, rowKeys: List<KeyboardKey>): Button {
        val button =
            if (buttonPool.isNotEmpty()) {
                buttonPool.removeAt(buttonPool.size - 1).apply {
                    isPressed = false
                }
            } else {
                Button(context)
            }

        configureButton(button, key, state, rowKeys)
        activeButtons.add(button)

        return button
    }

    private fun preallocateLongPressRunnable(button: Button, key: KeyboardKey) {
        when {
            key is KeyboardKey.Character &&
                (
                    key.type == KeyboardKey.KeyType.LETTER ||
                        key.type == KeyboardKey.KeyType.NUMBER ||
                        key.type == KeyboardKey.KeyType.SYMBOL
                    ) -> buttonLongPressRunnables[button] = Runnable {
                characterLongPressFired.add(button)
                longPressConsumedButtons.add(button)
                performContextualHaptic(key)
                handleCharacterLongPress(key, button, button)
            }

            key is KeyboardKey.Character &&
                key.type == KeyboardKey.KeyType.PUNCTUATION &&
                longPressPunctuationMode == LongPressPunctuationMode.PERIOD &&
                key.value == "." ->
                buttonLongPressRunnables[button] = Runnable {
                    longPressConsumedButtons.add(button)
                    performContextualHaptic(key)
                    handlePunctuationLongPress(key, button)
                }

            key is KeyboardKey.Character && key.value == "," ->
                buttonLongPressRunnables[button] = Runnable {
                    commaLongPressFired = true
                    longPressConsumedButtons.add(button)
                    button.isPressed = false
                    performContextualHaptic(KeyboardKey.Character(",", KeyboardKey.KeyType.PUNCTUATION))
                    onShowInputMethodPicker()
                }

            key is KeyboardKey.Action &&
                key.action == KeyboardKey.ActionType.SPACE &&
                longPressPunctuationMode == LongPressPunctuationMode.SPACEBAR ->
                buttonLongPressRunnables[button] = Runnable {
                    longPressConsumedButtons.add(button)
                    performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
                    handleSpaceLongPress(button)
                }

            key is KeyboardKey.Action &&
                key.action == KeyboardKey.ActionType.SHIFT &&
                !showLanguageSwitchKey &&
                activeLanguages.size > 1 ->
                buttonLongPressRunnables[button] = Runnable {
                    shiftLongPressFired = true
                    longPressConsumedButtons.add(button)
                    performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SHIFT))
                    handleShiftLongPress(button)
                }

            key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS ->
                buttonLongPressRunnables[button] = Runnable {
                    symbolsLongPressFired.add(button)
                    longPressConsumedButtons.add(button)
                    button.isPressed = false
                    performContextualHaptic(null)
                    onSymbolsLongPress()
                }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureButton(button: Button, key: KeyboardKey, state: KeyboardState, rowKeys: List<KeyboardKey>) {
        ensureCacheValid()

        button.apply {
            setOnClickListener(null)
            setOnLongClickListener(null)
            setOnTouchListener(null)

            val minTarget = cachedDimensions["minTarget"]!!
            val keyHeight = cachedDimensions["keyHeight"]!!
            val gutterReduction = if (isTopNumberRow(rowKeys)) cachedDimensions["numberRowGutter"]!! else 0
            val adjustedKeyHeight = (keyHeight - gutterReduction).coerceAtLeast(keyHeight / 2)
            val adjustedMinTarget = (minTarget - gutterReduction).coerceAtLeast(minTarget / 2)
            val visualHeight = adjustedKeyHeight + 2
            val verticalMargin = ((adjustedMinTarget - visualHeight) / 2).coerceAtLeast(0)

            layoutParams =
                LinearLayout
                    .LayoutParams(
                        0,
                        visualHeight,
                        getKeyWeight(key, rowKeys)
                    ).apply {
                        val horizontalMargin = cachedDimensions["horizontalMargin"]!!
                        setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
                    }

            text = getKeyLabel(key, state)

            val finalTextSize = getCachedTextSize(adjustedKeyHeight)

            TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)

            setTextAppearance(
                when (key) {
                    is KeyboardKey.Action -> R.style.KeyTextAppearance_Action
                    else -> R.style.KeyTextAppearance
                }
            )

            setTextSize(TypedValue.COMPLEX_UNIT_SP, finalTextSize)
            maxLines = 1
            gravity = Gravity.CENTER

            typeface = Typeface.DEFAULT

            minHeight = 0
            minimumHeight = 0

            val horizontalPadding = cachedDimensions["horizontalPadding"]!!
            val verticalPadding = cachedDimensions["verticalPadding"]!!
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            if (key is KeyboardKey.Action &&
                key.action in
                setOf(
                    KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS,
                    KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY,
                    KeyboardKey.ActionType.MODE_SWITCH_LETTERS,
                    KeyboardKey.ActionType.MODE_SWITCH_NUMBERS
                )
            ) {
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,
                    finalTextSize.toInt(),
                    1,
                    TypedValue.COMPLEX_UNIT_SP
                )
            }

            val keyBackground = getKeyBackground(key)
            val supportsCustomMapping =
                key is KeyboardKey.Character &&
                    (key.type == KeyboardKey.KeyType.LETTER || key.type == KeyboardKey.KeyType.NUMBER)

            val isCommaWithMultipleImes =
                key is KeyboardKey.Character &&
                    key.value == "," &&
                    hasMultipleImes &&
                    rowKeys.any { it is KeyboardKey.Action && it.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS }

            background =
                if (isCommaWithMultipleImes) {
                    val keyboardIcon = ContextCompat.getDrawable(context, R.drawable.ic_keyboard)
                    keyboardIcon?.setTint(getKeyTextColor(key))
                    addBadgeOverlay(keyBackground, keyboardIcon)
                } else if (supportsCustomMapping && customKeyMappings[key.value.lowercase()] != null) {
                    val customSymbol = customKeyMappings[key.value.lowercase()]!!
                    keyHintRenderer.createKeyWithHint(
                        keyBackground,
                        customSymbol,
                        themeManager.currentTheme.value.colors
                    )
                } else if (isNumberHintRow(rowKeys)) {
                    val keyIndex = rowKeys.indexOf(key)
                    keyHintRenderer.createKeyWithHint(
                        keyBackground,
                        ((keyIndex + 1) % 10).toString(),
                        themeManager.currentTheme.value.colors
                    )
                } else {
                    keyBackground
                }
            setTextColor(getKeyTextColor(key))

            isActivated = getKeyActivatedState(key, state)
            isClickable = true
            isFocusable = true

            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getKeyContentDescription(key, state)

            ViewCompat.setAccessibilityDelegate(
                this,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        when (key) {
                            is KeyboardKey.Character -> {
                                info.roleDescription = context.getString(R.string.key_role)
                            }

                            is KeyboardKey.Action -> {
                                info.roleDescription = context.getString(R.string.action_key_role)
                                when (key.action) {
                                    KeyboardKey.ActionType.SHIFT -> {
                                        info.stateDescription = when {
                                            state.isCapsLockOn -> context.getString(R.string.state_on)
                                            state.isShiftPressed -> context.getString(R.string.state_active)
                                            else -> context.getString(R.string.state_inactive)
                                        }
                                    }

                                    KeyboardKey.ActionType.BACKSPACE -> {
                                        info.addAction(
                                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                                R.id.action_delete_word,
                                                context.getString(R.string.backspace_delete_word_action)
                                            )
                                        )
                                    }

                                    else -> {}
                                }
                            }

                            KeyboardKey.Spacer -> {}
                        }
                    }

                    override fun performAccessibilityAction(
                        host: View,
                        action: Int,
                        args: android.os.Bundle?
                    ): Boolean {
                        if (action == R.id.action_delete_word) {
                            onDeleteWord?.invoke()
                            return true
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            )

            setTag(R.id.key_data, key)
            setOnClickListener(keyClickListener)
            isHapticFeedbackEnabled = false

            if (key is KeyboardKey.Action) {
                val iconRes =
                    when (key.action) {
                        KeyboardKey.ActionType.SHIFT -> if (state.isCapsLockOn) {
                            R.drawable.shift_lock_48px
                        } else {
                            R.drawable.shift_48px
                        }

                        KeyboardKey.ActionType.SPACE -> R.drawable.space_bar_48px

                        KeyboardKey.ActionType.BACKSPACE -> R.drawable.backspace_48px

                        KeyboardKey.ActionType.ENTER -> R.drawable.keyboard_return_48px

                        KeyboardKey.ActionType.SEARCH -> R.drawable.search_48px

                        KeyboardKey.ActionType.SEND -> R.drawable.send_48px

                        KeyboardKey.ActionType.DONE -> R.drawable.done_48px

                        KeyboardKey.ActionType.GO -> R.drawable.arrow_forward_48px

                        KeyboardKey.ActionType.NEXT -> R.drawable.arrow_forward_48px

                        KeyboardKey.ActionType.PREVIOUS -> R.drawable.arrow_back_48px

                        else -> 0
                    }

                if (iconRes != 0) {
                    val keyBackground = getKeyBackground(key)
                    val iconDrawable = ContextCompat.getDrawable(context, iconRes)

                    iconDrawable?.setTint(getKeyTextColor(key))

                    val baseLayer = LayerDrawable(arrayOf(keyBackground, iconDrawable)).apply {
                        setLayerInset(1, 12, 12, 12, 12)
                        setLayerGravity(1, Gravity.CENTER)
                    }

                    background = if (key.action == KeyboardKey.ActionType.SHIFT &&
                        activeLanguages.size >= 2 &&
                        !showLanguageSwitchKey
                    ) {
                        val shortcode =
                            languageManager.currentLayoutLanguage.value
                                .take(2)
                                .uppercase(java.util.Locale.ROOT)
                        val langBadge = createLangBadgeDrawable(shortcode, getKeyTextColor(key))
                        addBadgeOverlay(baseLayer, langBadge)
                    } else {
                        baseLayer
                    }
                    text = ""
                } else if (key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS &&
                    clipboardEnabled &&
                    effectiveLayout?.mode == KeyboardMode.LETTERS
                ) {
                    val keyBackground = getKeyBackground(key)
                    val clipboardIcon = ContextCompat.getDrawable(context, R.drawable.ic_clipboard)
                    clipboardIcon?.setTint(getKeyTextColor(key))
                    background = addBadgeOverlay(keyBackground, clipboardIcon)
                } else {
                    background = getKeyBackground(key)
                }
            }

            preallocateLongPressRunnable(button, key)

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
                if (longPressPunctuationMode == LongPressPunctuationMode.PERIOD && key.value == ".") {
                    setOnTouchListener(punctuationLongPressTouchListener)
                } else if (key.value == ",") {
                    setOnTouchListener(commaLongPressTouchListener)
                } else {
                    setOnTouchListener(punctuationTapHapticTouchListener)
                }
            }

            if (key is KeyboardKey.Action &&
                key.action == KeyboardKey.ActionType.SPACE &&
                longPressPunctuationMode == LongPressPunctuationMode.SPACEBAR
            ) {
                setOnTouchListener(spaceLongPressTouchListener)
            }

            if (key is KeyboardKey.Action &&
                key.action == KeyboardKey.ActionType.SHIFT &&
                !showLanguageSwitchKey &&
                activeLanguages.size > 1
            ) {
                setOnTouchListener(shiftLongPressTouchListener)
            }

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS) {
                setOnTouchListener(symbolsLongPressTouchListener)
            }

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.LANGUAGE_SWITCH) {
                setOnClickListener {
                    val nextLang = languageManager.getNextLayoutLanguage()
                    onLanguageSwitch(nextLang)

                    val displayName =
                        com.urik.keyboard.settings.KeyboardSettings
                            .getLanguageDisplayNames()[nextLang] ?: nextLang
                    android.widget.Toast
                        .makeText(context, displayName, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                setOnLongClickListener {
                    performContextualHaptic(key)
                    if (activeLanguages.size > 1) {
                        showLanguagePickerPopup(this, activeLanguages)
                    }
                    true
                }
            }

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.BACKSPACE) {
                setOnLongClickListener(backspaceLongClickListener)
                setOnTouchListener(backspaceTouchListener)
                isHapticFeedbackEnabled = false
            }
        }
    }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal fun cleanupButton(button: Button) {
        buttonLongPressRunnables.remove(button)
        buttonPendingCallbacks.remove(button)?.let { pending ->
            pending.handler.removeCallbacks(pending.runnable)
        }
        symbolsLongPressFired.remove(button)
        customMappingLongPressFired.remove(button)
        characterLongPressFired.remove(button)
        hapticDownFiredButtons.remove(button)

        button.isHapticFeedbackEnabled = true
        button.isPressed = false
        button.setOnClickListener(null)
        button.setOnLongClickListener(null)
        button.setOnTouchListener(null)

        (button.parent as? ViewGroup)?.removeView(button)
    }

    private fun returnActiveButtonsToPool() {
        variationPopup?.dismiss()
        languagePickerPopup?.dismiss()

        activeButtons.forEach { button ->
            cleanupButton(button)

            if (buttonPool.size < MAX_BUTTON_POOL_SIZE) {
                buttonPool.add(button)
            }
        }
        activeButtons.clear()
    }

    private fun isBicameralScript(script: String): Boolean = when (script) {
        "Latn", "Cyrl", "Grek" -> true
        else -> false
    }

    @VisibleForTesting
    internal fun isNumberHintRow(row: List<KeyboardKey>): Boolean {
        if (!isNumberHintEnabled()) return false
        return effectiveLayout?.rows?.get(0)
            ?.let { firstRow ->
                !isTopNumberRow(firstRow) && row == firstRow
            } ?: false
    }

    @VisibleForTesting
    internal fun isNumberHintEnabled(): Boolean {
        if (!showNumberHints) return false
        return effectiveLayout?.rows?.get(0)
            ?.let { row ->
                row.count { key ->
                    key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.LETTER
                } == 10
            }
            ?: false
    }

    private fun getCurrentLocale(): java.util.Locale {
        val lang =
            languageManager.currentLayoutLanguage.value
                .split("-")
                .first()
        return java.util.Locale.forLanguageTag(lang)
    }

    private fun getKeyLabel(key: KeyboardKey, state: KeyboardState): String = when (key) {
        is KeyboardKey.Character -> {
            val script = effectiveLayout?.script ?: "Latn"
            when {
                key.type == KeyboardKey.KeyType.LETTER &&
                    isBicameralScript(script) &&
                    shouldCapitalize(state) -> {
                    if (key.value == "ß") {
                        "ẞ"
                    } else {
                        key.value.uppercase(getCurrentLocale())
                    }
                }

                else -> {
                    key.value
                }
            }
        }

        is KeyboardKey.Action -> {
            when (key.action) {
                KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> {
                    context.getString(R.string.letters_mode_label)
                }

                KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> {
                    context.getString(R.string.numbers_mode_label)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> {
                    context.getString(R.string.symbols_mode_label)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY -> {
                    context.getString(R.string.symbols_secondary_mode_label)
                }

                KeyboardKey.ActionType.LANGUAGE_SWITCH -> {
                    languageManager.currentLayoutLanguage.value
                        .take(
                            2
                        ).uppercase(java.util.Locale.ROOT)
                }

                else -> {
                    "?"
                }
            }
        }

        KeyboardKey.Spacer -> {
            ""
        }
    }

    private fun getKeyContentDescription(key: KeyboardKey, state: KeyboardState): String = when (key) {
        is KeyboardKey.Character -> {
            val script = effectiveLayout?.script ?: "Latn"
            val char =
                when {
                    key.type == KeyboardKey.KeyType.LETTER &&
                        isBicameralScript(script) &&
                        shouldCapitalize(state) -> {
                        if (key.value == "ß") {
                            "ẞ"
                        } else {
                            key.value.uppercase(getCurrentLocale())
                        }
                    }

                    else -> {
                        key.value
                    }
                }
            context.getString(R.string.key_character_description, char)
        }

        is KeyboardKey.Action -> {
            when (key.action) {
                KeyboardKey.ActionType.SHIFT -> {
                    when {
                        state.isCapsLockOn -> context.getString(R.string.caps_lock_on_description)
                        state.isShiftPressed -> context.getString(R.string.shift_active_description)
                        else -> context.getString(R.string.shift_key_description)
                    }
                }

                KeyboardKey.ActionType.BACKSPACE -> {
                    context.getString(R.string.backspace_key_description)
                }

                KeyboardKey.ActionType.SPACE -> {
                    context.getString(R.string.space_key_description)
                }

                KeyboardKey.ActionType.ENTER -> {
                    context.getString(R.string.action_enter_description)
                }

                KeyboardKey.ActionType.SEARCH -> {
                    context.getString(R.string.action_search_description)
                }

                KeyboardKey.ActionType.SEND -> {
                    context.getString(R.string.action_send_description)
                }

                KeyboardKey.ActionType.DONE -> {
                    context.getString(R.string.action_done_description)
                }

                KeyboardKey.ActionType.GO -> {
                    context.getString(R.string.action_go_description)
                }

                KeyboardKey.ActionType.NEXT -> {
                    context.getString(R.string.action_next_description)
                }

                KeyboardKey.ActionType.PREVIOUS -> {
                    context.getString(R.string.action_previous_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> {
                    context.getString(R.string.letters_mode_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> {
                    context.getString(R.string.numbers_mode_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> {
                    context.getString(R.string.symbols_mode_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY -> {
                    context.getString(R.string.symbols_secondary_mode_description)
                }

                KeyboardKey.ActionType.CAPS_LOCK -> {
                    context.getString(R.string.caps_lock_description)
                }

                KeyboardKey.ActionType.LANGUAGE_SWITCH -> {
                    context.getString(R.string.language_switch_description)
                }
            }
        }

        KeyboardKey.Spacer -> {
            ""
        }
    }

    private fun handleSpaceLongPress(view: View) {
        if (longPressPunctuationMode != LongPressPunctuationMode.SPACEBAR) {
            return
        }

        performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))

        val currentLayoutLang = languageManager.currentLayoutLanguage.value
        val languageCode = currentLayoutLang.split("-").first()

        backgroundScope.launch {
            try {
                val punctuation = punctuationLoader.loadPunctuation(languageCode)
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, punctuation)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, PunctuationLoader.DEFAULT_PUNCTUATION)
                }
            }
        }
    }

    private fun handlePunctuationLongPress(key: KeyboardKey.Character, view: View) {
        performContextualHaptic(key)

        val currentLayoutLang = languageManager.currentLayoutLanguage.value
        val languageCode = currentLayoutLang.split("-").first()

        backgroundScope.launch {
            try {
                val punctuation = punctuationLoader.loadPunctuation(languageCode)
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, punctuation)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, PunctuationLoader.DEFAULT_PUNCTUATION)
                }
            }
        }
    }

    private fun handleShiftLongPress(view: View) {
        if (activeLanguages.size <= 1) {
            return
        }
        if (showLanguageSwitchKey) {
            return
        }
        showLanguagePickerPopup(view, activeLanguages)
    }

    private fun showLanguagePickerPopup(anchorView: View, languages: List<String>) {
        languagePickerPopup?.dismiss()

        val popup = LanguagePickerPopup(context, themeManager)
        popup.setLanguages(
            languages = languages,
            currentLanguage = languageManager.currentLayoutLanguage.value,
            anchorView = anchorView,
            onSelected = { selectedLang ->
                popup.dismiss()
                onLanguageSwitch(selectedLang)

                val displayNames =
                    com.urik.keyboard.settings.KeyboardSettings
                        .getLanguageDisplayNames()
                val displayName = displayNames[selectedLang] ?: selectedLang
                android.widget.Toast
                    .makeText(context, displayName, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        )
        popup.showAboveAnchor()
        languagePickerPopup = popup
    }

    private fun showPunctuationPopup(anchorView: View, punctuationList: List<String>) {
        if (!anchorView.isAttachedToWindow || anchorView.windowToken == null) {
            return
        }

        variationPopup?.dismiss()
        languagePickerPopup?.dismiss()

        anchorView.isPressed = false

        val popup =
            CharacterVariationPopup(context, themeManager).apply {
                setCharacterVariations("", punctuationList, punctuationVariationCallback)
                showAboveAnchor(anchorView)
            }

        variationPopup = popup
        activePunctuationPopup = popup
        popupSelectionMode = true
        swipeKeyboardView?.setPopupActive(true)
    }

    private fun handleCharacterLongPress(key: KeyboardKey.Character, view: View, button: Button) {
        val customSymbol = customKeyMappings[key.value.lowercase()]
        if (customSymbol != null) {
            customMappingLongPressFired.add(button)
            view.isPressed = false
            val customKey = KeyboardKey.Character(customSymbol, KeyboardKey.KeyType.SYMBOL)
            performContextualHaptic(customKey)
            onKeyClick(customKey)
            return
        }

        val currentLayoutLang = languageManager.currentLayoutLanguage.value

        backgroundScope.launch {
            try {
                val firstRowLetter = if (isNumberHintEnabled() && key.type == KeyboardKey.KeyType.LETTER) {
                    val firstRow = effectiveLayout?.rows?.get(0)
                    if (firstRow?.contains(key) == true) {
                        firstRow
                    } else {
                        null
                    }
                } else {
                    null
                }

                var variations = characterVariationService.getVariations(key.value, currentLayoutLang)

                if (firstRowLetter != null) {
                    val number = (firstRowLetter.indexOf(key) + 1) % 10
                    variations = listOf(number.toString()) + variations
                }
                if (variations.isNotEmpty()) {
                    val casedVariations = applyCasingToVariations(variations)
                    withContext(Dispatchers.Main) {
                        showCharacterVariationPopup(key, view, casedVariations)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        view.isPressed = false
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    view.isPressed = false
                }
            }
        }
    }

    private fun showCharacterVariationPopup(key: KeyboardKey.Character, anchorView: View, variations: List<String>) {
        if (!anchorView.isAttachedToWindow || anchorView.windowToken == null) {
            return
        }

        variationPopup?.dismiss()
        languagePickerPopup?.dismiss()
        performContextualHaptic(key)

        anchorView.isPressed = false

        currentVariationKeyType = key.type

        variationPopup =
            CharacterVariationPopup(context, themeManager).apply {
                setCharacterVariations("", variations, characterVariationCallback)
                showAboveAnchor(anchorView)
            }

        popupSelectionMode = true
        swipeKeyboardView?.setPopupActive(true)
    }

    private fun getKeyWeight(key: KeyboardKey, rowKeys: List<KeyboardKey>): Float {
        val isNumberModeRow = isNumberModeRow(rowKeys)
        val characterKeyCount = rowKeys.count { it is KeyboardKey.Character }
        val isSplitMode = splitGapPx > 0 && !containsSpacebar(rowKeys)

        val baseWeight =
            if (isNumberModeRow || isSplitMode) {
                STANDARD_KEY_WEIGHT
            } else {
                when (key) {
                    is KeyboardKey.Character -> {
                        STANDARD_KEY_WEIGHT
                    }

                    is KeyboardKey.Action -> {
                        when (key.action) {
                            KeyboardKey.ActionType.SPACE -> {
                                return currentSpaceBarSize.widthMultiplier
                            }

                            KeyboardKey.ActionType.SHIFT,
                            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY
                            -> {
                                if (characterKeyCount >= 10) STANDARD_KEY_WEIGHT else SHIFT_KEY_WEIGHT
                            }

                            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> {
                                if (effectiveLayout?.mode == KeyboardMode.SYMBOLS_SECONDARY && characterKeyCount < 10) {
                                    SHIFT_KEY_WEIGHT
                                } else {
                                    STANDARD_KEY_WEIGHT
                                }
                            }

                            KeyboardKey.ActionType.BACKSPACE -> {
                                if (characterKeyCount >= 10) STANDARD_KEY_WEIGHT else BACKSPACE_KEY_WEIGHT
                            }

                            else -> {
                                STANDARD_KEY_WEIGHT
                            }
                        }
                    }

                    KeyboardKey.Spacer -> {
                        STANDARD_KEY_WEIGHT
                    }
                }
            }

        return baseWeight * currentKeySize.scaleFactor
    }

    private fun isNumberModeRow(rowKeys: List<KeyboardKey>): Boolean {
        if (rowKeys.size != 3) return false

        return rowKeys.all { key ->
            when (key) {
                is KeyboardKey.Character ->
                    key.type == KeyboardKey.KeyType.NUMBER ||
                        key.type == KeyboardKey.KeyType.PUNCTUATION

                is KeyboardKey.Action -> key.action == KeyboardKey.ActionType.BACKSPACE

                KeyboardKey.Spacer -> false
            }
        }
    }

    private fun isTopNumberRow(rowKeys: List<KeyboardKey>): Boolean {
        val characterKeys = rowKeys.filterIsInstance<KeyboardKey.Character>()
        return characterKeys.size == 10 && characterKeys.all { it.type == KeyboardKey.KeyType.NUMBER }
    }

    private fun shouldCapitalize(state: KeyboardState): Boolean = state.isShiftPressed || state.isCapsLockOn

    private fun applyCasingToVariations(variations: List<String>): List<String> {
        val script = effectiveLayout?.script ?: "Latn"
        if (!isBicameralScript(script) || !shouldCapitalize(lastKeyboardState)) {
            return variations
        }
        val locale = getCurrentLocale()
        return variations.map { it.uppercase(locale) }
    }

    private fun getKeyActivatedState(key: KeyboardKey, state: KeyboardState): Boolean = when (key) {
        is KeyboardKey.Action -> {
            when (key.action) {
                KeyboardKey.ActionType.SHIFT -> state.isShiftPressed && !state.isCapsLockOn
                KeyboardKey.ActionType.CAPS_LOCK -> state.isCapsLockOn
                else -> false
            }
        }

        else -> {
            false
        }
    }

    private fun getKeyBackground(key: KeyboardKey): Drawable {
        ensureCacheValid()
        val theme = themeManager.currentTheme.value

        val backgroundColor =
            when (key) {
                is KeyboardKey.Character -> {
                    theme.colors.keyBackgroundCharacter
                }

                is KeyboardKey.Action -> {
                    when (key.action) {
                        KeyboardKey.ActionType.SPACE -> theme.colors.keyBackgroundSpace
                        else -> theme.colors.keyBackgroundAction
                    }
                }

                KeyboardKey.Spacer -> {
                    android.graphics.Color.TRANSPARENT
                }
            }

        val normalDrawable =
            GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = cachedCornerRadius
                setStroke(cachedStrokeWidth, theme.colors.keyBorder)
            }

        val pressedDrawable =
            if (pressHighlightEnabled) {
                GradientDrawable().apply {
                    setColor(theme.colors.statePressed)
                    cornerRadius = cachedCornerRadius
                    setStroke(cachedStrokeWidth, theme.colors.keyBorderPressed)
                }
            } else {
                normalDrawable
            }

        val focusedDrawable =
            GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = cachedCornerRadius
                setStroke(cachedStrokeWidthThick, theme.colors.keyBorderFocused)
            }

        val activatedDrawable =
            GradientDrawable().apply {
                setColor(theme.colors.stateActivated)
                cornerRadius = cachedCornerRadius
                setStroke(cachedStrokeWidthThick, theme.colors.keyBorderFocused)
            }

        val stateListDrawable = StateListDrawable()
        stateListDrawable.addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
        stateListDrawable.addState(intArrayOf(android.R.attr.state_activated), activatedDrawable)
        stateListDrawable.addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
        stateListDrawable.addState(intArrayOf(), normalDrawable)

        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                if (pressHighlightEnabled) theme.colors.statePressed else android.graphics.Color.TRANSPARENT
            ),
            stateListDrawable,
            null
        )
    }

    private fun getKeyTextColor(key: KeyboardKey): Int {
        val colors = themeManager.currentTheme.value.colors
        return when (key) {
            is KeyboardKey.Character -> colors.keyTextCharacter
            is KeyboardKey.Action -> colors.keyTextAction
            KeyboardKey.Spacer -> android.graphics.Color.TRANSPARENT
        }
    }

    private fun addBadgeOverlay(base: Drawable, badge: Drawable?): Drawable {
        val density = context.resources.displayMetrics.density
        val iconSize = (10 * density).toInt()
        val leftInset = (5 * density).toInt()
        val topInset = (4 * density).toInt()

        return LayerDrawable(arrayOf(base, badge)).apply {
            setLayerSize(1, iconSize, iconSize)
            setLayerInset(1, leftInset, topInset, 0, 0)
            setLayerGravity(1, Gravity.TOP or Gravity.START)
        }
    }

    private fun createLangBadgeDrawable(text: String, color: Int): android.graphics.drawable.BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (10 * density).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = android.graphics.Canvas(bitmap)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = sizePx * 0.75f
                typeface = Typeface.DEFAULT_BOLD
            }
        canvas.drawText(text, sizePx / 2f, (sizePx - paint.descent() - paint.ascent()) / 2f, paint)
        return bitmap.toDrawable(context.resources)
    }

    fun cleanup() {
        backgroundJob.cancel()
        backgroundJob = SupervisorJob()
        backgroundScope = CoroutineScope(Dispatchers.IO + backgroundJob)
        effectiveLayout = null
        returnActiveButtonsToPool()
        buttonPool.clear()
        longPressConsumedButtons.clear()
        buttonPendingCallbacks.forEach { (_, pending) ->
            pending.handler.removeCallbacks(pending.runnable)
        }
        buttonPendingCallbacks.clear()
        backspaceController.cleanup()
        variationPopup?.dismiss()
        variationPopup = null
        languagePickerPopup?.dismiss()
        languagePickerPopup = null
        punctuationLoader.cleanup()
    }

    companion object {
        private const val STANDARD_KEY_WEIGHT = 1f
        private const val SHIFT_KEY_WEIGHT = 1.5f
        private const val BACKSPACE_KEY_WEIGHT = 1.5f
        private const val MAX_BUTTON_POOL_SIZE = 40
    }
}
