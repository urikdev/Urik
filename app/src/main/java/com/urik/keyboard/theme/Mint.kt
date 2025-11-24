package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Mint : KeyboardTheme {
    override val id = "mint"
    override val displayName = "Mint"
    override val colors =
        ThemeColors(
            keyboardBackground = "#1a2e26".toColorInt(),
            keyBackgroundCharacter = "#2a4a3d".toColorInt(),
            keyBackgroundAction = "#3d5c4a".toColorInt(),
            keyBackgroundSpace = "#2a4a3d".toColorInt(),
            keyTextCharacter = "#a3f5d9".toColorInt(),
            keyTextAction = "#c4ffeb".toColorInt(),
            keyBorder = "#4a6b58".toColorInt(),
            keyBorderFocused = "#c4ffeb".toColorInt(),
            keyBorderPressed = "#c4ffeb".toColorInt(),
            statePressed = "#5a7a68".toColorInt(),
            stateActivated = "#5a7a68".toColorInt(),
            stateCapsLock = "#3d5c4a".toColorInt(),
            suggestionBarBackground = "#3d5c4a".toColorInt(),
            suggestionText = "#a3f5d9".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#c4ffeb".toColorInt(),
            swipePrimary = "#a3f5d9".toColorInt(),
            swipeSecondary = "#c4ffeb".toColorInt(),
            swipeCurrent = "#4a6b58".toColorInt(),
        )
}
