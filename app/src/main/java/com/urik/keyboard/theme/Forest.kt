package com.urik.keyboard.theme

import android.graphics.Color

data object Forest : KeyboardTheme {
    override val id = "forest"
    override val displayName = "Forest"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#1a2419"),
            keyBackgroundCharacter = Color.parseColor("#2d4a2b"),
            keyBackgroundAction = Color.parseColor("#3d3428"),
            keyBackgroundSpace = Color.parseColor("#2d4a2b"),
            keyTextCharacter = Color.parseColor("#c4d9a8"),
            keyTextAction = Color.parseColor("#f5d99b"),
            keyBorder = Color.parseColor("#4a5c48"),
            keyBorderFocused = Color.parseColor("#f5d99b"),
            keyBorderPressed = Color.parseColor("#f5d99b"),
            statePressed = Color.parseColor("#3d5a3a"),
            stateActivated = Color.parseColor("#3d5a3a"),
            stateCapsLock = Color.parseColor("#3d3428"),
            suggestionBarBackground = Color.parseColor("#3d3428"),
            suggestionText = Color.parseColor("#c4d9a8"),
            keyShadow = 0x1A000000,
            focusIndicator = Color.parseColor("#f5d99b"),
            swipePrimary = Color.parseColor("#c4d9a8"),
            swipeSecondary = Color.parseColor("#f5d99b"),
            swipeCurrent = Color.parseColor("#4a5c48"),
        )
}
