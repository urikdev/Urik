package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Slate : KeyboardTheme {
    override val id = "slate"
    override val displayName = "Slate"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#1a1d24"),
            keyBackgroundCharacter = Color.parseColor("#2d3440"),
            keyBackgroundAction = Color.parseColor("#353d4a"),
            keyBackgroundSpace = Color.parseColor("#2d3440"),
            keyTextCharacter = Color.parseColor("#a8b8d4"),
            keyTextAction = Color.parseColor("#c4d4f5"),
            keyBorder = Color.parseColor("#4a5361"),
            keyBorderFocused = Color.parseColor("#c4d4f5"),
            keyBorderPressed = Color.parseColor("#c4d4f5"),
            statePressed = Color.parseColor("#5a6370"),
            stateActivated = Color.parseColor("#5a6370"),
            stateCapsLock = Color.parseColor("#353d4a"),
            suggestionBarBackground = Color.parseColor("#353d4a"),
            suggestionText = Color.parseColor("#a8b8d4"),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = Color.parseColor("#c4d4f5"),
            swipePrimary = Color.parseColor("#a8b8d4"),
            swipeSecondary = Color.parseColor("#c4d4f5"),
            swipeCurrent = Color.parseColor("#4a5361"),
        )
}
