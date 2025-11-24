package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Steel : KeyboardTheme {
    override val id = "steel"
    override val displayName = "Steel"
    override val colors =
        ThemeColors(
            keyboardBackground = "#141618".toColorInt(),
            keyBackgroundCharacter = "#242629".toColorInt(),
            keyBackgroundAction = "#2d3033".toColorInt(),
            keyBackgroundSpace = "#242629".toColorInt(),
            keyTextCharacter = "#c4c9d1".toColorInt(),
            keyTextAction = "#e8ecf0".toColorInt(),
            keyBorder = "#3d4145".toColorInt(),
            keyBorderFocused = "#e8ecf0".toColorInt(),
            keyBorderPressed = "#e8ecf0".toColorInt(),
            statePressed = "#4a4f54".toColorInt(),
            stateActivated = "#4a4f54".toColorInt(),
            stateCapsLock = "#2d3033".toColorInt(),
            suggestionBarBackground = "#2d3033".toColorInt(),
            suggestionText = "#c4c9d1".toColorInt(),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = "#e8ecf0".toColorInt(),
            swipePrimary = "#c4c9d1".toColorInt(),
            swipeSecondary = "#e8ecf0".toColorInt(),
            swipeCurrent = "#3d4145".toColorInt(),
        )
}
