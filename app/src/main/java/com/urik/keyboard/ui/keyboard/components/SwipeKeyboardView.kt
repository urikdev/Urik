package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.emoji2.emojipicker.EmojiPickerView
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.SpellCheckManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        private var spellCheckManager: SpellCheckManager? = null
        private var keyboardLayoutManager: KeyboardLayoutManager? = null
        private var swipeDetector: SwipeDetector? = null

        private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
        private var onSwipeWordListener: ((String) -> Unit)? = null
        private var onSuggestionClickListener: ((String) -> Unit)? = null
        private var onSuggestionLongPressListener: ((String) -> Unit)? = null
        private var onEmojiSelected: ((String) -> Unit)? = null
        private val keyViews = mutableListOf<Button>()
        private val keyPositions = mutableMapOf<Button, Rect>()
        private val keyMapping = mutableMapOf<Button, KeyboardKey>()
        private val keyCharacterPositions = mutableMapOf<KeyboardKey.Character, PointF>()

        private var currentLayout: KeyboardLayout? = null
        private var currentState: KeyboardState? = null

        private val swipeOverlay = SwipeOverlayView(context)

        private var suggestionBar: LinearLayout? = null

        private var emojiButton: TextView? = null

        private var isSwipeActive = false
        private var touchStartPoint: PointF? = null
        private var touchStartTime: Long? = null

        private var confirmationOverlay: FrameLayout? = null
        private var pendingRemovalSuggestion: String? = null

        private var emojiPickerContainer: LinearLayout? = null
        private var isShowingEmojiPicker = false

        private var isDestroyed = false

        private var viewScopeJob = SupervisorJob()
        private var viewScope = CoroutineScope(Dispatchers.Main + viewScopeJob)

        init {
            addView(
                swipeOverlay,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                ),
            )
        }

        /**
         * Shows full-screen emoji picker overlay.
         *
         * Hides keyboard view, shows emoji grid with close button.
         * Auto-hidden on keyboard layout change.
         */
        fun showEmojiPicker(onEmojiSelected: (String) -> Unit) {
            if (isDestroyed || isShowingEmojiPicker) return

            isShowingEmojiPicker = true
            findKeyboardView()?.visibility = GONE

            val baseContext = keyboardLayoutManager?.getThemedContext() ?: context

            val themedContext =
                androidx.appcompat.view.ContextThemeWrapper(
                    baseContext,
                    R.style.Theme_Urik,
                )

            val windowInsets = rootWindowInsets
            val bottomInset =
                if (windowInsets != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        windowInsets.getInsets(WindowInsets.Type.systemBars()).bottom
                    } else {
                        @Suppress("DEPRECATION")
                        windowInsets.systemWindowInsetBottom
                    }
                } else {
                    0
                }

            val container =
                LinearLayout(baseContext).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT,
                        )
                    setBackgroundColor(ContextCompat.getColor(baseContext, R.color.surface_background))
                }

            val closeButtonBar =
                LinearLayout(baseContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setBackgroundColor(ContextCompat.getColor(baseContext, R.color.surface_accent))

                    val padding = baseContext.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
                    setPadding(padding, padding / 2, padding, padding / 2)
                }

            val closeButton =
                TextView(baseContext).apply {
                    text = "✕"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(ContextCompat.getColor(baseContext, R.color.content_primary))

                    val buttonPadding = (18f * baseContext.resources.displayMetrics.density).toInt()
                    setPadding(buttonPadding, buttonPadding / 2, buttonPadding, buttonPadding / 2)

                    setBackgroundColor(ContextCompat.getColor(baseContext, R.color.key_background_action))
                    contentDescription = "Close emoji picker"

                    setOnClickListener {
                        hideEmojiPicker()
                    }
                }

            closeButtonBar.addView(closeButton)
            container.addView(
                closeButtonBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            val emojiPickerView =
                EmojiPickerView(themedContext).apply {
                    setOnEmojiPickedListener { emojiViewItem ->
                        onEmojiSelected(emojiViewItem.emoji)
                        hideEmojiPicker()
                    }
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                0,
                                1f,
                            ).apply {
                                bottomMargin = bottomInset
                            }
                }

            container.addView(emojiPickerView)
            emojiPickerContainer = container
            addView(container, childCount - 1)
        }

        /**
         * Hides emoji picker and restores keyboard view.
         */
        fun hideEmojiPicker() {
            if (isDestroyed || !isShowingEmojiPicker) return

            isShowingEmojiPicker = false

            emojiPickerContainer?.let { container ->
                removeView(container)
            }
            emojiPickerContainer = null

            findKeyboardView()?.visibility = VISIBLE
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val keyboardView = findKeyboardView()

            if (keyboardView != null) {
                keyboardView.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                var keyboardHeight = keyboardView.measuredHeight

                val windowInsets = rootWindowInsets
                if (windowInsets != null) {
                    val bottomInset =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            windowInsets.getInsets(WindowInsets.Type.systemBars()).bottom
                        } else {
                            @Suppress("DEPRECATION")
                            windowInsets.systemWindowInsetBottom
                        }
                    keyboardHeight += bottomInset
                }

                val constrainedHeight = MeasureSpec.makeMeasureSpec(keyboardHeight, MeasureSpec.EXACTLY)
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
        ) {
            if (isDestroyed) return

            this.keyboardLayoutManager = layoutManager
            this.swipeDetector = detector
            this.spellCheckManager = spellCheckManager

            detector.setSwipeListener(this)
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

        private fun insertSuggestionBar() {
            if (isDestroyed) return

            val keyboardView = findKeyboardView() as? LinearLayout
            if (keyboardView == null) return

            suggestionBar?.let { bar ->
                (bar.parent as? ViewGroup)?.removeView(bar)
            }

            keyboardView.addView(
                suggestionBar,
                0,
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
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

            suggestionBar?.let { bar ->
                val emojiBtn = emojiButton
                bar.removeAllViews()

                if (suggestions.isNotEmpty()) {
                    populateSuggestions(bar, suggestions)
                } else {
                    val spacer =
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                        }
                    bar.addView(spacer)
                }

                if (emojiBtn != null) {
                    bar.addView(emojiBtn)
                }
            }
        }

        private fun populateSuggestions(
            bar: LinearLayout,
            suggestions: List<String>,
        ) {
            suggestions.take(3).forEachIndexed { index, suggestion ->
                if (isDestroyed) return@forEachIndexed

                val btn =
                    TextView(context).apply {
                        text = suggestion

                        val suggestionTextSize = calculateResponsiveSuggestionTextSize()
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, suggestionTextSize)
                        setTextColor(ContextCompat.getColor(context, R.color.suggestion_text))

                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE

                        contentDescription = context.getString(R.string.ime_prediction_description, suggestion)

                        val horizontalPadding = (suggestionTextSize * context.resources.displayMetrics.density * 1.2f).toInt()
                        val verticalPadding = (suggestionTextSize * context.resources.displayMetrics.density * 0.65f).toInt()
                        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

                        typeface = android.graphics.Typeface.DEFAULT

                        setOnClickListener {
                            if (!isDestroyed) {
                                keyboardLayoutManager?.triggerHapticFeedback()
                                onSuggestionClickListener?.invoke(suggestion)
                            }
                        }

                        setOnLongClickListener {
                            if (!isDestroyed) {
                                showRemovalConfirmation(suggestion)
                                true
                            } else {
                                false
                            }
                        }
                    }

                val layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

                bar.addView(btn, layoutParams)

                if (index < suggestions.take(3).size - 1) {
                    val divider =
                        View(context).apply {
                            setBackgroundColor(ContextCompat.getColor(context, R.color.suggestion_text))
                        }

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

                    bar.addView(divider, dividerParams)
                }
            }
        }

        private fun showRemovalConfirmation(suggestion: String) {
            if (isDestroyed || confirmationOverlay != null) return

            pendingRemovalSuggestion = suggestion

            confirmationOverlay =
                FrameLayout(context).apply {
                    setBackgroundColor(ContextCompat.getColor(context, android.R.color.black))
                    alpha = 0.8f

                    val container =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_background))

                            val padding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal) * 2
                            setPadding(padding, padding, padding, padding)

                            val message =
                                TextView(context).apply {
                                    text = context.getString(R.string.remove_suggestion, suggestion)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                    setTextColor(ContextCompat.getColor(context, R.color.content_primary))
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
                                            setTextColor(ContextCompat.getColor(context, R.color.content_secondary))
                                            setBackgroundColor(ContextCompat.getColor(context, R.color.key_background_action))
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
            val baseTextSize = keyHeight * 0.30f / context.resources.displayMetrics.density
            val minSize = 13f
            val maxSize = 16f

            return baseTextSize.coerceIn(minSize, maxSize)
        }

        /**
         * Clears suggestion bar
         *
         * Call when word buffer empty or committed.
         */
        fun clearSuggestions() {
            if (isDestroyed) return
            updateSuggestionBarContent(emptyList())
            safeMappingPost()
        }

        private fun findKeyboardView(): ViewGroup? {
            if (isDestroyed) return null

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child != swipeOverlay && child != suggestionBar && child != emojiPickerContainer && child is ViewGroup) {
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
                    child?.text?.toString()?.let {
                        existingSuggestions.add(it)
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

                        val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
                        val verticalPadding = (basePadding * 0.3f).toInt()
                        setPadding(basePadding, verticalPadding, basePadding, verticalPadding)

                        val keyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
                        minimumHeight = (keyHeight * 0.8f).toInt()
                        setBackgroundColor(ContextCompat.getColor(context, R.color.suggestion_bar_background))

                        emojiButton =
                            TextView(context).apply {
                                val emojiDrawable = ContextCompat.getDrawable(context, R.drawable.ic_emoji)
                                emojiDrawable?.setTint(ContextCompat.getColor(context, R.color.key_text_action))

                                setCompoundDrawablesRelativeWithIntrinsicBounds(emojiDrawable, null, null, null)

                                val emojiTextSize = calculateResponsiveSuggestionTextSize()

                                val padding = (emojiTextSize * context.resources.displayMetrics.density * 0.8f).toInt()
                                setPadding(padding, padding, padding, padding)
                                setBackgroundColor(ContextCompat.getColor(context, R.color.key_background_action))

                                contentDescription = context.getString(R.string.action_emoji)

                                setOnClickListener {
                                    if (!isDestroyed) {
                                        showEmojiPicker { selectedEmoji ->
                                            onEmojiSelected?.invoke(selectedEmoji)
                                        }
                                    }
                                }

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
            }
        }

        private fun extractButtonViews(viewGroup: ViewGroup) {
            if (isDestroyed) return

            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                when (child) {
                    is Button -> {
                        keyViews.add(child)
                        mapButtonToKey(child)
                    }
                    is ViewGroup -> extractButtonViews(child)
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
                    if (currentIndex == buttonIndex) {
                        keyMapping[button] = key
                        return
                    }
                    currentIndex++
                }
            }
        }

        private fun safeMappingPost() {
            if (!isDestroyed && isAttachedToWindow && viewTreeObserver.isAlive) {
                viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (!isDestroyed && isAttachedToWindow) {
                                viewTreeObserver.removeOnGlobalLayoutListener(this)
                                mapKeyPositions()
                            }
                        }
                    },
                )
            }
        }

        private fun mapKeyPositions() {
            if (isDestroyed || keyViews.isEmpty()) return

            keyViews.forEach { button ->
                if (isDestroyed) return@forEach

                val buttonLocation = IntArray(2)
                val thisLocation = IntArray(2)

                button.getLocationInWindow(buttonLocation)
                this.getLocationInWindow(thisLocation)

                val localX = buttonLocation[0] - thisLocation[0]
                val localY = buttonLocation[1] - thisLocation[1]

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
        }

        private fun expandEdgeKeyHitAreas() {
            if (isDestroyed || keyViews.isEmpty()) return

            val layout = currentLayout ?: return
            val viewWidth = width
            val viewHeight = height

            if (viewWidth <= 0 || viewHeight <= 0) return

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
                        expandedRect.left = 0
                    }
                    if (isLastCol) {
                        expandedRect.right = viewWidth
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
                index += layout.rows[r].size
            }
            index += colIndex

            return index
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (isDestroyed) return false

            suggestionBar?.let { bar ->
                if (bar.isVisible) {
                    val location = IntArray(2)
                    bar.getLocationInWindow(location)
                    val parentLocation = IntArray(2)
                    this.getLocationInWindow(parentLocation)

                    val barRect =
                        Rect(
                            location[0] - parentLocation[0],
                            location[1] - parentLocation[1],
                            location[0] - parentLocation[0] + bar.width,
                            location[1] - parentLocation[1] + bar.height,
                        )

                    if (barRect.contains(ev.x.toInt(), ev.y.toInt())) {
                        return false
                    }
                }
            }

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartPoint = PointF(ev.x, ev.y)
                    touchStartTime = System.currentTimeMillis()
                    isSwipeActive = false

                    swipeDetector?.handleTouchEvent(ev) { x, y -> findKeyAt(x, y) }
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val touchDuration = System.currentTimeMillis() - (touchStartTime ?: System.currentTimeMillis())

                    touchStartPoint?.let { start ->
                        val distance =
                            kotlin.math.sqrt(
                                (ev.x - start.x) * (ev.x - start.x) + (ev.y - start.y) * (ev.y - start.y),
                            )

                        if (distance > 60f && touchDuration > 200L) {
                            val isSwipe = swipeDetector?.handleTouchEvent(ev) { x, y -> findKeyAt(x, y) } ?: false

                            if (isSwipe && !isSwipeActive) {
                                isSwipeActive = true
                                return true
                            }
                        }
                    }

                    return isSwipeActive
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasSwipe = isSwipeActive
                    isSwipeActive = false
                    touchStartPoint = null
                    touchStartTime = null
                    return wasSwipe
                }
            }

            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isDestroyed) return false

            if (isSwipeActive) {
                val handled =
                    swipeDetector?.handleTouchEvent(event) { x, y ->
                        findKeyAt(x, y)
                    } ?: false

                if (event.action == MotionEvent.ACTION_UP && handled) {
                    performClick()
                }

                return if (handled) true else super.onTouchEvent(event)
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - (touchStartTime ?: 0)
                    if (duration <= 350L) {
                        val key = findKeyAt(event.x, event.y)
                        if (key != null) {
                            keyboardLayoutManager?.triggerHapticFeedback()
                            onKeyClickListener?.invoke(key)
                            performClick()
                            return true
                        }
                    }
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

            keyPositions.forEach { (button, rect) ->
                if (rect.contains(x.toInt(), y.toInt())) {
                    return keyMapping[button]
                }
            }

            var closestButton: Button? = null
            var closestDistance = Float.MAX_VALUE

            keyPositions.forEach { (button, rect) ->
                val centerX = rect.centerX().toFloat()
                val centerY = rect.centerY().toFloat()
                val distance = kotlin.math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY))

                if (distance < closestDistance) {
                    closestDistance = distance
                    closestButton = button
                }
            }

            val maxTouchDistance = 60f * context.resources.displayMetrics.density
            if (closestButton != null && closestDistance <= maxTouchDistance) {
                return keyMapping[closestButton]
            }

            return null
        }

        override fun onSwipeStart(startPoint: PointF) {
            if (isDestroyed) return
            swipeOverlay.startSwipe(startPoint)
        }

        override fun onSwipeUpdate(
            currentPath: SwipeDetector.SwipePath,
            currentPoint: PointF,
        ) {
            if (isDestroyed) return
            swipeOverlay.updateSwipe(currentPoint)
        }

        override fun onSwipeEnd(finalPath: SwipeDetector.SwipePath) {
            if (isDestroyed) return
            swipeOverlay.endSwipe()
        }

        /**
         * Processes swipe word candidates with learned word boosting.
         *
         * Async flow:
         * 1. Batch check learned status for top 10 candidates
         * 2. Learned words: base 0.95 + spatial*0.05
         * 3. Dictionary: spatial*0.85 + frequency*0.15
         * 4. Select best, invoke onSwipeWord callback
         *
         * Exception handling: Falls back to spatial+frequency if batch check fails.
         */
        override fun onSwipeResults(candidates: List<WordCandidate>) {
            if (isDestroyed) return

            if (candidates.isNotEmpty()) {
                viewScope.launch {
                    try {
                        val bestCandidate = selectBestCandidate(candidates)

                        if (!isDestroyed) {
                            onSwipeWordListener?.invoke(bestCandidate)
                        }
                    } catch (_: Exception) {
                        val fallback =
                            candidates
                                .maxByOrNull {
                                    it.spatialScore * 0.9f + it.frequencyScore * 0.1f
                                }?.word ?: candidates.first().word

                        if (!isDestroyed) {
                            onSwipeWordListener?.invoke(fallback)
                        }
                    }
                }
            }
        }

        private suspend fun selectBestCandidate(candidates: List<WordCandidate>): String {
            val candidateScores = mutableListOf<Pair<WordCandidate, Float>>()

            val candidateWords = candidates.take(10).map { it.word }
            val learnedStatus =
                try {
                    spellCheckManager?.areWordsInDictionary(candidateWords) ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }

            for (candidate in candidates.take(10)) {
                val isLearned = learnedStatus[candidate.word] ?: false

                val adjustedScore =
                    if (isLearned) {
                        0.95f + (candidate.spatialScore * 0.05f)
                    } else {
                        candidate.spatialScore * 0.85f + candidate.frequencyScore * 0.15f
                    }

                candidateScores.add(candidate to adjustedScore)
            }

            val bestCandidate = candidateScores.maxByOrNull { it.second }
            return bestCandidate?.first?.word ?: candidates.first().word
        }

        override fun onTap(key: KeyboardKey) {
            if (isDestroyed) return
            onKeyClickListener?.invoke(key)
        }

        private fun clearCollections() {
            keyViews.clear()
            keyPositions.clear()
            keyMapping.clear()
            keyCharacterPositions.clear()
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

            suggestionBar?.let { bar ->
                bar.removeAllViews()
                (bar.parent as? ViewGroup)?.removeView(bar)
            }
            suggestionBar = null
            emojiButton = null

            swipeOverlay.visibility = GONE

            onKeyClickListener = null
            onSwipeWordListener = null
            onSuggestionClickListener = null
            onSuggestionLongPressListener = null
            onEmojiSelected = null

            spellCheckManager = null
            keyboardLayoutManager = null
            swipeDetector = null

            currentLayout = null
            currentState = null
            touchStartPoint = null
            touchStartTime = null
            isSwipeActive = false

            hideRemovalConfirmation()
            hideEmojiPicker()

            removeAllViews()
        }

        override fun onDetachedFromWindow() {
            cleanup()
            super.onDetachedFromWindow()
        }
    }
