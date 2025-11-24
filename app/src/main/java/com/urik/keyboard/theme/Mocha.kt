package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Mocha : KeyboardTheme {
    override val id = "mocha"
    override val displayName = "Mocha"
    override val colors =
        ThemeColors(
            keyboardBackground = "#2b2218".toColorInt(),
            keyBackgroundCharacter = "#42382a".toColorInt(),
            keyBackgroundAction = "#524230".toColorInt(),
            keyBackgroundSpace = "#42382a".toColorInt(),
            keyTextCharacter = "#f5e6d3".toColorInt(),
            keyTextAction = "#fff5e6".toColorInt(),
            keyBorder = "#5a4a38".toColorInt(),
            keyBorderFocused = "#fff5e6".toColorInt(),
            keyBorderPressed = "#fff5e6".toColorInt(),
            statePressed = "#6b5a48".toColorInt(),
            stateActivated = "#6b5a48".toColorInt(),
            stateCapsLock = "#524230".toColorInt(),
            suggestionBarBackground = "#524230".toColorInt(),
            suggestionText = "#f5e6d3".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#fff5e6".toColorInt(),
            swipePrimary = "#f5e6d3".toColorInt(),
            swipeSecondary = "#fff5e6".toColorInt(),
            swipeCurrent = "#ff9966".toColorInt(),
        )
}
