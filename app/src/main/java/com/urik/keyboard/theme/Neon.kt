package com.urik.keyboard.theme

import android.graphics.Color

data object Neon : KeyboardTheme {
    override val id = "neon"
    override val displayName = "Neon"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#0a0a14"),
            keyBackgroundCharacter = Color.parseColor("#1a1a2e"),
            keyBackgroundAction = Color.parseColor("#1f1f3d"),
            keyBackgroundSpace = Color.parseColor("#1a1a2e"),
            keyTextCharacter = Color.parseColor("#00ffaa"),
            keyTextAction = Color.parseColor("#ff00ff"),
            keyBorder = Color.parseColor("#2a2a4a"),
            keyBorderFocused = Color.parseColor("#00ffff"),
            keyBorderPressed = Color.parseColor("#00ffff"),
            statePressed = Color.parseColor("#3d3d5c"),
            stateActivated = Color.parseColor("#3d3d5c"),
            stateCapsLock = Color.parseColor("#1f1f3d"),
            suggestionBarBackground = Color.parseColor("#1f1f3d"),
            suggestionText = Color.parseColor("#00ffaa"),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = Color.parseColor("#00ffff"),
            swipePrimary = Color.parseColor("#00ffff"),
            swipeSecondary = Color.parseColor("#ff00ff"),
            swipeCurrent = Color.parseColor("#ffff00"),
        )
}
