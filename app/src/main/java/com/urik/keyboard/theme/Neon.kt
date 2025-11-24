package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Neon : KeyboardTheme {
    override val id = "neon"
    override val displayName = "Neon"
    override val colors =
        ThemeColors(
            keyboardBackground = "#0a0a14".toColorInt(),
            keyBackgroundCharacter = "#1a1a2e".toColorInt(),
            keyBackgroundAction = "#1f1f3d".toColorInt(),
            keyBackgroundSpace = "#1a1a2e".toColorInt(),
            keyTextCharacter = "#00ffaa".toColorInt(),
            keyTextAction = "#ff00ff".toColorInt(),
            keyBorder = "#2a2a4a".toColorInt(),
            keyBorderFocused = "#00ffff".toColorInt(),
            keyBorderPressed = "#00ffff".toColorInt(),
            statePressed = "#3d3d5c".toColorInt(),
            stateActivated = "#3d3d5c".toColorInt(),
            stateCapsLock = "#1f1f3d".toColorInt(),
            suggestionBarBackground = "#1f1f3d".toColorInt(),
            suggestionText = "#00ffaa".toColorInt(),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = "#00ffff".toColorInt(),
            swipePrimary = "#00ffff".toColorInt(),
            swipeSecondary = "#ff00ff".toColorInt(),
            swipeCurrent = "#ffff00".toColorInt(),
        )
}
