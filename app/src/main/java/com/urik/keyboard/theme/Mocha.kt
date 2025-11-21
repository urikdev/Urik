package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Mocha : KeyboardTheme {
    override val id = "mocha"
    override val displayName = "Mocha"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#2b2218"),
            keyBackgroundCharacter = Color.parseColor("#42382a"),
            keyBackgroundAction = Color.parseColor("#524230"),
            keyBackgroundSpace = Color.parseColor("#42382a"),
            keyTextCharacter = Color.parseColor("#f5e6d3"),
            keyTextAction = Color.parseColor("#fff5e6"),
            keyBorder = Color.parseColor("#5a4a38"),
            keyBorderFocused = Color.parseColor("#fff5e6"),
            keyBorderPressed = Color.parseColor("#fff5e6"),
            statePressed = Color.parseColor("#6b5a48"),
            stateActivated = Color.parseColor("#6b5a48"),
            stateCapsLock = Color.parseColor("#524230"),
            suggestionBarBackground = Color.parseColor("#524230"),
            suggestionText = Color.parseColor("#f5e6d3"),
            keyShadow = 0x1A000000,
            focusIndicator = Color.parseColor("#fff5e6"),
            swipePrimary = Color.parseColor("#f5e6d3"),
            swipeSecondary = Color.parseColor("#fff5e6"),
            swipeCurrent = Color.parseColor("#ff9966"),
        )
}
