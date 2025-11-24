package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Abyss : KeyboardTheme {
    override val id = "abyss"
    override val displayName = "Abyss"
    override val colors =
        ThemeColors(
            keyboardBackground = "#000000".toColorInt(),
            keyBackgroundCharacter = "#0a0a0a".toColorInt(),
            keyBackgroundAction = "#141414".toColorInt(),
            keyBackgroundSpace = "#0a0a0a".toColorInt(),
            keyTextCharacter = "#e0e0e0".toColorInt(),
            keyTextAction = "#00d9ff".toColorInt(),
            keyBorder = "#1a1a1a".toColorInt(),
            keyBorderFocused = "#00d9ff".toColorInt(),
            keyBorderPressed = "#00d9ff".toColorInt(),
            statePressed = "#1f1f1f".toColorInt(),
            stateActivated = "#1f1f1f".toColorInt(),
            stateCapsLock = "#141414".toColorInt(),
            suggestionBarBackground = "#141414".toColorInt(),
            suggestionText = "#e0e0e0".toColorInt(),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = "#00d9ff".toColorInt(),
            swipePrimary = "#00d9ff".toColorInt(),
            swipeSecondary = "#0099ff".toColorInt(),
            swipeCurrent = "#00ffff".toColorInt(),
        )
}
