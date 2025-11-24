package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Forest : KeyboardTheme {
    override val id = "forest"
    override val displayName = "Forest"
    override val colors =
        ThemeColors(
            keyboardBackground = "#1a2419".toColorInt(),
            keyBackgroundCharacter = "#2d4a2b".toColorInt(),
            keyBackgroundAction = "#3d3428".toColorInt(),
            keyBackgroundSpace = "#2d4a2b".toColorInt(),
            keyTextCharacter = "#c4d9a8".toColorInt(),
            keyTextAction = "#f5d99b".toColorInt(),
            keyBorder = "#4a5c48".toColorInt(),
            keyBorderFocused = "#f5d99b".toColorInt(),
            keyBorderPressed = "#f5d99b".toColorInt(),
            statePressed = "#3d5a3a".toColorInt(),
            stateActivated = "#3d5a3a".toColorInt(),
            stateCapsLock = "#3d3428".toColorInt(),
            suggestionBarBackground = "#3d3428".toColorInt(),
            suggestionText = "#c4d9a8".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#f5d99b".toColorInt(),
            swipePrimary = "#c4d9a8".toColorInt(),
            swipeSecondary = "#f5d99b".toColorInt(),
            swipeCurrent = "#4a5c48".toColorInt(),
        )
}
