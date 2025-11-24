package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Light : KeyboardTheme {
    override val id = "light"
    override val displayName = "Light"
    override val colors =
        ThemeColors(
            keyboardBackground = "#f5f5f5".toColorInt(),
            keyBackgroundCharacter = "#ffffff".toColorInt(),
            keyBackgroundAction = "#e8e8e8".toColorInt(),
            keyBackgroundSpace = "#ffffff".toColorInt(),
            keyTextCharacter = "#2d2d2d".toColorInt(),
            keyTextAction = "#1a1a1a".toColorInt(),
            keyBorder = "#d0d0d0".toColorInt(),
            keyBorderFocused = "#5e5768".toColorInt(),
            keyBorderPressed = "#3a445d".toColorInt(),
            statePressed = "#e0e0e0".toColorInt(),
            stateActivated = "#d4d2a5".toColorInt(),
            stateCapsLock = "#e8e8e8".toColorInt(),
            suggestionBarBackground = "#e8e8e8".toColorInt(),
            suggestionText = "#2d2d2d".toColorInt(),
            keyShadow = 0x0D000000,
            focusIndicator = "#5e5768".toColorInt(),
            swipePrimary = "#5e5768".toColorInt(),
            swipeSecondary = "#928779".toColorInt(),
            swipeCurrent = "#3a445d".toColorInt(),
        )
}
