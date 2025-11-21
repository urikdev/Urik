package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Light : KeyboardTheme {
    override val id = "light"
    override val displayName = "Light"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#f5f5f5"),
            keyBackgroundCharacter = Color.parseColor("#ffffff"),
            keyBackgroundAction = Color.parseColor("#e8e8e8"),
            keyBackgroundSpace = Color.parseColor("#ffffff"),
            keyTextCharacter = Color.parseColor("#2d2d2d"),
            keyTextAction = Color.parseColor("#1a1a1a"),
            keyBorder = Color.parseColor("#d0d0d0"),
            keyBorderFocused = Color.parseColor("#5e5768"),
            keyBorderPressed = Color.parseColor("#3a445d"),
            statePressed = Color.parseColor("#e0e0e0"),
            stateActivated = Color.parseColor("#d4d2a5"),
            stateCapsLock = Color.parseColor("#e8e8e8"),
            suggestionBarBackground = Color.parseColor("#e8e8e8"),
            suggestionText = Color.parseColor("#2d2d2d"),
            keyShadow = 0x0D000000,
            focusIndicator = Color.parseColor("#5e5768"),
            swipePrimary = Color.parseColor("#5e5768"),
            swipeSecondary = Color.parseColor("#928779"),
            swipeCurrent = Color.parseColor("#3a445d"),
        )
}
