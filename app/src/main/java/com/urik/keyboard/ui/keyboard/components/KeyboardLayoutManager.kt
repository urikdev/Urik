@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
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
import androidx.core.content.ContextCompat
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.settings.KeyLabelSize
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.SpaceBarSize
import com.urik.keyboard.settings.Theme
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.*
import org.json.JSONException
import java.util.concurrent.ConcurrentHashMap

private data class PendingCallbacks(
    val handler: Handler,
    val runnable: Runnable,
)

class KeyboardLayoutManager(
    private val context: Context,
    private val onKeyClick: (KeyboardKey) -> Unit,
    private val onAcceleratedDeletionChanged: (Boolean) -> Unit,
    private val characterVariationService: CharacterVariationService,
    private val languageManager: LanguageManager,
    cacheMemoryManager: CacheMemoryManager,
) {
    companion object {
        private const val STANDARD_KEY_WEIGHT = 1f
        private const val SHIFT_KEY_WEIGHT = 1.5f
        private const val BACKSPACE_KEY_WEIGHT = 1.5f
        private const val MAX_BUTTON_POOL_SIZE = 40
        private const val MAX_ERROR_TRACKING_SIZE = 20
        private const val ERROR_CLEANUP_INTERVAL_MS = 300000L
        private const val ERROR_TRACKING_RETENTION_SECONDS = 60
        private val DEFAULT_PUNCTUATION = listOf(".", ",", "?", "!", "'", "\"", ";", ":")
    }

    private val vibrator =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    private var hapticEnabled = true
    private var hapticDurationMs = 20L

    private var themedContext: Context = context

    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    private var currentLongPressDuration = LongPressDuration.MEDIUM
    private var currentKeySize = KeySize.MEDIUM
    private var currentSpaceBarSize = SpaceBarSize.STANDARD
    private var currentKeyLabelSize = KeyLabelSize.MEDIUM
    private var currentTheme = Theme.SYSTEM

    private val backgroundJob = SupervisorJob()
    private val backgroundScope = CoroutineScope(Dispatchers.IO + backgroundJob)

    private val buttonPool = mutableListOf<Button>()
    private val activeButtons = mutableSetOf<Button>()
    private val buttonPendingCallbacks = ConcurrentHashMap<Button, PendingCallbacks>()

    private val cachedTextSizes = mutableMapOf<Int, Float>()
    private val cachedDimensions = mutableMapOf<String, Int>()
    private var cacheValid = false

    private var backspaceHandler: Handler? = null
    private var backspaceRunnable: Runnable? = null

    private var backspaceStartTime = 0L
    private var backspaceCharsSinceLastHaptic = 0

    private var variationPopup: CharacterVariationPopup? = null

    private val punctuationCache: ManagedCache<String, List<String>> =
        cacheMemoryManager.createCache(
            name = "punctuation_cache",
            maxSize = 20,
        )

    private val failedPunctuationLanguages = ConcurrentHashMap.newKeySet<String>()
    private val punctuationErrorCounts = ConcurrentHashMap<String, Int>()
    private val lastPunctuationErrors = ConcurrentHashMap<String, Long>()
    private val maxPunctuationRetries = 3
    private val punctuationErrorCooldownMs = 60000L
    private var lastErrorCleanupTime = 0L

    fun updateScriptContext() {
        invalidateCalculationCache()
    }

    fun triggerHapticFeedback() {
        performCustomHaptic()
    }

    fun updateLongPressDuration(duration: LongPressDuration) {
        currentLongPressDuration = duration
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

    fun updateKeyLabelSize(keyLabelSize: KeyLabelSize) {
        if (currentKeyLabelSize != keyLabelSize) {
            currentKeyLabelSize = keyLabelSize
            invalidateCalculationCache()
        }
    }

    fun updateTheme(theme: Theme) {
        if (currentTheme != theme) {
            currentTheme = theme
            themedContext = createThemedContext()
            returnActiveButtonsToPool()
            buttonPool.clear()
        }
    }

    private fun createThemedContext(): Context =
        when (currentTheme) {
            Theme.LIGHT -> {
                val config = context.resources.configuration
                val newConfig = android.content.res.Configuration(config)
                newConfig.uiMode =
                    (
                        newConfig.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK
                                .inv()
                    ) or
                    android.content.res.Configuration.UI_MODE_NIGHT_NO
                context.createConfigurationContext(newConfig)
            }
            Theme.DARK -> {
                val config = context.resources.configuration
                val newConfig = android.content.res.Configuration(config)
                newConfig.uiMode =
                    (
                        newConfig.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK
                                .inv()
                    ) or
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                context.createConfigurationContext(newConfig)
            }
            Theme.SYSTEM -> context
        }

    fun updateHapticSettings(
        enabled: Boolean,
        durationMs: Long,
    ) {
        hapticEnabled = enabled
        hapticDurationMs = durationMs
    }

    private fun performCustomHaptic(durationMs: Long = hapticDurationMs) {
        if (!hapticEnabled) return

        try {
            vibrator?.vibrate(
                android.os.VibrationEffect.createOneShot(
                    durationMs,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
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

        val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
        val baseMinTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
        val baseKeyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
        val baseHorizontalMargin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)

        val keySizeMultiplier = currentKeySize.scaleFactor

        cachedDimensions["minTarget"] = (baseMinTouchTarget * keySizeMultiplier).toInt()
        cachedDimensions["keyHeight"] = (baseKeyHeight * keySizeMultiplier).toInt()
        cachedDimensions["horizontalPadding"] = (basePadding * keySizeMultiplier).toInt()
        cachedDimensions["verticalPadding"] = (basePadding * 0.5f * keySizeMultiplier).toInt()
        cachedDimensions["horizontalMargin"] = (baseHorizontalMargin * keySizeMultiplier).toInt()

        cacheValid = true
    }

    private fun getCachedTextSize(keyHeight: Int): Float =
        cachedTextSizes.getOrPut(keyHeight) {
            val baseTextSize = keyHeight * 0.38f / context.resources.displayMetrics.density
            val minSize = 12f
            val maxSize = 24f
            val adjusted = baseTextSize.coerceIn(minSize, maxSize)
            adjusted * currentKeyLabelSize.scaleFactor
        }

    fun createKeyboardView(
        layout: KeyboardLayout,
        state: KeyboardState,
    ): View {
        returnActiveButtonsToPool()

        val keyboardContainer =
            LinearLayout(themedContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )

                val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.keyboard_padding)
                val verticalPadding = context.resources.getDimensionPixelSize(R.dimen.keyboard_padding_vertical)

                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                setBackgroundColor(getThemeAwareColor(R.color.surface_background))

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = context.getString(R.string.keyboard_description)
            }

        layout.rows.forEach { row ->
            val rowView = createRowView(row, state)
            keyboardContainer.addView(rowView)
        }

        return keyboardContainer
    }

    private fun createRowView(
        keys: List<KeyboardKey>,
        state: KeyboardState,
    ): LinearLayout {
        val rowLayout =
            LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            val verticalMargin = context.resources.getDimensionPixelSize(R.dimen.key_margin_vertical)
                            setMargins(0, 0, 0, verticalMargin)
                        }
            }

        val is9LetterRow = is9CharacterLetterRow(keys)

        if (is9LetterRow) {
            val spacer =
                View(themedContext).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 0.5f)
                }
            rowLayout.addView(spacer)
        }

        keys.forEach { key ->
            val keyButton = getOrCreateKeyButton(key, state, keys)
            rowLayout.addView(keyButton)
        }

        if (is9LetterRow) {
            val spacer =
                View(themedContext).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 0.5f)
                }
            rowLayout.addView(spacer)
        }

        return rowLayout
    }

    private fun is9CharacterLetterRow(rowKeys: List<KeyboardKey>): Boolean {
        if (rowKeys.size != 9) return false

        return rowKeys.all { key ->
            key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.LETTER
        }
    }

    private fun getOrCreateKeyButton(
        key: KeyboardKey,
        state: KeyboardState,
        rowKeys: List<KeyboardKey>,
    ): Button {
        val button =
            if (buttonPool.isNotEmpty()) {
                buttonPool.removeAt(buttonPool.size - 1)
            } else {
                Button(themedContext)
            }

        configureButton(button, key, state, rowKeys)
        activeButtons.add(button)

        return button
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureButton(
        button: Button,
        key: KeyboardKey,
        state: KeyboardState,
        rowKeys: List<KeyboardKey>,
    ) {
        ensureCacheValid()

        button.apply {
            setOnClickListener(null)
            setOnLongClickListener(null)
            setOnTouchListener(null)

            val minTarget = cachedDimensions["minTarget"]!!
            val keyHeight = cachedDimensions["keyHeight"]!!
            val visualHeight = keyHeight + 2
            val verticalMargin = (minTarget - visualHeight) / 2

            layoutParams =
                LinearLayout
                    .LayoutParams(
                        0,
                        visualHeight,
                        getKeyWeight(key, rowKeys),
                    ).apply {
                        val horizontalMargin = cachedDimensions["horizontalMargin"]!!
                        setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
                    }

            text = getKeyLabel(key, state)

            val finalTextSize = getCachedTextSize(keyHeight)

            setTextAppearance(
                when (key) {
                    is KeyboardKey.Action -> R.style.KeyTextAppearance_Action
                    else -> R.style.KeyTextAppearance
                },
            )

            setTextSize(TypedValue.COMPLEX_UNIT_SP, finalTextSize)

            typeface = Typeface.DEFAULT

            minHeight = 0
            minimumHeight = 0

            val horizontalPadding = cachedDimensions["horizontalPadding"]!!
            val verticalPadding = cachedDimensions["verticalPadding"]!!
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            setBackgroundResource(getKeyBackground(key, state))
            setTextColor(getKeyTextColor(key))

            isActivated = getKeyActivatedState(key, state)
            isClickable = true
            isFocusable = true

            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getKeyContentDescription(key, state)

            setOnClickListener { view ->
                performCustomHaptic()

                if (accessibilityManager.isEnabled) {
                    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
                    event.contentDescription = contentDescription
                    accessibilityManager.sendAccessibilityEvent(event)
                }

                onKeyClick(key)
            }

            if (key is KeyboardKey.Action) {
                val iconRes =
                    when (key.action) {
                        KeyboardKey.ActionType.SHIFT -> if (state.isCapsLockOn) R.drawable.shift_lock_48px else R.drawable.shift_48px
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
                    val keyBackground = ContextCompat.getDrawable(themedContext, getKeyBackground(key, state))
                    val iconDrawable = ContextCompat.getDrawable(themedContext, iconRes)

                    iconDrawable?.setTint(getKeyTextColor(key))

                    val layerDrawable = LayerDrawable(arrayOf(keyBackground, iconDrawable))
                    layerDrawable.setLayerInset(1, 12, 12, 12, 12)
                    layerDrawable.setLayerGravity(1, Gravity.CENTER)

                    background = layerDrawable
                    text = ""
                } else {
                    setBackgroundResource(getKeyBackground(key, state))
                }
            }

            if (key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.LETTER) {
                setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            val handler = Handler(Looper.getMainLooper())
                            val runnable =
                                Runnable {
                                    performCustomHaptic()
                                    handleCharacterLongPress(key, view)
                                }
                            buttonPendingCallbacks[this] = PendingCallbacks(handler, runnable)
                            handler.postDelayed(runnable, currentLongPressDuration.durationMs)
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            buttonPendingCallbacks.remove(this)?.let { pending ->
                                pending.handler.removeCallbacks(pending.runnable)
                            }
                            false
                        }
                        else -> false
                    }
                }
            }

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.SPACE) {
                setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            val handler = Handler(Looper.getMainLooper())
                            val runnable =
                                Runnable {
                                    performCustomHaptic()
                                    handleSpaceLongPress(view)
                                }
                            buttonPendingCallbacks[this] = PendingCallbacks(handler, runnable)
                            handler.postDelayed(runnable, currentLongPressDuration.durationMs)
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            buttonPendingCallbacks.remove(this)?.let { pending ->
                                pending.handler.removeCallbacks(pending.runnable)
                            }
                            false
                        }
                        else -> false
                    }
                }
            }

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.BACKSPACE) {
                setOnLongClickListener { view ->
                    performCustomHaptic()
                    startAcceleratedBackspace()
                    true
                }

                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            stopAcceleratedBackspace()
                            false
                        }
                        else -> false
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun cleanupButton(button: Button) {
        buttonPendingCallbacks.remove(button)?.let { pending ->
            pending.handler.removeCallbacks(pending.runnable)
        }

        button.setOnClickListener(null)
        button.setOnLongClickListener(null)
        button.setOnTouchListener(null)

        (button.parent as? ViewGroup)?.removeView(button)
    }

    private fun returnActiveButtonsToPool() {
        variationPopup?.dismiss()

        activeButtons.forEach { button ->
            cleanupButton(button)

            if (buttonPool.size < MAX_BUTTON_POOL_SIZE) {
                buttonPool.add(button)
            }
        }
        activeButtons.clear()
    }

    private fun getKeyLabel(
        key: KeyboardKey,
        state: KeyboardState,
    ): String =
        when (key) {
            is KeyboardKey.Character -> {
                when {
                    key.type == KeyboardKey.KeyType.LETTER && shouldCapitalize(state) -> key.value.uppercase()
                    else -> key.value
                }
            }
            is KeyboardKey.Action -> {
                when (key.action) {
                    KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> context.getString(R.string.letters_mode_label)
                    KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> context.getString(R.string.numbers_mode_label)
                    KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> context.getString(R.string.symbols_mode_label)
                    else -> "?"
                }
            }
        }

    private fun getKeyContentDescription(
        key: KeyboardKey,
        state: KeyboardState,
    ): String =
        when (key) {
            is KeyboardKey.Character -> {
                val char =
                    when {
                        key.type == KeyboardKey.KeyType.LETTER && shouldCapitalize(state) -> key.value.uppercase()
                        else -> key.value
                    }
                context.getString(R.string.key_character_description, char)
            }
            is KeyboardKey.Action ->
                when (key.action) {
                    KeyboardKey.ActionType.SHIFT -> {
                        when {
                            state.isCapsLockOn -> context.getString(R.string.caps_lock_on_description)
                            state.isShiftPressed -> context.getString(R.string.shift_active_description)
                            else -> context.getString(R.string.shift_key_description)
                        }
                    }
                    KeyboardKey.ActionType.BACKSPACE -> context.getString(R.string.backspace_key_description)
                    KeyboardKey.ActionType.SPACE -> context.getString(R.string.space_key_description)
                    KeyboardKey.ActionType.ENTER -> context.getString(R.string.action_enter_description)
                    KeyboardKey.ActionType.SEARCH -> context.getString(R.string.action_search_description)
                    KeyboardKey.ActionType.SEND -> context.getString(R.string.action_send_description)
                    KeyboardKey.ActionType.DONE -> context.getString(R.string.action_done_description)
                    KeyboardKey.ActionType.GO -> context.getString(R.string.action_go_description)
                    KeyboardKey.ActionType.NEXT -> context.getString(R.string.action_next_description)
                    KeyboardKey.ActionType.PREVIOUS -> context.getString(R.string.action_previous_description)
                    KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> context.getString(R.string.letters_mode_description)
                    KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> context.getString(R.string.numbers_mode_description)
                    KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> context.getString(R.string.symbols_mode_description)
                    KeyboardKey.ActionType.CAPS_LOCK -> context.getString(R.string.caps_lock_description)
                }
        }

    private fun handleSpaceLongPress(view: View) {
        performCustomHaptic()

        val currentLanguage = languageManager.currentLanguage.value
        val languageCode = currentLanguage.split("-").first()

        backgroundScope.launch {
            try {
                val punctuation = loadPunctuationWithErrorHandling(languageCode)
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, punctuation)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, DEFAULT_PUNCTUATION)
                }
            }
        }
    }

    private fun showPunctuationPopup(
        anchorView: View,
        punctuationList: List<String>,
    ) {
        variationPopup?.dismiss()

        anchorView.isPressed = false

        variationPopup =
            CharacterVariationPopup(themedContext).apply {
                setCharacterVariations("", punctuationList) { selectedPunctuation ->
                    performCustomHaptic()
                    val punctuationKey = KeyboardKey.Character(selectedPunctuation, KeyboardKey.KeyType.PUNCTUATION)
                    onKeyClick(punctuationKey)
                }
                showAboveAnchor(anchorView)
            }
    }

    private fun loadPunctuationWithErrorHandling(languageCode: String): List<String> {
        punctuationCache.getIfPresent(languageCode)?.let { cached ->
            return cached
        }

        performPeriodicErrorCleanup()

        if (shouldSkipPunctuationLoading(languageCode)) {
            return getFallbackPunctuation(languageCode)
        }

        return try {
            loadPunctuationFromAssets(languageCode).also { punctuation ->
                punctuationCache.put(languageCode, punctuation)
                punctuationErrorCounts.remove(languageCode)
                lastPunctuationErrors.remove(languageCode)
            }
        } catch (_: Exception) {
            recordPunctuationError(languageCode)
            getFallbackPunctuation(languageCode)
        }
    }

    private fun recordPunctuationError(languageCode: String) {
        val currentTime = System.currentTimeMillis()
        val currentCount = punctuationErrorCounts.getOrDefault(languageCode, 0)

        punctuationErrorCounts[languageCode] = currentCount + 1
        lastPunctuationErrors[languageCode] = currentTime

        if (currentCount + 1 >= maxPunctuationRetries) {
            failedPunctuationLanguages.add(languageCode)
        }

        enforceErrorTrackingBounds()
    }

    private fun enforceErrorTrackingBounds() {
        while (punctuationErrorCounts.size > MAX_ERROR_TRACKING_SIZE) {
            val oldestEntry =
                lastPunctuationErrors.entries
                    .minByOrNull { it.value }
                    ?.key

            if (oldestEntry != null) {
                punctuationErrorCounts.remove(oldestEntry)
                lastPunctuationErrors.remove(oldestEntry)
                failedPunctuationLanguages.remove(oldestEntry)
            } else {
                break
            }
        }
    }

    private fun performPeriodicErrorCleanup() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastErrorCleanupTime < ERROR_CLEANUP_INTERVAL_MS) {
            return
        }

        lastErrorCleanupTime = currentTime

        val cutoffTime = currentTime - (ERROR_TRACKING_RETENTION_SECONDS * 1000L)

        val expiredLanguages =
            lastPunctuationErrors.entries
                .filter { entry -> entry.value < cutoffTime }
                .map { entry -> entry.key }
                .toList()

        expiredLanguages.forEach { languageCode ->
            punctuationErrorCounts.remove(languageCode)
            lastPunctuationErrors.remove(languageCode)
            failedPunctuationLanguages.remove(languageCode)
        }
    }

    private fun loadPunctuationFromAssets(languageCode: String): List<String> {
        val filename = "$languageCode.json"

        return context.assets.open("punctuation/$filename").bufferedReader().use { reader ->
            val jsonContent = reader.readText()
            if (jsonContent.isBlank()) {
                throw IllegalStateException("Punctuation file $filename is empty")
            }
            parsePunctuationJson(jsonContent)
        }
    }

    private fun parsePunctuationJson(jsonContent: String): List<String> {
        val json = org.json.JSONObject(jsonContent)
        if (!json.has("punctuation")) {
            throw JSONException("Missing 'punctuation' key in punctuation file")
        }

        val punctuationArray = json.getJSONArray("punctuation")
        val result = mutableListOf<String>()

        for (i in 0 until punctuationArray.length()) {
            val punctuation = punctuationArray.getString(i)
            if (punctuation.isNotBlank()) {
                result.add(punctuation)
            }
        }

        if (result.isEmpty()) {
            throw IllegalStateException("No valid punctuation marks found in file")
        }

        return result
    }

    private fun getFallbackPunctuation(languageCode: String): List<String> =
        when {
            languageCode != "en" && !failedPunctuationLanguages.contains("en") -> {
                try {
                    loadPunctuationFromAssets("en").also { punctuation ->
                        punctuationCache.put("en", punctuation)
                    }
                } catch (_: Exception) {
                    failedPunctuationLanguages.add("en")
                    DEFAULT_PUNCTUATION
                }
            }
            else -> DEFAULT_PUNCTUATION
        }

    private fun shouldSkipPunctuationLoading(languageCode: String): Boolean {
        val errorCount = punctuationErrorCounts.getOrDefault(languageCode, 0)
        val lastError = lastPunctuationErrors.getOrDefault(languageCode, 0)
        val now = System.currentTimeMillis()

        return errorCount >= maxPunctuationRetries && (now - lastError) < punctuationErrorCooldownMs
    }

    private fun handleCharacterLongPress(
        key: KeyboardKey.Character,
        view: View,
    ) {
        val currentLanguage = languageManager.currentLanguage.value

        backgroundScope.launch {
            try {
                val variations = characterVariationService.getVariations(key.value, currentLanguage)
                if (variations.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        showCharacterVariationPopup(key, view, variations)
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

    private fun showCharacterVariationPopup(
        key: KeyboardKey.Character,
        anchorView: View,
        variations: List<String>,
    ) {
        variationPopup?.dismiss()
        performCustomHaptic()

        anchorView.isPressed = false

        variationPopup =
            CharacterVariationPopup(themedContext).apply {
                setCharacterVariations(key.value, variations) { selectedChar ->
                    performCustomHaptic()
                    val selectedKey = KeyboardKey.Character(selectedChar, key.type)
                    onKeyClick(selectedKey)
                }
                showAboveAnchor(anchorView)
            }
    }

    private fun startAcceleratedBackspace() {
        stopAcceleratedBackspace()

        onAcceleratedDeletionChanged(true)

        backspaceStartTime = System.currentTimeMillis()
        backspaceCharsSinceLastHaptic = 0

        backspaceHandler = Handler(Looper.getMainLooper())
        backspaceRunnable =
            object : Runnable {
                override fun run() {
                    val elapsed = System.currentTimeMillis() - backspaceStartTime

                    onKeyClick(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE))
                    backspaceCharsSinceLastHaptic++
                    performHapticForBackspace(elapsed)

                    val nextInterval = calculateBackspaceInterval(elapsed)
                    backspaceHandler?.postDelayed(this, nextInterval)
                }
            }

        backspaceHandler?.postDelayed(backspaceRunnable!!, currentLongPressDuration.durationMs)
    }

    private fun stopAcceleratedBackspace() {
        backspaceRunnable?.let { runnable ->
            backspaceHandler?.removeCallbacks(runnable)
        }
        backspaceHandler = null
        backspaceRunnable = null
        backspaceStartTime = 0L
        backspaceCharsSinceLastHaptic = 0

        onAcceleratedDeletionChanged(false)
    }

    private fun calculateBackspaceInterval(elapsed: Long): Long {
        val startSpeed = 100L
        val endSpeed = 15L
        val accelerationDuration = 2000f

        if (elapsed >= accelerationDuration) {
            return endSpeed
        }

        val progress = elapsed / accelerationDuration
        return (startSpeed - (startSpeed - endSpeed) * progress).toLong()
    }

    private fun performHapticForBackspace(elapsed: Long) {
        val interval = calculateBackspaceInterval(elapsed)

        if (interval > 50) {
            performCustomHaptic()
            backspaceCharsSinceLastHaptic = 0
        } else if (backspaceCharsSinceLastHaptic >= 5) {
            performCustomHaptic()
            backspaceCharsSinceLastHaptic = 0
        }
    }

    private fun getKeyWeight(
        key: KeyboardKey,
        rowKeys: List<KeyboardKey>,
    ): Float {
        val isNumberModeRow = isNumberModeRow(rowKeys)

        if (isNumberModeRow) {
            return STANDARD_KEY_WEIGHT
        }

        return when (key) {
            is KeyboardKey.Character ->
                when (key.type) {
                    KeyboardKey.KeyType.PUNCTUATION -> 0.7f
                    else -> STANDARD_KEY_WEIGHT
                }
            is KeyboardKey.Action ->
                when (key.action) {
                    KeyboardKey.ActionType.SPACE -> currentSpaceBarSize.widthMultiplier
                    KeyboardKey.ActionType.SHIFT -> SHIFT_KEY_WEIGHT
                    KeyboardKey.ActionType.BACKSPACE -> BACKSPACE_KEY_WEIGHT
                    else -> STANDARD_KEY_WEIGHT
                }
        }
    }

    private fun isNumberModeRow(rowKeys: List<KeyboardKey>): Boolean {
        if (rowKeys.size != 3) return false

        return rowKeys.all { key ->
            when (key) {
                is KeyboardKey.Character -> key.type == KeyboardKey.KeyType.NUMBER || key.type == KeyboardKey.KeyType.PUNCTUATION
                is KeyboardKey.Action -> key.action == KeyboardKey.ActionType.BACKSPACE
            }
        }
    }

    private fun shouldCapitalize(state: KeyboardState): Boolean = state.isShiftPressed || state.isCapsLockOn

    private fun getKeyActivatedState(
        key: KeyboardKey,
        state: KeyboardState,
    ): Boolean =
        when (key) {
            is KeyboardKey.Action ->
                when (key.action) {
                    KeyboardKey.ActionType.SHIFT -> state.isShiftPressed && !state.isCapsLockOn
                    KeyboardKey.ActionType.CAPS_LOCK -> state.isCapsLockOn
                    else -> false
                }
            else -> false
        }

    private fun getKeyBackground(
        key: KeyboardKey,
        state: KeyboardState,
    ): Int =
        when (key) {
            is KeyboardKey.Character -> R.drawable.key_background_character
            is KeyboardKey.Action ->
                when (key.action) {
                    KeyboardKey.ActionType.SHIFT -> {
                        when {
                            state.isCapsLockOn -> R.drawable.key_background_caps_lock_on
                            else -> R.drawable.key_background_action
                        }
                    }
                    KeyboardKey.ActionType.SPACE -> R.drawable.key_background_space
                    else -> R.drawable.key_background_action
                }
        }

    private fun getKeyTextColor(key: KeyboardKey): Int =
        when (key) {
            is KeyboardKey.Character -> getThemeAwareColor(R.color.key_text_character)
            is KeyboardKey.Action -> getThemeAwareColor(R.color.key_text_action)
        }

    private fun getThemeAwareColor(colorRes: Int): Int = ContextCompat.getColor(themedContext, colorRes)

    fun getThemedContext(): Context = themedContext

    fun cleanup() {
        backgroundJob.cancel()
        returnActiveButtonsToPool()
        buttonPool.clear()
        buttonPendingCallbacks.clear()
        stopAcceleratedBackspace()
        variationPopup?.dismiss()
        variationPopup = null
        punctuationCache.invalidateAll()
    }

    fun onLowMemory() {
        returnActiveButtonsToPool()
        buttonPool.clear()
    }
}
