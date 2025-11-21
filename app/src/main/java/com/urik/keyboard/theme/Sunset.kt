package com.urik.keyboard.theme

import android.graphics.Color

data object Sunset : KeyboardTheme {
    override val id = "sunset"
    override val displayName = "Sunset"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#2b1a3d"),
            keyBackgroundCharacter = Color.parseColor("#4a2d5c"),
            keyBackgroundAction = Color.parseColor("#5c3848"),
            keyBackgroundSpace = Color.parseColor("#4a2d5c"),
            keyTextCharacter = Color.parseColor("#ffb8a5"),
            keyTextAction = Color.parseColor("#ffd4a3"),
            keyBorder = Color.parseColor("#6b4f7a"),
            keyBorderFocused = Color.parseColor("#ffd4a3"),
            keyBorderPressed = Color.parseColor("#ffd4a3"),
            statePressed = Color.parseColor("#7a5591"),
            stateActivated = Color.parseColor("#7a5591"),
            stateCapsLock = Color.parseColor("#5c3848"),
            suggestionBarBackground = Color.parseColor("#5c3848"),
            suggestionText = Color.parseColor("#ffb8a5"),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = Color.parseColor("#ffd4a3"),
            swipePrimary = Color.parseColor("#ffb8a5"),
            swipeSecondary = Color.parseColor("#ffd4a3"),
            swipeCurrent = Color.parseColor("#6b4f7a"),
        )
}
