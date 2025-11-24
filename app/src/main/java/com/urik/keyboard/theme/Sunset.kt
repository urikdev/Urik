package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Sunset : KeyboardTheme {
    override val id = "sunset"
    override val displayName = "Sunset"
    override val colors =
        ThemeColors(
            keyboardBackground = "#2b1a3d".toColorInt(),
            keyBackgroundCharacter = "#4a2d5c".toColorInt(),
            keyBackgroundAction = "#5c3848".toColorInt(),
            keyBackgroundSpace = "#4a2d5c".toColorInt(),
            keyTextCharacter = "#ffb8a5".toColorInt(),
            keyTextAction = "#ffd4a3".toColorInt(),
            keyBorder = "#6b4f7a".toColorInt(),
            keyBorderFocused = "#ffd4a3".toColorInt(),
            keyBorderPressed = "#ffd4a3".toColorInt(),
            statePressed = "#7a5591".toColorInt(),
            stateActivated = "#7a5591".toColorInt(),
            stateCapsLock = "#5c3848".toColorInt(),
            suggestionBarBackground = "#5c3848".toColorInt(),
            suggestionText = "#ffb8a5".toColorInt(),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = "#ffd4a3".toColorInt(),
            swipePrimary = "#ffb8a5".toColorInt(),
            swipeSecondary = "#ffd4a3".toColorInt(),
            swipeCurrent = "#6b4f7a".toColorInt(),
        )
}
