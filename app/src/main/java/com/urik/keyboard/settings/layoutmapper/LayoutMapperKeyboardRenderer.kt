package com.urik.keyboard.settings.layoutmapper

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.theme.KeyboardTheme

/**
 * Renders interactive keyboard for layout mapping configuration.
 *
 * Uses identical dimension calculations as KeyboardLayoutManager for 1:1 visual match.
 * Only letter keys are interactive; action keys are displayed but disabled.
 */
class LayoutMapperKeyboardRenderer(
    private val context: Context,
) {
    private val keyButtons = mutableMapOf<String, Button>()

    private val keySize = KeySize.MEDIUM
    private val density = context.resources.displayMetrics.density

    private val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
    private val baseMinTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
    private val baseKeyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
    private val baseHorizontalMargin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
    private val keyboardPadding = context.resources.getDimensionPixelSize(R.dimen.keyboard_padding)
    private val keyboardPaddingVertical = context.resources.getDimensionPixelSize(R.dimen.keyboard_padding_vertical)
    private val keyMarginVertical = context.resources.getDimensionPixelSize(R.dimen.key_margin_vertical)

    private val keySizeMultiplier = keySize.scaleFactor
    private val keyHeight = (baseKeyHeight * keySizeMultiplier).toInt()
    private val visualHeight = keyHeight + 2
    private val minTarget = (baseMinTouchTarget * keySizeMultiplier).toInt()
    private val verticalMargin = (minTarget - visualHeight) / 2
    private val horizontalMargin = (baseHorizontalMargin * keySizeMultiplier).toInt()
    private val horizontalPadding = (basePadding * keySizeMultiplier).toInt()
    private val verticalPadding = (basePadding * 0.5f * keySizeMultiplier).toInt()
    private val cornerRadius = 8f * density

    fun createKeyboardView(
        layout: KeyboardLayout,
        theme: KeyboardTheme,
        mappings: Map<String, String>,
        onKeyClick: (String) -> Unit,
    ): View {
        keyButtons.clear()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setPadding(keyboardPadding, keyboardPaddingVertical, keyboardPadding, keyboardPaddingVertical)
                setBackgroundColor(theme.colors.keyboardBackground)
            }

        val textSize = calculateTextSize()

        layout.rows
            .filter { row -> !isBottomRow(row) }
            .forEach { row ->
                val rowView = createRowView(row, theme, mappings, textSize, onKeyClick)
                container.addView(rowView)
            }

        return container
    }

    fun updateMappings(
        mappings: Map<String, String>,
        theme: KeyboardTheme,
    ) {
        keyButtons.forEach { (keyValue, button) ->
            val customSymbol = mappings[keyValue]
            updateButtonAppearance(button, keyValue, customSymbol, theme)
        }
    }

    private fun calculateTextSize(): Float {
        val baseTextSize = keyHeight * 0.38f / density
        val minSize = 12f
        val maxSize = 24f
        return baseTextSize.coerceIn(minSize, maxSize)
    }

    private fun createRowView(
        keys: List<KeyboardKey>,
        theme: KeyboardTheme,
        mappings: Map<String, String>,
        textSize: Float,
        onKeyClick: (String) -> Unit,
    ): LinearLayout {
        val is9LetterRow = is9CharacterLetterRow(keys)

        val rowLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 0, 0, keyMarginVertical)
                        }
                isBaselineAligned = false
            }

        if (is9LetterRow) {
            rowLayout.addView(createSpacer(0.5f))
        }

        keys.forEach { key ->
            when (key) {
                is KeyboardKey.Spacer -> {
                    rowLayout.addView(createSpacer(STANDARD_KEY_WEIGHT))
                }
                is KeyboardKey.Character -> {
                    val button = createCharacterButton(key, keys, theme, mappings, textSize, onKeyClick)
                    rowLayout.addView(button)
                }
                is KeyboardKey.Action -> {
                    val button = createActionButton(key, keys, theme, textSize)
                    rowLayout.addView(button)
                }
            }
        }

        if (is9LetterRow) {
            rowLayout.addView(createSpacer(0.5f))
        }

        return rowLayout
    }

    private fun createSpacer(weight: Float): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, weight)
        }

    private fun createCharacterButton(
        key: KeyboardKey.Character,
        rowKeys: List<KeyboardKey>,
        theme: KeyboardTheme,
        mappings: Map<String, String>,
        textSize: Float,
        onKeyClick: (String) -> Unit,
    ): Button {
        val button =
            Button(context).apply {
                layoutParams =
                    LinearLayout
                        .LayoutParams(0, visualHeight, getKeyWeight(key, rowKeys))
                        .apply { setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin) }

                setTextAppearance(R.style.KeyTextAppearance)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                maxLines = 1
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT
                minHeight = 0
                minimumHeight = 0
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            }

        val keyValue = key.value.lowercase()
        val customSymbol = mappings[keyValue]
        updateButtonAppearance(button, keyValue, customSymbol, theme)

        if (key.type == KeyboardKey.KeyType.LETTER || key.type == KeyboardKey.KeyType.NUMBER) {
            button.setOnClickListener { onKeyClick(key.value) }
            keyButtons[keyValue] = button
        } else {
            button.isClickable = false
            button.isFocusable = false
        }

        return button
    }

    private fun createActionButton(
        key: KeyboardKey.Action,
        rowKeys: List<KeyboardKey>,
        theme: KeyboardTheme,
        textSize: Float,
    ): Button =
        Button(context).apply {
            layoutParams =
                LinearLayout
                    .LayoutParams(0, visualHeight, getKeyWeight(key, rowKeys))
                    .apply { setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin) }

            text = getActionLabel(key)
            setTextAppearance(R.style.KeyTextAppearance_Action)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize * 0.8f)
            maxLines = 1
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            minHeight = 0
            minimumHeight = 0
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            background = createKeyBackground(theme.colors.keyBackgroundAction, theme)
            setTextColor(theme.colors.keyTextAction)
            alpha = 0.5f

            isClickable = false
            isFocusable = false
        }

    private fun updateButtonAppearance(
        button: Button,
        keyValue: String,
        customSymbol: String?,
        theme: KeyboardTheme,
    ) {
        if (customSymbol != null) {
            button.text = "${keyValue.uppercase()}\n$customSymbol"
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            button.background = createKeyBackground(theme.colors.keyBackgroundAction, theme)
            button.setTextColor(theme.colors.keyTextAction)
        } else {
            button.text = keyValue.uppercase()
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, calculateTextSize())
            button.background = createKeyBackground(theme.colors.keyBackgroundCharacter, theme)
            button.setTextColor(theme.colors.keyTextCharacter)
        }
    }

    private fun createKeyBackground(
        backgroundColor: Int,
        theme: KeyboardTheme,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(backgroundColor)
            this.cornerRadius = this@LayoutMapperKeyboardRenderer.cornerRadius
            setStroke((1 * density).toInt(), theme.colors.keyBorder)
        }

    private fun getKeyWeight(
        key: KeyboardKey,
        rowKeys: List<KeyboardKey>,
    ): Float {
        val characterKeyCount = rowKeys.count { it is KeyboardKey.Character }

        return when (key) {
            is KeyboardKey.Character -> STANDARD_KEY_WEIGHT
            is KeyboardKey.Action ->
                when (key.action) {
                    KeyboardKey.ActionType.SPACE -> 4.0f
                    KeyboardKey.ActionType.SHIFT ->
                        if (characterKeyCount >= 10) STANDARD_KEY_WEIGHT else 1.5f
                    KeyboardKey.ActionType.BACKSPACE ->
                        if (characterKeyCount >= 10) STANDARD_KEY_WEIGHT else 1.5f
                    else -> STANDARD_KEY_WEIGHT
                }
            KeyboardKey.Spacer -> STANDARD_KEY_WEIGHT
        }
    }

    private fun getActionLabel(key: KeyboardKey.Action): String =
        when (key.action) {
            KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> context.getString(R.string.letters_mode_label)
            KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> context.getString(R.string.numbers_mode_label)
            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> context.getString(R.string.symbols_mode_label)
            KeyboardKey.ActionType.SHIFT -> "⇧"
            KeyboardKey.ActionType.BACKSPACE -> "⌫"
            KeyboardKey.ActionType.SPACE -> "␣"
            KeyboardKey.ActionType.ENTER -> "↵"
            else -> ""
        }

    private fun is9CharacterLetterRow(rowKeys: List<KeyboardKey>): Boolean {
        val nonSpacerKeys = rowKeys.filter { it !is KeyboardKey.Spacer }
        if (nonSpacerKeys.size != 9) return false
        return nonSpacerKeys.all { it is KeyboardKey.Character && it.type == KeyboardKey.KeyType.LETTER }
    }

    private fun isBottomRow(rowKeys: List<KeyboardKey>): Boolean =
        rowKeys.any { it is KeyboardKey.Action && it.action == KeyboardKey.ActionType.SPACE }

    companion object {
        private const val STANDARD_KEY_WEIGHT = 1f
    }
}
