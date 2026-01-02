package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.drawable.toDrawable
import com.urik.keyboard.R
import com.urik.keyboard.theme.ThemeManager

/**
 * Character variation popup for long-press key menu.
 *
 */
class CharacterVariationPopup(
    private val context: Context,
    private val themeManager: ThemeManager,
) : PopupWindow() {
    private var onVariationSelected: ((String) -> Unit)? = null
    private val characterButtons = mutableListOf<Button>()
    private var highlightedButton: Button? = null

    private val scrollView: HorizontalScrollView =
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }

    private val variationContainer: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            val density = context.resources.displayMetrics.density
            val paddingH = (8 * density).toInt()
            val paddingV = (4 * density).toInt()
            setPadding(paddingH, paddingV, paddingH, paddingV)

            setBackgroundColor(themeManager.currentTheme.value.colors.keyBackgroundAction)
            elevation = 8f * density
        }

    init {
        scrollView.addView(variationContainer)
        contentView = scrollView
        isOutsideTouchable = true
        isFocusable = false
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        inputMethodMode = INPUT_METHOD_NOT_NEEDED

        setOnDismissListener {
            onVariationSelected = null
            variationContainer.removeAllViews()
        }
    }

    /**
     * Configures popup with character variations.
     *
     * @param baseChar Base character to highlight (e.g., "a")
     * @param variations Available variations (e.g., ["á", "à", "â"])
     * @param onSelected Callback when variation selected
     */
    fun setCharacterVariations(
        baseChar: String,
        variations: List<String>,
        onSelected: (String) -> Unit,
    ) {
        this.onVariationSelected = onSelected

        variationContainer.removeAllViews()
        characterButtons.clear()
        highlightedButton = null

        val totalCount = variations.size + if (baseChar.isNotEmpty()) 1 else 0

        if (baseChar.isNotEmpty()) {
            addCharacterView(baseChar, isBase = true, index = 0, totalCount = totalCount)
        }

        variations.forEachIndexed { index, variation ->
            val actualIndex = if (baseChar.isNotEmpty()) index + 1 else index
            addCharacterView(variation, isBase = false, index = actualIndex, totalCount = totalCount)
        }

        val density = context.resources.displayMetrics.density
        val itemSize = (40 * density).toInt()
        val totalItems = variations.size + if (baseChar.isNotEmpty()) 1 else 0
        val idealWidth = totalItems * itemSize + (16 * density).toInt()
        val popupHeight = itemSize + (8 * density).toInt()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val maxWidth = screenWidth - (32 * density).toInt()

        width = minOf(idealWidth, maxWidth)
        height = popupHeight
    }

    private fun addCharacterView(
        char: String,
        isBase: Boolean,
        index: Int,
        totalCount: Int,
    ) {
        val density = context.resources.displayMetrics.density

        val button =
            Button(context).apply {
                text = char

                val buttonSize = (36 * density).toInt()
                layoutParams =
                    LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                        val margin = (2 * density).toInt()
                        setMargins(margin, 0, margin, 0)
                    }

                textSize = 14f

                val theme = themeManager.currentTheme.value
                setTextColor(
                    if (isBase) {
                        theme.colors.keyTextAction
                    } else {
                        theme.colors.keyTextCharacter
                    },
                )

                val backgroundColor =
                    if (isBase) {
                        theme.colors.keyBackgroundAction
                    } else {
                        theme.colors.keyBackgroundCharacter
                    }

                val cornerRadius = 8f * density
                background =
                    GradientDrawable().apply {
                        setColor(backgroundColor)
                        this.cornerRadius = cornerRadius
                        setStroke(
                            (1 * density).toInt(),
                            theme.colors.keyBorder,
                        )
                    }

                minHeight = 0
                minimumHeight = 0
                includeFontPadding = false
                setPadding(0, 0, 0, 0)

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    onVariationSelected?.invoke(char)
                    dismiss()
                }

                contentDescription =
                    context.getString(
                        R.string.character_variation_position,
                        index + 1,
                        totalCount,
                        char,
                    )
            }

        characterButtons.add(button)
        variationContainer.addView(button)
    }

    /**
     * Shows popup intelligently positioned relative to anchor key.
     *
     * Positioning strategy:
     * 1. Prefers showing above anchor (standard long-press behavior)
     * 2. Falls back to below if insufficient space above
     * 3. Centers horizontally on anchor, respects screen edges
     * 4. Ensures popup fully visible within screen bounds
     *
     * @param anchorView Key view that triggered the popup
     */
    fun showAboveAnchor(anchorView: View) {
        if (!anchorView.isAttachedToWindow || anchorView.windowToken == null) {
            return
        }

        val density = context.resources.displayMetrics.density
        val gap = (8 * density).toInt()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)
        val anchorX = anchorLocation[0]
        val anchorY = anchorLocation[1]
        val anchorWidth = anchorView.width
        val anchorHeight = anchorView.height

        val spaceBelow = screenHeight - (anchorY + anchorHeight)

        val showAbove = anchorY >= (height + gap) || anchorY > spaceBelow

        val verticalOffset =
            if (showAbove) {
                -(anchorHeight + height + gap)
            } else {
                gap
            }

        var horizontalOffset = (anchorWidth - width) / 2

        val popupLeft = anchorX + horizontalOffset
        val popupRight = popupLeft + width

        if (popupLeft < 0) {
            horizontalOffset -= popupLeft
        } else if (popupRight > screenWidth) {
            horizontalOffset -= (popupRight - screenWidth)
        }

        super.showAsDropDown(anchorView, horizontalOffset, verticalOffset)
    }

    /**
     * Dismisses popup if showing.
     *
     * Safe to call multiple times.
     */
    fun cleanup() {
        if (isShowing) {
            dismiss()
        }
    }

    fun getCharacterAt(
        rawX: Float,
        rawY: Float,
    ): String? {
        if (!isShowing) {
            return null
        }

        val location = IntArray(2)
        contentView.getLocationOnScreen(location)
        val localX = rawX - location[0]
        val localY = rawY - location[1]

        for (button in characterButtons) {
            val buttonLeft = button.left
            val buttonTop = button.top
            val buttonRight = button.right
            val buttonBottom = button.bottom

            val inHorizontalRange = localX >= buttonLeft && localX <= buttonRight
            val inVerticalRange = localY >= buttonTop && localY <= buttonBottom

            if (inHorizontalRange && inVerticalRange) {
                return button.text.toString()
            }
        }

        return null
    }

    fun setHighlighted(char: String?) {
        val theme = themeManager.currentTheme.value
        val density = context.resources.displayMetrics.density

        highlightedButton?.let { button ->
            val isBase = button == characterButtons.firstOrNull()
            val backgroundColor =
                if (isBase) {
                    theme.colors.keyBackgroundAction
                } else {
                    theme.colors.keyBackgroundCharacter
                }

            val cornerRadius = 8f * density
            button.background =
                GradientDrawable().apply {
                    setColor(backgroundColor)
                    this.cornerRadius = cornerRadius
                    setStroke(
                        (1 * density).toInt(),
                        theme.colors.keyBorder,
                    )
                }
        }

        highlightedButton = null

        if (char != null) {
            val button = characterButtons.find { it.text == char }
            if (button != null) {
                highlightedButton = button
                val cornerRadius = 8f * density
                button.background =
                    GradientDrawable().apply {
                        setColor(theme.colors.statePressed)
                        this.cornerRadius = cornerRadius
                        setStroke(
                            (1 * density).toInt(),
                            theme.colors.keyBorder,
                        )
                    }
            }
        }
    }

    fun getHighlightedCharacter(): String? = highlightedButton?.text?.toString()
}
