package com.urik.keyboard.settings.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.theme.KeyboardTheme

/**
 * Renders non-interactive keyboard preview for theme selection.
 * Uses same dimension calculations as KeyboardLayoutManager for consistency.
 */
class KeyboardPreviewRenderer(
    private val context: Context,
) {
    fun createPreviewView(
        layout: KeyboardLayout,
        theme: KeyboardTheme,
    ): View {
        val rowsToRender = layout.rows.takeLast(2)

        val keySize = KeySize.MEDIUM
        val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
        val baseMinTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
        val baseKeyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
        val baseHorizontalMargin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)

        val keySizeMultiplier = keySize.scaleFactor

        val minTarget = (baseMinTouchTarget * keySizeMultiplier).toInt()
        val keyHeight = (baseKeyHeight * keySizeMultiplier).toInt()
        val horizontalPadding = (basePadding * keySizeMultiplier).toInt()
        val verticalPadding = (basePadding * 0.5f * keySizeMultiplier).toInt()
        val horizontalMargin = (baseHorizontalMargin * keySizeMultiplier).toInt()

        val visualHeight = keyHeight + 2
        val verticalMargin = (minTarget - visualHeight) / 2

        val keyboardContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )

                val kbHorizontalPadding = context.resources.getDimensionPixelSize(R.dimen.keyboard_padding)
                val kbVerticalPadding = context.resources.getDimensionPixelSize(R.dimen.keyboard_padding_vertical)
                setPadding(kbHorizontalPadding, kbVerticalPadding, kbHorizontalPadding, kbVerticalPadding)

                setBackgroundColor(theme.colors.keyboardBackground)
            }

        val textSize = calculateTextSize(keyHeight, keySize)

        rowsToRender.forEach { row ->
            val rowView =
                createRowView(
                    row,
                    theme,
                    visualHeight,
                    horizontalMargin,
                    verticalMargin,
                    horizontalPadding,
                    verticalPadding,
                    textSize,
                )
            keyboardContainer.addView(rowView)
        }

        val scaledContainer =
            android.widget.FrameLayout(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                addView(keyboardContainer)

                viewTreeObserver.addOnGlobalLayoutListener(
                    object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            viewTreeObserver.removeOnGlobalLayoutListener(this)

                            if (width > 0 && keyboardContainer.width > 0 && keyboardContainer.height > 0) {
                                val scaleX = width.toFloat() / keyboardContainer.width.toFloat()
                                val scaleY = scaleX // Keep aspect ratio

                                keyboardContainer.pivotX = 0f
                                keyboardContainer.pivotY = 0f
                                keyboardContainer.scaleX = scaleX
                                keyboardContainer.scaleY = scaleY

                                val scaledHeight = (keyboardContainer.height * scaleY).toInt()
                                layoutParams.height = scaledHeight
                                requestLayout()
                            }
                        }
                    },
                )
            }

        return scaledContainer
    }

    private fun calculateTextSize(
        keyHeight: Int,
        keySize: KeySize,
    ): Float {
        val baseTextSize = keyHeight * 0.38f / context.resources.displayMetrics.density
        val minSize = 12f
        val maxSize =
            when (keySize) {
                KeySize.EXTRA_LARGE -> 16f
                else -> 24f
            }
        return baseTextSize.coerceIn(minSize, maxSize)
    }

    private fun createRowView(
        keys: List<KeyboardKey>,
        theme: KeyboardTheme,
        visualHeight: Int,
        horizontalMargin: Int,
        verticalMargin: Int,
        horizontalPadding: Int,
        verticalPadding: Int,
        textSize: Float,
    ): LinearLayout {
        val rowLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            val rowVerticalMargin = context.resources.getDimensionPixelSize(R.dimen.key_margin_vertical)
                            setMargins(0, 0, 0, rowVerticalMargin)
                        }
            }

        val is9LetterRow = is9CharacterLetterRow(keys)

        if (is9LetterRow) {
            val spacer =
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 0.5f)
                }
            rowLayout.addView(spacer)
        }

        keys.forEach { key ->
            val keyButton =
                createKeyButton(
                    key,
                    keys,
                    theme,
                    visualHeight,
                    horizontalMargin,
                    verticalMargin,
                    horizontalPadding,
                    verticalPadding,
                    textSize,
                )
            rowLayout.addView(keyButton)
        }

        if (is9LetterRow) {
            val spacer =
                View(context).apply {
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

    private fun createKeyButton(
        key: KeyboardKey,
        rowKeys: List<KeyboardKey>,
        theme: KeyboardTheme,
        visualHeight: Int,
        horizontalMargin: Int,
        verticalMargin: Int,
        horizontalPadding: Int,
        verticalPadding: Int,
        textSize: Float,
    ): Button {
        val state = KeyboardState()
        val button = Button(context)

        button.apply {
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        0,
                        visualHeight,
                        getKeyWeight(key),
                    ).apply {
                        setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
                    }

            text = getKeyLabel(key, state)

            setTextAppearance(R.style.KeyTextAppearance)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            maxLines = 1
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT

            minHeight = 0
            minimumHeight = 0

            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            val textColor = getKeyTextColor(key, theme)
            setTextColor(textColor)

            if (key is KeyboardKey.Action) {
                val iconRes = getActionIconRes(key)

                if (iconRes != 0) {
                    val keyBackground = getKeyBackgroundDrawable(key, theme)
                    val iconDrawable = ContextCompat.getDrawable(context, iconRes)

                    iconDrawable?.setTint(textColor)

                    val layerDrawable = LayerDrawable(arrayOf(keyBackground, iconDrawable))
                    layerDrawable.setLayerInset(1, 12, 12, 12, 12)
                    layerDrawable.setLayerGravity(1, Gravity.CENTER)

                    background = layerDrawable
                    text = ""
                } else {
                    background = getKeyBackgroundDrawable(key, theme)
                }
            } else {
                background = getKeyBackgroundDrawable(key, theme)
            }

            isClickable = false
            isFocusable = false
            isEnabled = true
        }

        return button
    }

    private fun getKeyWeight(key: KeyboardKey): Float =
        when (key) {
            is KeyboardKey.Action -> {
                when (key.action) {
                    KeyboardKey.ActionType.SHIFT -> 1.5f

                    KeyboardKey.ActionType.BACKSPACE -> 1.5f

                    KeyboardKey.ActionType.SPACE -> 4.0f

                    // Default SpaceBarSize.STANDARD
                    else -> 1.5f
                }
            }

            else -> {
                1f
            }
        }

    private fun getKeyTextColor(
        key: KeyboardKey,
        theme: KeyboardTheme,
    ): Int =
        when (key) {
            is KeyboardKey.Action -> theme.colors.keyTextAction
            else -> theme.colors.keyTextCharacter
        }

    private fun getKeyBackgroundDrawable(
        key: KeyboardKey,
        theme: KeyboardTheme,
    ): GradientDrawable {
        val backgroundColor =
            when (key) {
                is KeyboardKey.Action -> {
                    when (key.action) {
                        KeyboardKey.ActionType.SPACE -> theme.colors.keyBackgroundSpace
                        else -> theme.colors.keyBackgroundAction
                    }
                }

                else -> {
                    theme.colors.keyBackgroundCharacter
                }
            }

        val cornerRadius = 8f * context.resources.displayMetrics.density

        return GradientDrawable().apply {
            setColor(backgroundColor)
            this.cornerRadius = cornerRadius
            setStroke(
                (1 * context.resources.displayMetrics.density).toInt(),
                theme.colors.keyBorder,
            )
        }
    }

    private fun getActionIconRes(key: KeyboardKey.Action): Int =
        when (key.action) {
            KeyboardKey.ActionType.SHIFT -> R.drawable.shift_48px
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

    private fun getKeyLabel(
        key: KeyboardKey,
        state: KeyboardState,
    ): String =
        when (key) {
            is KeyboardKey.Character -> {
                when {
                    key.type == KeyboardKey.KeyType.LETTER && (state.isShiftPressed || state.isCapsLockOn) -> {
                        key.value.uppercase()
                    }

                    else -> {
                        key.value
                    }
                }
            }

            is KeyboardKey.Action -> {
                when (key.action) {
                    KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> context.getString(R.string.letters_mode_label)
                    KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> context.getString(R.string.numbers_mode_label)
                    KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> context.getString(R.string.symbols_mode_label)
                    else -> ""
                }
            }

            KeyboardKey.Spacer -> {
                ""
            }
        }
}
