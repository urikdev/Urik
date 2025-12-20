package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.drawable.toDrawable
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.theme.ThemeManager

class LanguagePickerPopup(
    private val context: Context,
    private val themeManager: ThemeManager,
) : PopupWindow() {
    private var onLanguageSelected: ((String) -> Unit)? = null

    private val languageContainer: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            val density = context.resources.displayMetrics.density
            val paddingH = (8 * density).toInt()
            val paddingV = (4 * density).toInt()
            setPadding(paddingH, paddingV, paddingH, paddingV)

            setBackgroundColor(themeManager.currentTheme.value.colors.keyBackgroundAction)
            elevation = 8f * density
        }

    init {
        contentView = languageContainer
        isOutsideTouchable = true
        isFocusable = false
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        inputMethodMode = INPUT_METHOD_NOT_NEEDED

        setOnDismissListener {
            onLanguageSelected = null
            languageContainer.removeAllViews()
        }
    }

    fun setLanguages(
        languages: List<String>,
        currentLanguage: String,
        onSelected: (String) -> Unit,
    ) {
        this.onLanguageSelected = onSelected

        languageContainer.removeAllViews()

        languages.forEach { languageCode ->
            addLanguageButton(languageCode, languageCode == currentLanguage)
        }

        val density = context.resources.displayMetrics.density
        val buttonWidth = (120 * density).toInt()
        val buttonHeight = (40 * density).toInt()
        val totalHeight = (languages.size * buttonHeight) + ((languages.size - 1) * 4 * density).toInt() + (8 * density).toInt()

        width = buttonWidth + (16 * density).toInt()
        height = totalHeight
    }

    private fun addLanguageButton(
        languageCode: String,
        isCurrent: Boolean,
    ) {
        val density = context.resources.displayMetrics.density

        val button =
            Button(context).apply {
                val displayName = KeyboardSettings.getLanguageDisplayNames()[languageCode] ?: languageCode
                text = if (isCurrent) "✓ $displayName" else displayName

                val buttonWidth = (120 * density).toInt()
                val buttonHeight = (40 * density).toInt()
                layoutParams =
                    LinearLayout.LayoutParams(buttonWidth, buttonHeight).apply {
                        val margin = (2 * density).toInt()
                        setMargins(0, margin, 0, margin)
                    }

                textSize = 14f

                val theme = themeManager.currentTheme.value
                setTextColor(
                    if (isCurrent) {
                        theme.colors.keyTextAction
                    } else {
                        theme.colors.keyTextCharacter
                    },
                )

                val backgroundColor =
                    if (isCurrent) {
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
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    onLanguageSelected?.invoke(languageCode)
                    dismiss()
                }

                contentDescription = displayName
            }

        languageContainer.addView(button)
    }

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

    fun cleanup() {
        if (isShowing) {
            dismiss()
        }
    }
}
