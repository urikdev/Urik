package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Slate : KeyboardTheme {
    override val id = "slate"
    override val displayName = "Slate"
    override val colors =
        ThemeColors(
            keyboardBackground = "#1a1d24".toColorInt(),
            keyBackgroundCharacter = "#2d3440".toColorInt(),
            keyBackgroundAction = "#353d4a".toColorInt(),
            keyBackgroundSpace = "#2d3440".toColorInt(),
            keyTextCharacter = "#a8b8d4".toColorInt(),
            keyTextAction = "#c4d4f5".toColorInt(),
            keyBorder = "#4a5361".toColorInt(),
            keyBorderFocused = "#c4d4f5".toColorInt(),
            keyBorderPressed = "#c4d4f5".toColorInt(),
            statePressed = "#5a6370".toColorInt(),
            stateActivated = "#5a6370".toColorInt(),
            stateCapsLock = "#353d4a".toColorInt(),
            suggestionBarBackground = "#353d4a".toColorInt(),
            suggestionText = "#a8b8d4".toColorInt(),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = "#c4d4f5".toColorInt(),
            swipePrimary = "#a8b8d4".toColorInt(),
            swipeSecondary = "#c4d4f5".toColorInt(),
            swipeCurrent = "#4a5361".toColorInt(),
        )
}
