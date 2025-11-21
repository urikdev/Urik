package com.urik.keyboard.theme

import android.graphics.Color

data object Ocean : KeyboardTheme {
    override val id = "ocean"
    override val displayName = "Ocean"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#0d1f28"),
            keyBackgroundCharacter = Color.parseColor("#1a3d4f"),
            keyBackgroundAction = Color.parseColor("#1f4d5c"),
            keyBackgroundSpace = Color.parseColor("#1a3d4f"),
            keyTextCharacter = Color.parseColor("#87d6db"),
            keyTextAction = Color.parseColor("#b4f0f5"),
            keyBorder = Color.parseColor("#2d5a6b"),
            keyBorderFocused = Color.parseColor("#b4f0f5"),
            keyBorderPressed = Color.parseColor("#b4f0f5"),
            statePressed = Color.parseColor("#296b7a"),
            stateActivated = Color.parseColor("#296b7a"),
            stateCapsLock = Color.parseColor("#1f4d5c"),
            suggestionBarBackground = Color.parseColor("#1f4d5c"),
            suggestionText = Color.parseColor("#87d6db"),
            keyShadow = 0x1A000000,
            focusIndicator = Color.parseColor("#b4f0f5"),
            swipePrimary = Color.parseColor("#87d6db"),
            swipeSecondary = Color.parseColor("#b4f0f5"),
            swipeCurrent = Color.parseColor("#2d5a6b"),
        )
}
