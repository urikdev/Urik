package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Crimson : KeyboardTheme {
    override val id = "crimson"
    override val displayName = "Crimson"
    override val colors =
        ThemeColors(
            keyboardBackground = "#1a0a0f".toColorInt(),
            keyBackgroundCharacter = "#3d1a24".toColorInt(),
            keyBackgroundAction = "#5c2433".toColorInt(),
            keyBackgroundSpace = "#3d1a24".toColorInt(),
            keyTextCharacter = "#ffb3c1".toColorInt(),
            keyTextAction = "#ffd4dd".toColorInt(),
            keyBorder = "#4a2030".toColorInt(),
            keyBorderFocused = "#ffd4dd".toColorInt(),
            keyBorderPressed = "#ffd4dd".toColorInt(),
            statePressed = "#6b2f42".toColorInt(),
            stateActivated = "#6b2f42".toColorInt(),
            stateCapsLock = "#5c2433".toColorInt(),
            suggestionBarBackground = "#5c2433".toColorInt(),
            suggestionText = "#ffb3c1".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#ffd4dd".toColorInt(),
            swipePrimary = "#ffb3c1".toColorInt(),
            swipeSecondary = "#ffd4dd".toColorInt(),
            swipeCurrent = "#ff8899".toColorInt(),
        )
}
