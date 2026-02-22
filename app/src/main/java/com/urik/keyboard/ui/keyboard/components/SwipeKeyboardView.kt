package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.emoji2.emojipicker.EmojiPickerView
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main keyboard view with swipe typing and suggestion bar.
 *
 */
class SwipeKeyboardView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr),
        SwipeDetector.SwipeListener {
        private val touchCoordinateTransformer = TouchCoordinateTransformer(this)
        private val suggestionViewPool = mutableListOf<TextView>()
        private val activeSuggestionViews = mutableListOf<TextView>()
        private val dividerViewPool = mutableListOf<View>()
        private val activeDividerViews = mutableListOf<View>()
        private var spellCheckManager: SpellCheckManager? = null
        private var keyboardLayoutManager: KeyboardLayoutManager? = null
        private var swipeDetector: SwipeDetector? = null
        private var themeManager: ThemeManager? = null
        private var languageManager: LanguageManager? = null
        private var emojiSearchManager: EmojiSearchManager? = null
        private var recentEmojiProvider: com.urik.keyboard.service.RecentEmojiProvider? = null

        private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
        private var onSwipeWordListener: ((String) -> Unit)? = null
        private var onSuggestionClickListener: ((String) -> Unit)? = null
        private var onSuggestionLongPressListener: ((String) -> Unit)? = null
        private var onEmojiSelected: ((String) -> Unit)? = null
        private var onBackspacePressed: (() -> Unit)? = null
        private var onSpacebarCursorMove: ((Int) -> Unit)? = null
        private var onBackspaceSwipeDelete: (() -> Unit)? = null
        private val keyViews = mutableListOf<Button>()
        private val keyPositions = mutableMapOf<Button, Rect>()
        private val keyMapping = mutableMapOf<Button, KeyboardKey>()
        private val keyCharacterPositions = mutableMapOf<KeyboardKey.Character, PointF>()

        private var popupActive = false

        internal var currentLayout: KeyboardLayout? = null
        internal var currentState: KeyboardState? = null

        private var wordLearningEngine: WordLearningEngine? = null

        private val swipeOverlay = SwipeOverlayView(context)

        private var suggestionBar: LinearLayout? = null

        private var emojiButton: TextView? = null

        private var isSwipeActive = false
        private var hasTouchStart = false
        private val touchStartPoint = PointF()
        private var touchStartTime = 0L

        private var isGestureActive = false
        private var gestureKey: KeyboardKey.Action? = null
        private var gestureStartX = 0f
        private var gestureStartY = 0f
        private var gestureLastProcessedX = 0f
        private var gestureDensity = 1f
        private var currentCursorSpeed: com.urik.keyboard.settings.CursorSpeed = com.urik.keyboard.settings.CursorSpeed.MEDIUM

        private var confirmationOverlay: FrameLayout? = null
        private var pendingRemovalSuggestion: String? = null

        private var emojiPickerContainer: LinearLayout? = null
        private var isShowingEmojiPicker = false
        private var emojiSearchInputContainer: LinearLayout? = null
        private var emojiSearchInput: EditText? = null
        private var emojiSearchResultsContainer: View? = null
        private var emojiSearchContainer: LinearLayout? = null
        private var isEmojiSearchActive = false
        private var searchDebounceJob: kotlinx.coroutines.Job? = null
        private val emojiViewPool = mutableListOf<TextView>()
        private val activeEmojiViews = mutableListOf<TextView>()

        private var emojiPickerView: EmojiPickerView? = null
        private var searchCloseButton: TextView? = null
        private var closeButtonBar: LinearLayout? = null
        private var searchButton: ImageView? = null
        private var backspaceButton: TextView? = null
        private var closeButton: TextView? = null
        private var searchResultsGrid: GridLayout? = null
        private var searchScopeJob: kotlinx.coroutines.Job? = null
        private var searchScope: CoroutineScope? = null

        private var suggestionBarHeightPx = -1
        private var searchOverlapOffsetPx = -1

        private var autofillIndicatorIcon: TextView? = null
        private var isShowingAutofillSuggestions = false

        private var cachedLocationArray = IntArray(2)
        private var cachedParentLocationArray = IntArray(2)
        private val cachedKeyRect = Rect()

        private var cachedCursorDrawable: android.graphics.drawable.GradientDrawable? = null
        private var cachedCursorColor: Int = 0

        private var isDestroyed = false

        private var viewScopeJob = SupervisorJob()
        private var viewScope = CoroutineScope(Dispatchers.Main + viewScopeJob)

        private val suggestionClickListener =
            OnClickListener { view ->
                if (isDestroyed) return@OnClickListener
                val suggestion = view.getTag(R.id.suggestion_text) as? String ?: return@OnClickListener
                keyboardLayoutManager?.triggerHapticFeedback()
                onSuggestionClickListener?.invoke(suggestion)
            }

        private val suggestionLongClickListener =
            OnLongClickListener { view ->
                if (isDestroyed) return@OnLongClickListener false
                val suggestion = view.getTag(R.id.suggestion_text) as? String ?: return@OnLongClickListener false
                showRemovalConfirmation(suggestion)
                true
            }

        private val emojiButtonClickListener =
            OnClickListener {
                if (isDestroyed) return@OnClickListener
                showEmojiPicker()
            }

        private val emojiPickedListener =
            androidx.core.util.Consumer<androidx.emoji2.emojipicker.EmojiViewItem> { emojiViewItem ->
                if (isDestroyed) return@Consumer
                onEmojiSelected?.invoke(emojiViewItem.emoji)
            }

        private val backspaceClickListener =
            OnClickListener {
                if (isDestroyed) return@OnClickListener
                onBackspacePressed?.invoke()
            }

        private val searchActivateClickListener =
            OnClickListener {
                if (isDestroyed) return@OnClickListener
                emojiPickerContainer?.visibility = GONE
                isEmojiSearchActive = true

                emojiSearchContainer?.let { searchContainer ->
                    addView(
                        searchContainer,
                        LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT,
                        ).apply {
                            gravity = Gravity.TOP
                        },
                    )
                }

                emojiSearchInputContainer?.visibility = VISIBLE

                findKeyboardView()?.let { keyboardView ->
                    keyboardView.visibility = VISIBLE
                    (keyboardView.layoutParams as? LayoutParams)?.let { params ->
                        params.gravity = Gravity.BOTTOM
                        keyboardView.requestLayout()
                    }
                }

                requestLayout()

                emojiSearchInput?.post {
                    emojiSearchInput?.requestFocus()
                    emojiSearchInput?.text?.length?.let { emojiSearchInput?.setSelection(it) }
                }
            }

        private val searchCloseClickListener =
            OnClickListener {
                if (isDestroyed) return@OnClickListener
                emojiSearchContainer?.let { removeView(it) }
                emojiSearchResultsContainer?.visibility = GONE
                searchResultsGrid?.removeAllViews()
                emojiPickerContainer?.visibility = VISIBLE
                isEmojiSearchActive = false
                emojiSearchInput?.text?.clear()

                findKeyboardView()?.let { keyboardView ->
                    keyboardView.visibility = GONE
                    (keyboardView.layoutParams as? LayoutParams)?.let { params ->
                        params.gravity = Gravity.NO_GRAVITY
                        keyboardView.requestLayout()
                    }
                }
            }

        private val emojiPickerCloseClickListener =
            OnClickListener {
                if (isDestroyed) return@OnClickListener
                hideEmojiPicker()
            }

        private val searchResultEmojiClickListener =
            OnClickListener { view ->
                if (isDestroyed) return@OnClickListener
                val emoji = view.getTag(R.id.suggestion_text) as? String ?: return@OnClickListener
                recentEmojiProvider?.recordSelection(emoji)
                onEmojiSelected?.invoke(emoji)
            }

        private val globalLayoutListener =
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (!isDestroyed && isAttachedToWindow) {
                        mapKeyPositions()
                    }
                }
            }

        init {
            addView(
                swipeOverlay,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                ),
            )
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            updateDensity()
        }

        private fun setupSwipeOverlay() {
            themeManager?.let { manager ->
                swipeOverlay.setThemeManager(manager)

                viewScope.launch {
                    manager.currentTheme.collect {
                        swipeOverlay.resetColors()
                        updateEmojiPickerColors()
                    }
                }
            }
        }

        private fun isTouchInEmojiPicker(
            x: Float,
            y: Float,
        ): Boolean {
            if (!isShowingEmojiPicker || emojiPickerContainer == null) return false

            emojiPickerContainer!!.getLocationInWindow(cachedLocationArray)
            this.getLocationInWindow(cachedParentLocationArray)

            val left = cachedLocationArray[0] - cachedParentLocationArray[0]
            val top = cachedLocationArray[1] - cachedParentLocationArray[1]
            val right = left + emojiPickerContainer!!.width
            val bottom = top + emojiPickerContainer!!.height

            return x >= left && x < right && y >= top && y < bottom
        }

        private fun isTouchInSuggestionBar(
            x: Float,
            y: Float,
        ): Boolean {
            val bar = suggestionBar ?: return false
            if (!bar.isVisible) return false

            bar.getLocationInWindow(cachedLocationArray)
            this.getLocationInWindow(cachedParentLocationArray)

            val left = cachedLocationArray[0] - cachedParentLocationArray[0]
            val top = cachedLocationArray[1] - cachedParentLocationArray[1]

            cachedKeyRect.set(left, top, left + bar.width, top + bar.height)

            return cachedKeyRect.contains(x.toInt(), y.toInt())
        }

        private fun setupEmojiPickerViews() {
            if (searchButton != null) return

            val baseContext = context

            val emojiTextSize = calculateResponsiveSuggestionTextSize()
            val minTouchTarget = baseContext.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
            val basePadding = baseContext.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)

            searchScopeJob = SupervisorJob()
            searchScope = CoroutineScope(Dispatchers.Main + searchScopeJob!!)

            val searchResultsGrid =
                GridLayout(baseContext).apply {
                    rowCount = 2
                    columnCount = GridLayout.UNDEFINED
                    orientation = GridLayout.HORIZONTAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    val gridPadding = (basePadding * 0.5f).toInt()
                    setPadding(0, gridPadding, 0, gridPadding)
                }
            this.searchResultsGrid = searchResultsGrid

            val searchInput =
                EditText(baseContext).apply {
                    hint = baseContext.getString(R.string.emoji_search_hint)
                    background = null
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isCursorVisible = true
                    showSoftInputOnFocus = false
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )

                    addTextChangedListener(
                        object : TextWatcher {
                            override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int,
                            ) {}

                            override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int,
                            ) {}

                            override fun afterTextChanged(s: Editable?) {
                                searchDebounceJob?.cancel()

                                val query = s?.toString() ?: ""
                                if (query.isBlank()) {
                                    emojiSearchResultsContainer?.visibility = GONE
                                    searchResultsGrid?.removeAllViews()
                                    return
                                }

                                searchDebounceJob =
                                    searchScope?.launch {
                                        kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)

                                        val results = emojiSearchManager?.search(query) ?: emptyList()
                                        withContext(Dispatchers.Main) {
                                            returnEmojiViewsToPool()
                                            searchResultsGrid?.removeAllViews()

                                            if (results.isEmpty()) {
                                                emojiSearchResultsContainer?.visibility = GONE
                                            } else {
                                                emojiSearchResultsContainer?.visibility = VISIBLE
                                                results.forEach { emoji ->
                                                    val emojiView = getOrCreateEmojiView(emoji)
                                                    searchResultsGrid?.addView(emojiView)
                                                    activeEmojiViews.add(emojiView)
                                                }
                                            }
                                        }
                                    }
                            }
                        },
                    )
                }
            emojiSearchInput = searchInput

            val searchCloseBtn =
                TextView(baseContext).apply {
                    text = "✕"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, emojiTextSize)
                    gravity = Gravity.CENTER
                    contentDescription = "Close search"
                    layoutParams =
                        LinearLayout.LayoutParams(
                            minTouchTarget,
                            minTouchTarget,
                        )
                }
            this.searchCloseButton = searchCloseBtn

            val searchInputContainer =
                LinearLayout(baseContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    val horizontalPadding = (basePadding * 0.8f).toInt()
                    val verticalPadding = (basePadding * 0.3f).toInt()
                    setPadding(horizontalPadding, verticalPadding, 0, verticalPadding)
                    minimumHeight = minTouchTarget
                    visibility = GONE
                    addView(searchInput)
                    addView(searchCloseBtn)
                }
            emojiSearchInputContainer = searchInputContainer

            val searchResultsContainer =
                android.widget.HorizontalScrollView(baseContext).apply {
                    visibility = GONE
                    isFillViewport = true
                    clipChildren = true
                    clipToPadding = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    ViewCompat.setAccessibilityDelegate(
                        this,
                        object : AccessibilityDelegateCompat() {
                            override fun onInitializeAccessibilityNodeInfo(
                                host: View,
                                info: AccessibilityNodeInfoCompat,
                            ) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.removeAction(
                                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_TEXT,
                                )
                                info.isEditable = false
                            }
                        },
                    )
                    val horizontalPadding = (basePadding * 0.8f).toInt()
                    setPadding(horizontalPadding, 0, 0, 0)
                    addView(searchResultsGrid)
                }
            emojiSearchResultsContainer = searchResultsContainer

            val searchBtn =
                ImageView(baseContext).apply {
                    setImageResource(R.drawable.search_48px)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val buttonPadding = (12f * baseContext.resources.displayMetrics.density).toInt()
                    setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
                    contentDescription = "Search emojis"
                    isClickable = true
                    isFocusable = true
                    layoutParams =
                        LinearLayout.LayoutParams(
                            minTouchTarget,
                            minTouchTarget,
                        )
                }
            this.searchButton = searchBtn

            val backspaceBtn =
                TextView(baseContext).apply {
                    text = "⌫"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                    val buttonPadding = (18f * baseContext.resources.displayMetrics.density).toInt()
                    setPadding(buttonPadding, 0, buttonPadding, 0)
                    contentDescription = "Backspace"
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            minTouchTarget,
                        )
                }
            this.backspaceButton = backspaceBtn

            val closeBtn =
                TextView(baseContext).apply {
                    text = "✕"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, emojiTextSize)
                    gravity = Gravity.CENTER
                    contentDescription = "Close emoji picker"
                    layoutParams =
                        LinearLayout.LayoutParams(
                            minTouchTarget,
                            minTouchTarget,
                        )
                }
            this.closeButton = closeBtn

            val btnBar =
                LinearLayout(baseContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    val verticalPadding = (basePadding * 0.3f).toInt()
                    setPadding(basePadding, verticalPadding, 0, verticalPadding)
                    minimumHeight = minTouchTarget

                    val spacer =
                        View(baseContext).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    0,
                                    0,
                                    1f,
                                )
                        }
                    addView(spacer)
                    addView(searchBtn)
                    addView(backspaceBtn)
                    addView(closeBtn)
                }
            this.closeButtonBar = btnBar

            val searchContainer =
                LinearLayout(baseContext).apply {
                    orientation = LinearLayout.VERTICAL
                    val horizontalPadding = baseContext.resources.getDimensionPixelSize(R.dimen.keyboard_padding)
                    val verticalPadding = baseContext.resources.getDimensionPixelSize(R.dimen.keyboard_padding_vertical)
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
                    addView(
                        searchResultsContainer,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        searchInputContainer,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            minTouchTarget,
                        ),
                    )
                }
            emojiSearchContainer = searchContainer

            searchCloseBtn.setOnClickListener(searchCloseClickListener)
            searchBtn.setOnClickListener(searchActivateClickListener)
            backspaceBtn.setOnClickListener(backspaceClickListener)
            closeBtn.setOnClickListener(emojiPickerCloseClickListener)
        }

        private fun updateEmojiPickerColors() {
            val theme = themeManager?.currentTheme?.value ?: return

            emojiPickerContainer?.setBackgroundColor(theme.colors.keyboardBackground)
            emojiSearchContainer?.setBackgroundColor(theme.colors.keyboardBackground)
            emojiSearchInputContainer?.setBackgroundColor(theme.colors.suggestionBarBackground)
            emojiSearchResultsContainer?.setBackgroundColor(theme.colors.suggestionBarBackground)
            closeButtonBar?.setBackgroundColor(theme.colors.suggestionBarBackground)

            emojiSearchInput?.setTextColor(theme.colors.suggestionText)
            emojiSearchInput?.setHintTextColor(theme.colors.suggestionText and 0x60FFFFFF)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cursorColor = theme.colors.keyTextCharacter
                if (cachedCursorDrawable == null || cachedCursorColor != cursorColor) {
                    cachedCursorColor = cursorColor
                    cachedCursorDrawable =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            setSize(
                                (2 * context.resources.displayMetrics.density).toInt(),
                                ((emojiSearchInput?.textSize ?: 16f) * 1.2f).toInt(),
                            )
                            setColor(cursorColor)
                        }
                }
                emojiSearchInput?.textCursorDrawable = cachedCursorDrawable
            }

            searchCloseButton?.setTextColor(theme.colors.keyTextAction)
            searchCloseButton?.setBackgroundColor(theme.colors.keyBackgroundAction)

            searchButton?.setColorFilter(theme.colors.keyTextAction)
            searchButton?.setBackgroundColor(theme.colors.keyBackgroundAction)

            backspaceButton?.setTextColor(theme.colors.keyTextAction)
            backspaceButton?.setBackgroundColor(theme.colors.keyBackgroundAction)

            closeButton?.setTextColor(theme.colors.keyTextAction)
            closeButton?.setBackgroundColor(theme.colors.keyBackgroundAction)
        }

        /**
         * Shows full-screen emoji picker overlay.
         *
         * Hides keyboard view, shows emoji grid with backspace and close buttons.
         * Auto-hidden on keyboard layout change.
         */
        fun showEmojiPicker() {
            if (isDestroyed || isShowingEmojiPicker) return

            setupEmojiPickerViews()

            val baseContext = context
            val themedContext =
                androidx.appcompat.view.ContextThemeWrapper(
                    baseContext,
                    R.style.Theme_Urik,
                )
            val minTouchTarget = baseContext.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)

            val pickerView =
                EmojiPickerView(themedContext).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f,
                        )
                    setOnEmojiPickedListener(emojiPickedListener)
                    recentEmojiProvider?.let { provider ->
                        setRecentEmojiProvider(provider)
                    }
                }
            emojiPickerView = pickerView

            val horizontalPadding = baseContext.resources.getDimensionPixelSize(R.dimen.keyboard_padding)
            val verticalPadding = baseContext.resources.getDimensionPixelSize(R.dimen.keyboard_padding_vertical)

            (closeButtonBar?.parent as? ViewGroup)?.removeView(closeButtonBar)

            val container =
                LinearLayout(baseContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                    clipChildren = false
                    clipToPadding = false
                    addView(
                        closeButtonBar,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            minTouchTarget,
                        ),
                    )
                    addView(pickerView)
                }
            emojiPickerContainer = container

            isShowingEmojiPicker = true
            findKeyboardView()?.visibility = GONE

            searchScope?.launch {
                emojiSearchManager?.ensureLoaded()
            }

            updateEmojiPickerColors()

            addView(
                container,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                ),
            )
        }

        /**
         * Hides emoji picker and restores keyboard view.
         */
        fun hideEmojiPicker() {
            if (isDestroyed || !isShowingEmojiPicker) return

            isShowingEmojiPicker = false
            isEmojiSearchActive = false

            emojiPickerContainer?.let { container ->
                removeView(container)
            }

            emojiSearchContainer?.let { searchContainer ->
                removeView(searchContainer)
            }

            emojiPickerView = null
            emojiPickerContainer = null

            emojiSearchInput?.text?.clear()
            emojiSearchResultsContainer?.visibility = GONE
            searchResultsGrid?.removeAllViews()
            emojiSearchInputContainer?.visibility = GONE

            findKeyboardView()?.let { keyboardView ->
                keyboardView.visibility = VISIBLE
                (keyboardView.layoutParams as? LayoutParams)?.let { params ->
                    params.gravity = Gravity.NO_GRAVITY
                    keyboardView.requestLayout()
                }
                suggestionBar?.let { bar ->
                    val minTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
                    bar.minimumHeight = minTouchTarget
                    bar.requestLayout()
                }
                keyboardView.requestLayout()
            }
            requestLayout()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val keyboardView = findKeyboardView()

            if (keyboardView != null) {
                keyboardView.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val keyboardHeight = keyboardView.measuredHeight

                val totalHeight =
                    if (isEmojiSearchActive && emojiSearchContainer != null) {
                        emojiSearchContainer!!.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                        val searchHeight = emojiSearchContainer!!.measuredHeight

                        if (suggestionBarHeightPx == -1) {
                            suggestionBarHeightPx = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
                        }
                        if (searchOverlapOffsetPx == -1) {
                            searchOverlapOffsetPx = (4 * context.resources.displayMetrics.density).toInt()
                        }

                        val calculatedHeight = searchHeight + keyboardHeight - suggestionBarHeightPx - searchOverlapOffsetPx
                        maxOf(calculatedHeight, keyboardHeight)
                    } else {
                        keyboardHeight
                    }

                val constrainedHeight = MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
                super.onMeasure(widthMeasureSpec, constrainedHeight)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        /**
         * Initializes view with dependencies.
         *
         * Call once after view creation with managed components.
         * Strong references explicitly cleared on cleanup.
         */
        fun initialize(
            layoutManager: KeyboardLayoutManager,
            detector: SwipeDetector,
            spellCheckManager: SpellCheckManager,
            wordLearningEngine: WordLearningEngine,
            themeManager: ThemeManager,
            languageManager: LanguageManager,
            emojiSearchManager: EmojiSearchManager,
            recentEmojiProvider: com.urik.keyboard.service.RecentEmojiProvider,
        ) {
            if (isDestroyed) return

            this.keyboardLayoutManager = layoutManager
            this.swipeDetector = detector
            this.spellCheckManager = spellCheckManager
            this.wordLearningEngine = wordLearningEngine
            this.themeManager = themeManager
            this.languageManager = languageManager
            this.emojiSearchManager = emojiSearchManager
            this.recentEmojiProvider = recentEmojiProvider

            detector.setSwipeListener(this)
            setupSwipeOverlay()
        }

        fun setOnKeyClickListener(listener: (KeyboardKey) -> Unit) {
            if (!isDestroyed) {
                this.onKeyClickListener = listener
            }
        }

        fun setOnSwipeWordListener(listener: (String) -> Unit) {
            if (!isDestroyed) {
                this.onSwipeWordListener = listener
            }
        }

        fun setOnSuggestionClickListener(listener: (String) -> Unit) {
            if (!isDestroyed) {
                this.onSuggestionClickListener = listener
            }
        }

        fun setOnSuggestionLongPressListener(listener: (String) -> Unit) {
            if (!isDestroyed) {
                this.onSuggestionLongPressListener = listener
            }
        }

        fun setOnEmojiSelectedListener(listener: (String) -> Unit) {
            if (!isDestroyed) {
                this.onEmojiSelected = listener
            }
        }

        fun setOnBackspacePressedListener(listener: () -> Unit) {
            if (!isDestroyed) {
                this.onBackspacePressed = listener
            }
        }

        fun setOnSpacebarCursorMoveListener(listener: (Int) -> Unit) {
            if (!isDestroyed) {
                this.onSpacebarCursorMove = listener
            }
        }

        /**
         * Attempts to handle key input for emoji search if search is active.
         * Returns true if the input was consumed by search, false otherwise.
         */
        fun handleSearchInput(key: KeyboardKey): Boolean {
            if (!isEmojiSearchActive) return false

            val searchInput = emojiSearchInput ?: return false
            val editable = searchInput.text ?: return false

            when (key) {
                is KeyboardKey.Character -> {
                    editable.append(key.value)
                    searchInput.setSelection(editable.length)
                    return true
                }

                is KeyboardKey.Action -> {
                    when (key.action) {
                        KeyboardKey.ActionType.BACKSPACE -> {
                            if (editable.isNotEmpty()) {
                                editable.delete(editable.length - 1, editable.length)
                                searchInput.setSelection(editable.length)
                            }
                            return true
                        }

                        else -> {
                            return false
                        }
                    }
                }

                KeyboardKey.Spacer -> {
                    return false
                }
            }
        }

        fun setOnBackspaceSwipeDeleteListener(listener: () -> Unit) {
            if (!isDestroyed) {
                this.onBackspaceSwipeDelete = listener
            }
        }

        fun setPopupActive(active: Boolean) {
            popupActive = active
        }

        fun setCursorSpeed(speed: com.urik.keyboard.settings.CursorSpeed) {
            if (!isDestroyed) {
                currentCursorSpeed = speed
            }
        }

        fun updateDensity() {
            gestureDensity = resources.displayMetrics.density
        }

        private fun insertSuggestionBar() {
            if (isDestroyed) return

            val keyboardView = findKeyboardView() as? LinearLayout ?: return

            suggestionBar?.let { bar ->
                (bar.parent as? ViewGroup)?.removeView(bar)
            }

            val suggestionBarHeight = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
            keyboardView.addView(
                suggestionBar,
                0,
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        suggestionBarHeight,
                    ).apply {
                        setMargins(0, 0, 0, 8)
                    },
            )

            keyboardView.requestLayout()
            safeMappingPost()
        }

        /**
         * Updates suggestion bar with new word candidates.
         *
         */
        fun updateSuggestions(suggestions: List<String>) {
            if (isDestroyed) return
            updateSuggestionBarContent(suggestions)
        }

        private fun updateSuggestionBarContent(suggestions: List<String> = emptyList()) {
            if (isDestroyed) return

            if (isShowingAutofillSuggestions) return

            suggestionBar?.let { bar ->
                val emojiBtn = emojiButton

                returnSuggestionViewsToPool()

                bar.removeAllViews()

                if (suggestions.isNotEmpty()) {
                    populateSuggestions(bar, suggestions)
                } else {
                    val spacer =
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        }
                    bar.addView(spacer)
                }

                emojiBtn?.let { btn ->
                    if (btn.parent != null) {
                        (btn.parent as? ViewGroup)?.removeView(btn)
                    }
                    bar.addView(btn)
                }
            }
        }

        private fun populateSuggestions(
            bar: LinearLayout,
            suggestions: List<String>,
        ) {
            suggestions.take(3).forEachIndexed { index, suggestion ->
                if (isDestroyed) return@forEachIndexed

                val btn = getOrCreateSuggestionView()

                btn.text = suggestion

                val suggestionTextSize = calculateResponsiveSuggestionTextSize()
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, suggestionTextSize)
                btn.setTextColor(
                    themeManager!!
                        .currentTheme.value.colors.suggestionText,
                )

                btn.textDirection =
                    if (currentLayout?.isRTL == true) {
                        TEXT_DIRECTION_RTL
                    } else {
                        TEXT_DIRECTION_LTR
                    }

                btn.maxLines = 1
                btn.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE

                btn.contentDescription = context.getString(R.string.ime_prediction_description, suggestion)

                val horizontalPadding = (suggestionTextSize * context.resources.displayMetrics.density * 1.2f).toInt()
                val verticalPadding = (suggestionTextSize * context.resources.displayMetrics.density * 0.65f).toInt()
                btn.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

                btn.typeface = android.graphics.Typeface.DEFAULT

                btn.setTag(R.id.suggestion_text, suggestion)
                btn.setOnClickListener(suggestionClickListener)
                btn.setOnLongClickListener(suggestionLongClickListener)

                activeSuggestionViews.add(btn)

                btn.gravity =
                    if (suggestions.size == 1) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL

                val layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                bar.addView(btn, layoutParams)

                if (index < suggestions.take(3).size - 1) {
                    val divider = getOrCreateDividerView()

                    divider.setBackgroundColor(
                        themeManager!!
                            .currentTheme.value.colors.keyBorder,
                    )

                    val dividerParams =
                        LinearLayout
                            .LayoutParams(
                                (1 * context.resources.displayMetrics.density).toInt(),
                                LayoutParams.MATCH_PARENT,
                            ).apply {
                                val margin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal) / 2
                                marginStart = margin
                                marginEnd = margin
                            }

                    activeDividerViews.add(divider)
                    bar.addView(divider, dividerParams)
                }
            }
        }

        private fun getOrCreateSuggestionView(): TextView =
            if (suggestionViewPool.isNotEmpty()) {
                suggestionViewPool.removeAt(suggestionViewPool.size - 1)
            } else {
                TextView(context)
            }

        private fun getOrCreateDividerView(): View =
            if (dividerViewPool.isNotEmpty()) {
                dividerViewPool.removeAt(dividerViewPool.size - 1)
            } else {
                View(context)
            }

        private fun returnSuggestionViewsToPool() {
            suggestionBar?.let { bar ->
                activeSuggestionViews.forEach { view ->
                    view.setOnClickListener(null)
                    view.setOnLongClickListener(null)
                    if (suggestionViewPool.size < 10) {
                        suggestionViewPool.add(view)
                    }
                }
                activeSuggestionViews.clear()

                activeDividerViews.forEach { view ->
                    if (dividerViewPool.size < 10) {
                        dividerViewPool.add(view)
                    }
                }
                activeDividerViews.clear()
            }
        }

        private fun showRemovalConfirmation(suggestion: String) {
            if (isDestroyed || confirmationOverlay != null) return

            pendingRemovalSuggestion = suggestion

            confirmationOverlay =
                FrameLayout(context).apply {
                    setBackgroundColor(ContextCompat.getColor(context, android.R.color.black))
                    alpha = 0.8f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                    val container =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            setBackgroundColor(
                                themeManager!!
                                    .currentTheme.value.colors.keyboardBackground,
                            )

                            val padding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal) * 2
                            setPadding(padding, padding, padding, padding)

                            val message =
                                TextView(context).apply {
                                    text = context.getString(R.string.remove_suggestion, suggestion)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                    setTextColor(
                                        themeManager!!
                                            .currentTheme.value.colors.suggestionText,
                                    )
                                    gravity = Gravity.CENTER
                                    setPadding(0, 0, 0, padding)
                                }
                            addView(message)

                            val buttonsContainer =
                                LinearLayout(context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER

                                    val cancelBtn =
                                        Button(context).apply {
                                            text = context.getString(R.string.remove_cancel)
                                            setTextColor(
                                                themeManager!!
                                                    .currentTheme.value.colors.keyTextAction,
                                            )
                                            setBackgroundColor(
                                                themeManager!!
                                                    .currentTheme.value.colors.keyBackgroundAction,
                                            )
                                            setOnClickListener { hideRemovalConfirmation() }
                                        }

                                    val removeBtn =
                                        Button(context).apply {
                                            text = context.getString(R.string.remove_confirm)
                                            setTextColor(ContextCompat.getColor(context, android.R.color.white))
                                            setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                                            setOnClickListener { confirmRemoval() }
                                        }

                                    addView(
                                        cancelBtn,
                                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                            marginEnd = padding / 2
                                        },
                                    )
                                    addView(
                                        removeBtn,
                                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                            marginStart = padding / 2
                                        },
                                    )
                                }
                            addView(buttonsContainer)
                        }

                    addView(
                        container,
                        LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        ),
                    )
                }

            addView(
                confirmationOverlay,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                ),
            )
        }

        private fun hideRemovalConfirmation() {
            confirmationOverlay?.let { overlay ->
                removeView(overlay)
            }
            confirmationOverlay = null
            pendingRemovalSuggestion = null
        }

        private fun confirmRemoval() {
            val suggestion = pendingRemovalSuggestion
            hideRemovalConfirmation()

            if (suggestion != null && !isDestroyed) {
                onSuggestionLongPressListener?.invoke(suggestion)
            }
        }

        private fun calculateResponsiveSuggestionTextSize(): Float {
            val keyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
            val baseTextSize = keyHeight * 0.40f / context.resources.displayMetrics.density
            val minSize = 15f
            val maxSize = 19f

            return baseTextSize.coerceIn(minSize, maxSize)
        }

        /**
         * Clears suggestion bar
         *
         * Call when word buffer empty or committed.
         */
        fun clearSuggestions() {
            if (isDestroyed) return
            if (isShowingAutofillSuggestions) return
            updateSuggestionBarContent(emptyList())
            safeMappingPost()
        }

        fun clearAutofillIfShowing(): Boolean {
            if (isDestroyed || !isShowingAutofillSuggestions) return false
            forceClearAllSuggestions()
            return true
        }

        fun forceClearAllSuggestions() {
            if (isDestroyed) return
            isShowingAutofillSuggestions = false
            autofillIndicatorIcon?.visibility = GONE
            emojiButton?.visibility = VISIBLE
            updateSuggestionBarContent(emptyList())
            safeMappingPost()
        }

        fun updateInlineAutofillSuggestions(
            views: List<View>,
            showIndicator: Boolean,
        ) {
            if (isDestroyed) return

            suggestionBar?.let { bar ->
                returnSuggestionViewsToPool()
                bar.removeAllViews()

                if (views.isEmpty()) {
                    isShowingAutofillSuggestions = false
                    autofillIndicatorIcon?.visibility = GONE
                    emojiButton?.visibility = VISIBLE
                    updateSuggestionBarContent(emptyList())
                    return
                }

                isShowingAutofillSuggestions = true

                emojiButton?.visibility = GONE

                val indicator = getOrCreateAutofillIndicator()
                indicator.visibility = if (showIndicator) VISIBLE else GONE
                (indicator.parent as? ViewGroup)?.removeView(indicator)
                bar.addView(indicator)

                for (i in views.indices) {
                    val view = views[i]
                    val layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f,
                        )

                    (view.parent as? ViewGroup)?.removeView(view)
                    bar.addView(view, layoutParams)

                    if (i < views.size - 1) {
                        val divider = getOrCreateDividerView()
                        divider.setBackgroundColor(
                            themeManager!!
                                .currentTheme.value.colors.keyBorder,
                        )

                        val dividerParams =
                            LinearLayout
                                .LayoutParams(
                                    (1 * context.resources.displayMetrics.density).toInt(),
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                ).apply {
                                    val margin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal) / 2
                                    marginStart = margin
                                    marginEnd = margin
                                }

                        activeDividerViews.add(divider)
                        (divider.parent as? ViewGroup)?.removeView(divider)
                        bar.addView(divider, dividerParams)
                    }
                }

                emojiButton?.let { btn ->
                    if (btn.parent != null) {
                        (btn.parent as? ViewGroup)?.removeView(btn)
                    }
                    bar.addView(btn)
                }

                bar.alpha = 0f
                bar
                    .animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
        }

        private fun getOrCreateAutofillIndicator(): TextView {
            autofillIndicatorIcon?.let { return it }

            return TextView(context).apply {
                val keyDrawable = ContextCompat.getDrawable(context, R.drawable.ic_key)
                keyDrawable?.setTint(
                    themeManager!!
                        .currentTheme.value.colors.keyTextAction,
                )

                setCompoundDrawablesRelativeWithIntrinsicBounds(keyDrawable, null, null, null)

                val suggestionTextSize = calculateResponsiveSuggestionTextSize()
                val padding = (suggestionTextSize * context.resources.displayMetrics.density * 0.6f).toInt()
                setPadding(padding, padding, padding, padding)

                contentDescription = context.getString(R.string.autofill_indicator_description)

                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            0f,
                        ).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            marginEnd = (4 * context.resources.displayMetrics.density).toInt()
                        }

                autofillIndicatorIcon = this
            }
        }

        private fun findKeyboardView(): ViewGroup? {
            if (isDestroyed) return null

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child != swipeOverlay &&
                    child != suggestionBar &&
                    child != emojiPickerContainer &&
                    child != emojiSearchContainer &&
                    child is ViewGroup
                ) {
                    return child
                }
            }
            return null
        }

        /**
         * Rebuilds keyboard layout for new mode/state.
         *
         * Auto-hides emoji picker if showing.
         * Preserves existing suggestions if present.
         * Remaps key positions for swipe detection.
         */
        fun updateKeyboard(
            layout: KeyboardLayout,
            state: KeyboardState,
        ) {
            if (isDestroyed) return

            this.currentLayout = layout
            this.currentState = state

            touchCoordinateTransformer.updateRtlState(layout.isRTL)

            createKeyboardLayout(layout, state)
            safeMappingPost()
        }

        private fun createKeyboardLayout(
            layout: KeyboardLayout,
            state: KeyboardState,
        ) {
            if (isDestroyed) return

            if (isShowingEmojiPicker) {
                hideEmojiPicker()
            }

            clearCollections()

            val existingSuggestions = mutableListOf<String>()
            suggestionBar?.let { bar ->
                for (i in 0 until bar.childCount) {
                    val child = bar.getChildAt(i) as? TextView
                    child?.text?.toString()?.let { text ->
                        if (text.isNotBlank()) {
                            existingSuggestions.add(text)
                        }
                    }
                }
                (bar.parent as? ViewGroup)?.removeView(bar)
            }
            suggestionBar = null

            if (childCount > 1) {
                for (i in childCount - 2 downTo 0) {
                    removeViewAt(i)
                }
            }

            val keyboardView = keyboardLayoutManager?.createKeyboardView(layout, state)

            if (keyboardView is ViewGroup) {
                keyboardLayoutManager?.effectiveLayout?.let { effective ->
                    currentLayout = effective
                }

                addView(
                    keyboardView,
                    childCount - 1,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT,
                    ),
                )

                extractButtonViews(keyboardView)

                suggestionBar =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                        val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
                        val verticalPadding = (basePadding * 0.3f).toInt()
                        setPadding(basePadding, verticalPadding, 0, verticalPadding)

                        val minTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
                        minimumHeight = minTouchTarget
                        setBackgroundColor(
                            themeManager!!
                                .currentTheme.value.colors.suggestionBarBackground,
                        )

                        emojiButton =
                            TextView(context).apply {
                                val emojiDrawable = ContextCompat.getDrawable(context, R.drawable.ic_emoji)
                                emojiDrawable?.setTint(
                                    themeManager!!
                                        .currentTheme.value.colors.keyTextAction,
                                )

                                setCompoundDrawablesRelativeWithIntrinsicBounds(emojiDrawable, null, null, null)

                                val emojiTextSize = calculateResponsiveSuggestionTextSize()

                                val padding = (emojiTextSize * context.resources.displayMetrics.density * 0.8f).toInt()
                                setPadding(padding, padding, padding, padding)
                                setBackgroundColor(
                                    themeManager!!
                                        .currentTheme.value.colors.keyBackgroundAction,
                                )

                                contentDescription = context.getString(R.string.action_emoji)

                                setOnClickListener(emojiButtonClickListener)

                                layoutParams =
                                    LinearLayout
                                        .LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            0f,
                                        ).apply {
                                            gravity = Gravity.END
                                            marginStart = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
                                        }
                            }

                        addView(emojiButton)
                    }

                insertSuggestionBar()

                if (existingSuggestions.isNotEmpty()) {
                    updateSuggestions(existingSuggestions)
                } else {
                    updateSuggestionBarContent()
                }

                post {
                    requestLayout()
                    post {
                        if (!isDestroyed) {
                            mapKeyPositions()
                        }
                    }
                }
            }
        }

        private fun extractButtonViews(viewGroup: ViewGroup) {
            if (isDestroyed) return

            for (i in 0 until viewGroup.childCount) {
                when (val child = viewGroup.getChildAt(i)) {
                    is Button -> {
                        keyViews.add(child)
                        mapButtonToKey(child)
                    }

                    is ViewGroup -> {
                        extractButtonViews(child)
                    }
                }
            }
        }

        private fun mapButtonToKey(button: Button) {
            if (isDestroyed) return

            val layout = currentLayout ?: return

            val buttonIndex = keyViews.indexOf(button)
            if (buttonIndex == -1) return

            var currentIndex = 0
            layout.rows.forEach { row ->
                row.forEach { key ->
                    if (key is KeyboardKey.Spacer) return@forEach

                    if (currentIndex == buttonIndex) {
                        keyMapping[button] = key
                        return
                    }
                    currentIndex++
                }
            }
        }

        private var lastMappedWidth = 0
        private var lastMappedHeight = 0

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && (w != lastMappedWidth || h != lastMappedHeight)) {
                lastMappedWidth = w
                lastMappedHeight = h
                post {
                    if (!isDestroyed && isAttachedToWindow) {
                        mapKeyPositions()
                    }
                }
            }
        }

        private fun safeMappingPost() {
            if (!isDestroyed && isAttachedToWindow && viewTreeObserver.isAlive) {
                viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
            }
        }

        private fun mapKeyPositions() {
            if (isDestroyed || keyViews.isEmpty()) return

            keyViews.forEach { button ->
                if (isDestroyed) return@forEach

                button.getLocationInWindow(cachedLocationArray)
                this.getLocationInWindow(cachedParentLocationArray)

                val localX = cachedLocationArray[0] - cachedParentLocationArray[0]
                val localY = cachedLocationArray[1] - cachedParentLocationArray[1]

                val localRect =
                    Rect(
                        localX,
                        localY,
                        localX + button.width,
                        localY + button.height,
                    )

                keyPositions[button] = localRect

                val key = keyMapping[button]
                if (key is KeyboardKey.Character) {
                    val centerX = localRect.centerX().toFloat()
                    val centerY = localRect.centerY().toFloat()
                    keyCharacterPositions[key] = PointF(centerX, centerY)
                }
            }

            expandEdgeKeyHitAreas()

            swipeDetector?.updateKeyPositions(keyCharacterPositions)

            val charToPosition = mutableMapOf<Char, PointF>()
            keyCharacterPositions.forEach { (key, pos) ->
                if (key.value.isNotEmpty()) {
                    charToPosition[key.value.first()] = pos
                }
            }
            languageManager?.updateKeyPositions(charToPosition)
        }

        private fun expandEdgeKeyHitAreas() {
            if (isDestroyed || keyViews.isEmpty()) return

            val layout = currentLayout ?: return
            val viewWidth = width
            val viewHeight = height

            if (viewWidth <= 0 || viewHeight <= 0) return

            val minTouchTargetPx = (48 * resources.displayMetrics.density).toInt()

            layout.rows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { colIndex, key ->
                    val button = keyViews.getOrNull(getButtonIndexForKey(rowIndex, colIndex)) ?: return@forEachIndexed
                    val rect = keyPositions[button] ?: return@forEachIndexed

                    val isBottomRow = rowIndex == layout.rows.size - 1
                    val isFirstCol = colIndex == 0
                    val isLastCol = colIndex == row.size - 1

                    if (!isBottomRow && !isFirstCol && !isLastCol) return@forEachIndexed

                    val expandedRect = Rect(rect)

                    if (isBottomRow) {
                        expandedRect.bottom = viewHeight
                    }
                    if (isFirstCol) {
                        val maxExpansion = rect.left.coerceAtMost(minTouchTargetPx / 2)
                        expandedRect.left = (rect.left - maxExpansion).coerceAtLeast(0)
                    }
                    if (isLastCol) {
                        val remainingSpace = viewWidth - rect.right
                        val maxExpansion = remainingSpace.coerceAtMost(minTouchTargetPx / 2)
                        expandedRect.right = (rect.right + maxExpansion).coerceAtMost(viewWidth)
                    }

                    keyPositions[button] = expandedRect
                }
            }
        }

        private fun getButtonIndexForKey(
            rowIndex: Int,
            colIndex: Int,
        ): Int {
            val layout = currentLayout ?: return -1
            var index = 0

            for (r in 0 until rowIndex) {
                index += layout.rows[r].count { it !is KeyboardKey.Spacer }
            }
            index += layout.rows[rowIndex].take(colIndex).count { it !is KeyboardKey.Spacer }

            return index
        }

        private fun processGestureMovement(
            x: Float,
            y: Float,
        ) {
            if (isDestroyed) return

            val key = gestureKey ?: return

            when (key.action) {
                KeyboardKey.ActionType.SPACE -> {
                    val totalDx = x - gestureStartX
                    val sensitivity = currentCursorSpeed.sensitivityDp * gestureDensity

                    val positionsToMove = (totalDx / sensitivity).toInt()
                    val lastPositionsMoved = ((gestureLastProcessedX - gestureStartX) / sensitivity).toInt()
                    val deltaPositions = positionsToMove - lastPositionsMoved

                    if (deltaPositions != 0) {
                        onSpacebarCursorMove?.invoke(deltaPositions)
                        gestureLastProcessedX = gestureStartX + (positionsToMove * sensitivity)
                    }
                }

                else -> { }
            }
        }

        private fun finalizeGesture(
            x: Float,
            y: Float,
            key: KeyboardKey.Action?,
        ) {
            if (isDestroyed || key == null) return

            when (key.action) {
                KeyboardKey.ActionType.BACKSPACE -> {
                    val dx = x - gestureStartX
                    val dy = y - gestureStartY
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)
                    val minDistance = com.urik.keyboard.KeyboardConstants.GestureConstants.BACKSPACE_SWIPE_MIN_DISTANCE_DP * gestureDensity

                    if (dx < 0 && absDx > absDy && absDx > minDistance) {
                        keyboardLayoutManager?.triggerBackspaceHaptic()
                        onBackspaceSwipeDelete?.invoke()
                    }
                }

                else -> { }
            }
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!isDestroyed && (isGestureActive || isSwipeActive)) {
                return onTouchEvent(ev)
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (isDestroyed) return false

            if (isTouchInEmojiPicker(ev.x, ev.y)) {
                return false
            }

            if (isTouchInSuggestionBar(ev.x, ev.y)) {
                return false
            }

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartPoint.set(ev.x, ev.y)
                    hasTouchStart = true
                    touchStartTime = System.currentTimeMillis()
                    isSwipeActive = false
                    isGestureActive = false
                    gestureKey = null

                    val key = findKeyAt(ev.x, ev.y)
                    if (key is KeyboardKey.Action &&
                        (key.action == KeyboardKey.ActionType.SPACE || key.action == KeyboardKey.ActionType.BACKSPACE)
                    ) {
                        gestureKey = key
                        gestureStartX = ev.x
                        gestureStartY = ev.y
                        gestureLastProcessedX = ev.x
                    }

                    swipeDetector?.handleTouchEvent(ev) { x, y -> findKeyAt(x, y) }
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (gestureKey != null && !isGestureActive && !popupActive) {
                        val dx = ev.x - gestureStartX
                        val dy = ev.y - gestureStartY
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        val gestureThreshold =
                            com.urik.keyboard.KeyboardConstants.GestureConstants.GESTURE_START_DISTANCE_DP * gestureDensity

                        if (distance > gestureThreshold) {
                            isGestureActive = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            keyboardLayoutManager?.cancelAllPendingCallbacks()
                            keyboardLayoutManager?.dismissVariationPopup()
                            keyboardLayoutManager?.triggerHapticFeedback()
                            return true
                        }
                    }

                    if (isGestureActive) {
                        processGestureMovement(ev.x, ev.y)
                        return true
                    }

                    if (popupActive) {
                        return false
                    }

                    val isSwipe = swipeDetector?.handleTouchEvent(ev) { x, y -> findKeyAt(x, y) } ?: false

                    if (isSwipe && !isSwipeActive) {
                        isSwipeActive = true
                        return true
                    }

                    return isSwipeActive
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasGesture = isGestureActive
                    val wasSwipe = isSwipeActive

                    if (wasGesture || wasSwipe) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }

                    isGestureActive = false
                    gestureKey = null
                    isSwipeActive = false
                    hasTouchStart = false
                    return wasGesture || wasSwipe
                }
            }

            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isDestroyed) return false

            if (isTouchInEmojiPicker(event.x, event.y)) {
                return false
            }

            if (isTouchInSuggestionBar(event.x, event.y)) {
                return false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartPoint.set(event.x, event.y)
                    hasTouchStart = true
                    touchStartTime = System.currentTimeMillis()

                    val key = findKeyAt(event.x, event.y)
                    if (key != null) {
                        swipeDetector?.handleTouchEvent(event) { x, y -> findKeyAt(x, y) }
                        return true
                    }
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isGestureActive) {
                        processGestureMovement(event.x, event.y)
                        return true
                    }

                    if (isSwipeActive) {
                        swipeDetector?.handleTouchEvent(event) { x, y -> findKeyAt(x, y) }
                        return true
                    }

                    val isSwipe = swipeDetector?.handleTouchEvent(event) { x, y -> findKeyAt(x, y) } ?: false
                    if (isSwipe) {
                        isSwipeActive = true
                        return true
                    }

                    return false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val hadTouchStart = hasTouchStart
                    val wasGesture = isGestureActive
                    val currentGestureKey = gestureKey

                    if (wasGesture || isSwipeActive) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }

                    hasTouchStart = false
                    isGestureActive = false
                    gestureKey = null

                    if (wasGesture) {
                        finalizeGesture(event.x, event.y, currentGestureKey)
                        performClick()
                        return true
                    }

                    if (isSwipeActive) {
                        isSwipeActive = false
                        swipeDetector?.handleTouchEvent(event) { x, y -> findKeyAt(x, y) }
                        performClick()
                        return true
                    }

                    if (hadTouchStart) {
                        val dx = event.x - touchStartPoint.x
                        val dy = event.y - touchStartPoint.y
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

                        if (distance < 20f) {
                            findKeyAt(event.x, event.y)?.let { key ->
                                onTap(key)
                                performClick()
                                return true
                            }
                        }
                    }

                    return false
                }
            }

            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun findKeyAt(
            x: Float,
            y: Float,
        ): KeyboardKey? {
            if (isDestroyed) return null

            val normalizedPoint = touchCoordinateTransformer.normalizeForHitDetection(x, y)
            val nx = normalizedPoint.x
            val ny = normalizedPoint.y

            if (keyPositions.isEmpty() && keyViews.isNotEmpty()) {
                return findKeyAtDirect(nx, ny)
            }

            keyPositions.forEach { (button, rect) ->
                if (rect.contains(nx.toInt(), ny.toInt())) {
                    return keyMapping[button]
                }
            }

            var closestButton: Button? = null
            var closestDistance = Float.MAX_VALUE

            keyPositions.forEach { (button, rect) ->
                val centerX = rect.centerX().toFloat()
                val centerY = rect.centerY().toFloat()
                val distance = kotlin.math.sqrt((nx - centerX) * (nx - centerX) + (ny - centerY) * (ny - centerY))

                if (distance < closestDistance) {
                    closestDistance = distance
                    closestButton = button
                }
            }

            return if (closestButton != null) keyMapping[closestButton] else null
        }

        private fun findKeyAtDirect(
            normalizedX: Float,
            normalizedY: Float,
        ): KeyboardKey? {
            this.getLocationInWindow(cachedParentLocationArray)

            keyViews.forEach { button ->
                button.getLocationInWindow(cachedLocationArray)

                val localX = cachedLocationArray[0] - cachedParentLocationArray[0]
                val localY = cachedLocationArray[1] - cachedParentLocationArray[1]

                cachedKeyRect.set(localX, localY, localX + button.width, localY + button.height)

                if (cachedKeyRect.contains(normalizedX.toInt(), normalizedY.toInt())) {
                    return keyMapping[button]
                }
            }

            return null
        }

        override fun onSwipeStart(startPoint: PointF) {
            if (isDestroyed) return
            swipeOverlay.startSwipe(startPoint)
        }

        override fun onSwipeUpdate(currentPoint: PointF) {
            if (isDestroyed) return
            swipeOverlay.updateSwipe(currentPoint)
        }

        override fun onSwipeEnd() {
            if (isDestroyed) return
            keyboardLayoutManager?.triggerHapticFeedback()
            swipeOverlay.endSwipe()
        }

        /**
         * Processes swipe word candidates with learned word boosting.
         */
        override fun onSwipeResults(candidates: List<WordCandidate>) {
            if (isDestroyed) return

            if (candidates.isNotEmpty()) {
                viewScope.launch(Dispatchers.Default) {
                    try {
                        val bestCandidate = selectBestCandidate(candidates)

                        withContext(Dispatchers.Main) {
                            if (!isDestroyed) {
                                onSwipeWordListener?.invoke(bestCandidate)
                            }
                        }
                    } catch (_: Exception) {
                        val fallback =
                            candidates
                                .maxByOrNull {
                                    it.spatialScore * 0.9f + it.frequencyScore * 0.1f
                                }?.word ?: candidates.first().word

                        withContext(Dispatchers.Main) {
                            if (!isDestroyed) {
                                onSwipeWordListener?.invoke(fallback)
                            }
                        }
                    }
                }
            }
        }

        private suspend fun selectBestCandidate(candidates: List<WordCandidate>): String {
            if (candidates.isEmpty()) return ""

            val candidateWords = candidates.take(10).map { it.word }
            val learnedStatus =
                try {
                    wordLearningEngine?.areWordsLearned(candidateWords) ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }

            val candidateScores =
                candidates.take(10).map { candidate ->
                    val isLearned = learnedStatus[candidate.word] ?: false
                    val adjustedScore =
                        if (isLearned) {
                            candidate.combinedScore * 1.8f
                        } else {
                            candidate.combinedScore
                        }
                    candidate to adjustedScore
                }

            val bestCandidate = candidateScores.maxByOrNull { it.second }
            return bestCandidate?.first?.word ?: candidates.first().word
        }

        override fun onTap(key: KeyboardKey) {
            if (isDestroyed) return
            keyboardLayoutManager?.triggerHapticFeedback()

            onKeyClickListener?.invoke(key)
        }

        private fun clearCollections() {
            keyViews.clear()
            keyPositions.clear()
            keyMapping.clear()
            keyCharacterPositions.clear()

            activeSuggestionViews.clear()
            suggestionViewPool.clear()
            activeDividerViews.clear()
            dividerViewPool.clear()
        }

        /**
         * Cleans up all resources and cancels coroutines.
         *
         * Called automatically on detach.
         * Nulls strong references to prevent leaks.
         * Cancels managed viewScope.
         */
        fun cleanup() {
            if (isDestroyed) return
            isDestroyed = true

            viewScopeJob.cancel()

            clearCollections()

            returnSuggestionViewsToPool()
            suggestionViewPool.clear()
            dividerViewPool.clear()

            returnEmojiViewsToPool()
            emojiViewPool.clear()

            suggestionBar?.let { bar ->
                bar.removeAllViews()
                (bar.parent as? ViewGroup)?.removeView(bar)
            }
            suggestionBar = null
            emojiButton = null
            emojiSearchInput = null
            cachedCursorDrawable = null

            searchScopeJob?.cancel()
            searchScopeJob = null
            searchScope = null
            emojiPickerView = null
            searchCloseButton = null
            closeButtonBar = null
            searchButton = null
            backspaceButton = null
            closeButton = null
            searchResultsGrid = null
            emojiSearchInputContainer = null
            emojiSearchResultsContainer = null
            emojiSearchContainer = null
            emojiPickerContainer = null

            swipeOverlay.visibility = GONE

            onKeyClickListener = null
            onSwipeWordListener = null
            onSuggestionClickListener = null
            onSuggestionLongPressListener = null
            onEmojiSelected = null
            onBackspacePressed = null
            onSpacebarCursorMove = null
            onBackspaceSwipeDelete = null

            spellCheckManager = null
            keyboardLayoutManager = null
            swipeDetector?.setSwipeListener(null)
            swipeDetector = null
            themeManager = null
            wordLearningEngine = null

            currentLayout = null
            currentState = null
            hasTouchStart = false
            isSwipeActive = false

            hideRemovalConfirmation()
            hideEmojiPicker()

            removeAllViews()
        }

        override fun onDetachedFromWindow() {
            cleanup()
            super.onDetachedFromWindow()
        }

        private fun getOrCreateEmojiView(emoji: String): TextView {
            val view =
                if (emojiViewPool.isNotEmpty()) {
                    emojiViewPool.removeAt(emojiViewPool.lastIndex)
                } else {
                    val minTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
                    val basePadding = context.resources.getDimensionPixelSize(R.dimen.keyboard_padding)

                    TextView(context).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                        gravity = Gravity.CENTER
                        val emojiPadding = (basePadding * 0.3f).toInt()
                        setPadding(0, emojiPadding, emojiPadding, emojiPadding)
                        minWidth = minTouchTarget
                        minHeight = (minTouchTarget * 0.8f).toInt()
                        setOnClickListener(searchResultEmojiClickListener)
                    }
                }

            view.text = emoji
            view.setTag(R.id.suggestion_text, emoji)

            return view
        }

        private fun returnEmojiViewsToPool() {
            emojiViewPool.addAll(activeEmojiViews)
            activeEmojiViews.clear()
        }

        companion object {
            private const val SEARCH_DEBOUNCE_MS = 300L
        }
    }
