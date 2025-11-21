package com.urik.keyboard.theme

import android.graphics.Color

data object Default : KeyboardTheme {
    override val id = "default"
    override val displayName = "Default"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#2a2d3e"),
            keyBackgroundCharacter = Color.parseColor("#3a445d"),
            keyBackgroundAction = Color.parseColor("#5e5768"),
            keyBackgroundSpace = Color.parseColor("#3a445d"),
            keyTextCharacter = Color.parseColor("#d4d2a5"),
            keyTextAction = Color.parseColor("#fcdebe"),
            keyBorder = Color.parseColor("#928779"),
            keyBorderFocused = Color.parseColor("#d4d2a5"),
            keyBorderPressed = Color.parseColor("#fcdebe"),
            statePressed = Color.parseColor("#928779"),
            stateActivated = Color.parseColor("#928779"),
            stateCapsLock = Color.parseColor("#5e5768"),
            suggestionBarBackground = Color.parseColor("#5e5768"),
            suggestionText = Color.parseColor("#d4d2a5"),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = Color.parseColor("#fcdebe"),
            swipePrimary = Color.parseColor("#d4d2a5"),
            swipeSecondary = Color.parseColor("#fcdebe"),
            swipeCurrent = Color.parseColor("#928779"),
        )
}
