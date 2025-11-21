package com.urik.keyboard.theme

import android.graphics.Color

data object Lavender : KeyboardTheme {
    override val id = "lavender"
    override val displayName = "Lavender"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#1f1a2e"),
            keyBackgroundCharacter = Color.parseColor("#3d2f52"),
            keyBackgroundAction = Color.parseColor("#4a3858"),
            keyBackgroundSpace = Color.parseColor("#3d2f52"),
            keyTextCharacter = Color.parseColor("#d4b5ff"),
            keyTextAction = Color.parseColor("#e8d4ff"),
            keyBorder = Color.parseColor("#5c4f6b"),
            keyBorderFocused = Color.parseColor("#e8d4ff"),
            keyBorderPressed = Color.parseColor("#e8d4ff"),
            statePressed = Color.parseColor("#6b5a7a"),
            stateActivated = Color.parseColor("#6b5a7a"),
            stateCapsLock = Color.parseColor("#4a3858"),
            suggestionBarBackground = Color.parseColor("#4a3858"),
            suggestionText = Color.parseColor("#d4b5ff"),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = Color.parseColor("#e8d4ff"),
            swipePrimary = Color.parseColor("#d4b5ff"),
            swipeSecondary = Color.parseColor("#e8d4ff"),
            swipeCurrent = Color.parseColor("#5c4f6b"),
        )
}
