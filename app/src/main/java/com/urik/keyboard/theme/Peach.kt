package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Peach : KeyboardTheme {
    override val id = "peach"
    override val displayName = "Peach"
    override val colors =
        ThemeColors(
            keyboardBackground = "#2e1f1a".toColorInt(),
            keyBackgroundCharacter = "#4a3428".toColorInt(),
            keyBackgroundAction = "#5c4238".toColorInt(),
            keyBackgroundSpace = "#4a3428".toColorInt(),
            keyTextCharacter = "#ffc4a3".toColorInt(),
            keyTextAction = "#ffd9bf".toColorInt(),
            keyBorder = "#6b4f3d".toColorInt(),
            keyBorderFocused = "#ffd9bf".toColorInt(),
            keyBorderPressed = "#ffd9bf".toColorInt(),
            statePressed = "#7a5f4d".toColorInt(),
            stateActivated = "#7a5f4d".toColorInt(),
            stateCapsLock = "#5c4238".toColorInt(),
            suggestionBarBackground = "#5c4238".toColorInt(),
            suggestionText = "#ffc4a3".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#ffd9bf".toColorInt(),
            swipePrimary = "#ffc4a3".toColorInt(),
            swipeSecondary = "#ffd9bf".toColorInt(),
            swipeCurrent = "#6b4f3d".toColorInt(),
        )
}
