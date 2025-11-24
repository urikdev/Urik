package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Lavender : KeyboardTheme {
    override val id = "lavender"
    override val displayName = "Lavender"
    override val colors =
        ThemeColors(
            keyboardBackground = "#1f1a2e".toColorInt(),
            keyBackgroundCharacter = "#3d2f52".toColorInt(),
            keyBackgroundAction = "#4a3858".toColorInt(),
            keyBackgroundSpace = "#3d2f52".toColorInt(),
            keyTextCharacter = "#d4b5ff".toColorInt(),
            keyTextAction = "#e8d4ff".toColorInt(),
            keyBorder = "#5c4f6b".toColorInt(),
            keyBorderFocused = "#e8d4ff".toColorInt(),
            keyBorderPressed = "#e8d4ff".toColorInt(),
            statePressed = "#6b5a7a".toColorInt(),
            stateActivated = "#6b5a7a".toColorInt(),
            stateCapsLock = "#4a3858".toColorInt(),
            suggestionBarBackground = "#4a3858".toColorInt(),
            suggestionText = "#d4b5ff".toColorInt(),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = "#e8d4ff".toColorInt(),
            swipePrimary = "#d4b5ff".toColorInt(),
            swipeSecondary = "#e8d4ff".toColorInt(),
            swipeCurrent = "#5c4f6b".toColorInt(),
        )
}
