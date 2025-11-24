package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Ocean : KeyboardTheme {
    override val id = "ocean"
    override val displayName = "Ocean"
    override val colors =
        ThemeColors(
            keyboardBackground = "#0d1f28".toColorInt(),
            keyBackgroundCharacter = "#1a3d4f".toColorInt(),
            keyBackgroundAction = "#1f4d5c".toColorInt(),
            keyBackgroundSpace = "#1a3d4f".toColorInt(),
            keyTextCharacter = "#87d6db".toColorInt(),
            keyTextAction = "#b4f0f5".toColorInt(),
            keyBorder = "#2d5a6b".toColorInt(),
            keyBorderFocused = "#b4f0f5".toColorInt(),
            keyBorderPressed = "#b4f0f5".toColorInt(),
            statePressed = "#296b7a".toColorInt(),
            stateActivated = "#296b7a".toColorInt(),
            stateCapsLock = "#1f4d5c".toColorInt(),
            suggestionBarBackground = "#1f4d5c".toColorInt(),
            suggestionText = "#87d6db".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#b4f0f5".toColorInt(),
            swipePrimary = "#87d6db".toColorInt(),
            swipeSecondary = "#b4f0f5".toColorInt(),
            swipeCurrent = "#2d5a6b".toColorInt(),
        )
}
