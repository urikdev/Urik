package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardModeConfig
import com.urik.keyboard.theme.ThemeColors
import com.urik.keyboard.theme.ThemeManager

/**
 * Container that applies adaptive layout transformations to the keyboard.
 *
 * Handles one-handed mode, split mode, and floating mode by:
 * - Scaling/positioning the keyboard view
 * - Showing mode toggle controls in the gap area
 * - Notifying listeners of coordinate transformations for gesture handling
 */
class AdaptiveKeyboardContainer
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private var keyboardView: View? = null
        private var currentConfig: KeyboardModeConfig = KeyboardModeConfig.standard()
        private var hingeBounds: Rect? = null
        private var themeManager: ThemeManager? = null

        private var onLayoutTransformListener: ((scaleFactor: Float, offsetX: Float) -> Unit)? = null
        private var onModeToggleListener: ((KeyboardDisplayMode) -> Unit)? = null

        private var modeToggleBar: LinearLayout? = null
        private var leftButton: ImageView? = null
        private var centerButton: ImageView? = null
        private var rightButton: ImageView? = null

        private var containerWidth = 0
        private var pendingModeApplication = false

        private val layoutListener =
            ViewTreeObserver.OnGlobalLayoutListener {
                val newWidth = width
                if (newWidth > 0 && newWidth != containerWidth) {
                    containerWidth = newWidth
                    if (pendingModeApplication) {
                        pendingModeApplication = false
                        applyCurrentModeInternal()
                    }
                }
            }

        init {
            viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }

        fun setThemeManager(manager: ThemeManager) {
            themeManager = manager
            updateThemeColors()
        }

        fun setOnLayoutTransformListener(listener: (Float, Float) -> Unit) {
            onLayoutTransformListener = listener
        }

        fun setOnModeToggleListener(listener: (KeyboardDisplayMode) -> Unit) {
            onModeToggleListener = listener
        }

        fun setKeyboardView(view: View) {
            keyboardView?.let { removeView(it) }
            keyboardView = view
            addView(
                view,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM
                },
            )
            requestModeApplication()
        }

        fun setModeConfig(
            config: KeyboardModeConfig,
            hingeBounds: Rect? = null,
        ) {
            this.currentConfig = config
            this.hingeBounds = hingeBounds
            requestModeApplication()
        }

        private fun requestModeApplication() {
            if (containerWidth > 0) {
                applyCurrentModeInternal()
            } else {
                pendingModeApplication = true
            }
        }

        private fun applyCurrentModeInternal() {
            val view = keyboardView ?: return
            if (containerWidth <= 0) return

            when (currentConfig.mode) {
                KeyboardDisplayMode.STANDARD -> applyStandardMode(view)
                KeyboardDisplayMode.ONE_HANDED_LEFT -> applyOneHandedMode(view, anchorLeft = true)
                KeyboardDisplayMode.ONE_HANDED_RIGHT -> applyOneHandedMode(view, anchorLeft = false)
                KeyboardDisplayMode.SPLIT -> applySplitMode(view)
            }

            updateToggleBarState()
            notifyLayoutTransform()
        }

        private fun applyStandardMode(view: View) {
            val params =
                view.layoutParams as? LayoutParams
                    ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

            params.width = LayoutParams.MATCH_PARENT
            params.gravity = Gravity.BOTTOM
            params.marginStart = 0
            params.marginEnd = 0
            view.layoutParams = params
        }

        private fun applyOneHandedMode(
            view: View,
            anchorLeft: Boolean,
        ) {
            val params =
                view.layoutParams as? LayoutParams
                    ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

            val keyboardWidth = (containerWidth * currentConfig.widthFactor).toInt()
            params.width = keyboardWidth
            params.gravity = Gravity.BOTTOM or (if (anchorLeft) Gravity.START else Gravity.END)
            params.marginStart = 0
            params.marginEnd = 0
            view.layoutParams = params
        }

        private fun applySplitMode(view: View) {
            val params =
                view.layoutParams as? LayoutParams
                    ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

            params.width = LayoutParams.MATCH_PARENT
            params.gravity = Gravity.BOTTOM
            params.marginStart = 0
            params.marginEnd = 0
            view.layoutParams = params
        }

        private fun updateToggleBarState() {
            val isOneHanded =
                currentConfig.mode == KeyboardDisplayMode.ONE_HANDED_LEFT ||
                    currentConfig.mode == KeyboardDisplayMode.ONE_HANDED_RIGHT

            if (isOneHanded) {
                ensureToggleBarCreated()
                positionToggleBar()
                modeToggleBar?.visibility = VISIBLE
                updateToggleButtonStates()
            } else {
                modeToggleBar?.visibility = GONE
            }
        }

        private fun ensureToggleBarCreated() {
            if (modeToggleBar != null) return

            val density = resources.displayMetrics.density
            val buttonSize = (48 * density).toInt()
            val buttonMargin = (8 * density).toInt()

            modeToggleBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams =
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
                }

            leftButton =
                createToggleButton(R.drawable.arrow_back_48px, R.string.one_handed_mode_left) {
                    onModeToggleListener?.invoke(KeyboardDisplayMode.ONE_HANDED_LEFT)
                }

            centerButton =
                createToggleButton(R.drawable.space_bar_48px, R.string.one_handed_mode_full) {
                    onModeToggleListener?.invoke(KeyboardDisplayMode.STANDARD)
                }

            rightButton =
                createToggleButton(R.drawable.arrow_forward_48px, R.string.one_handed_mode_right) {
                    onModeToggleListener?.invoke(KeyboardDisplayMode.ONE_HANDED_RIGHT)
                }

            val buttonParams =
                LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                    setMargins(buttonMargin, buttonMargin, buttonMargin, buttonMargin)
                }

            modeToggleBar?.addView(leftButton, buttonParams)
            modeToggleBar?.addView(centerButton, buttonParams)
            modeToggleBar?.addView(rightButton, buttonParams)

            addView(modeToggleBar)
            updateThemeColors()
        }

        private fun createToggleButton(
            iconRes: Int,
            contentDescRes: Int,
            onClick: () -> Unit,
        ): ImageView =
            ImageView(context).apply {
                setImageResource(iconRes)
                contentDescription = context.getString(contentDescRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true

                val padding = (8 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)

                setOnClickListener { onClick() }
            }

        private fun positionToggleBar() {
            val bar = modeToggleBar ?: return
            val params = bar.layoutParams as? LayoutParams ?: return

            val gapWidth = (containerWidth * (1f - currentConfig.widthFactor)).toInt()
            params.width = gapWidth

            when (currentConfig.mode) {
                KeyboardDisplayMode.ONE_HANDED_LEFT -> {
                    params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    params.marginEnd = 0
                    params.marginStart = 0
                }
                KeyboardDisplayMode.ONE_HANDED_RIGHT -> {
                    params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    params.marginStart = 0
                    params.marginEnd = 0
                }
                else -> {}
            }

            bar.layoutParams = params
        }

        private fun updateToggleButtonStates() {
            val colors = themeManager?.currentTheme?.value?.colors ?: return

            val activeColor = colors.stateActivated
            val inactiveColor = colors.keyBackgroundAction

            when (currentConfig.mode) {
                KeyboardDisplayMode.ONE_HANDED_LEFT -> {
                    setButtonBackground(leftButton, activeColor, colors)
                    setButtonBackground(centerButton, inactiveColor, colors)
                    setButtonBackground(rightButton, inactiveColor, colors)
                }
                KeyboardDisplayMode.ONE_HANDED_RIGHT -> {
                    setButtonBackground(leftButton, inactiveColor, colors)
                    setButtonBackground(centerButton, inactiveColor, colors)
                    setButtonBackground(rightButton, activeColor, colors)
                }
                else -> {
                    setButtonBackground(leftButton, inactiveColor, colors)
                    setButtonBackground(centerButton, inactiveColor, colors)
                    setButtonBackground(rightButton, inactiveColor, colors)
                }
            }
        }

        private fun setButtonBackground(
            button: ImageView?,
            backgroundColor: Int,
            colors: ThemeColors,
        ) {
            button ?: return

            val cornerRadius = 12 * resources.displayMetrics.density
            val backgroundDrawable =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(backgroundColor)
                    this.cornerRadius = cornerRadius
                }

            val rippleColor = ColorStateList.valueOf(colors.statePressed)
            val rippleDrawable = RippleDrawable(rippleColor, backgroundDrawable, null)

            button.background = rippleDrawable
            button.setColorFilter(colors.keyTextAction)
        }

        private fun updateThemeColors() {
            val colors = themeManager?.currentTheme?.value?.colors ?: return

            modeToggleBar?.setBackgroundColor(colors.keyboardBackground)
            updateToggleButtonStates()
        }

        private fun notifyLayoutTransform() {
            onLayoutTransformListener?.invoke(1.0f, 0f)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }
    }
