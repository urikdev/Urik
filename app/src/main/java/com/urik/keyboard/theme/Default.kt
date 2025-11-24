package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Default : KeyboardTheme {
    override val id = "default"
    override val displayName = "Default"
    override val colors =
        ThemeColors(
            keyboardBackground = "#2a2d3e".toColorInt(),
            keyBackgroundCharacter = "#3a445d".toColorInt(),
            keyBackgroundAction = "#5e5768".toColorInt(),
            keyBackgroundSpace = "#3a445d".toColorInt(),
            keyTextCharacter = "#d4d2a5".toColorInt(),
            keyTextAction = "#fcdebe".toColorInt(),
            keyBorder = "#928779".toColorInt(),
            keyBorderFocused = "#d4d2a5".toColorInt(),
            keyBorderPressed = "#fcdebe".toColorInt(),
            statePressed = "#928779".toColorInt(),
            stateActivated = "#928779".toColorInt(),
            stateCapsLock = "#5e5768".toColorInt(),
            suggestionBarBackground = "#5e5768".toColorInt(),
            suggestionText = "#d4d2a5".toColorInt(),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = "#fcdebe".toColorInt(),
            swipePrimary = "#d4d2a5".toColorInt(),
            swipeSecondary = "#fcdebe".toColorInt(),
            swipeCurrent = "#928779".toColorInt(),
        )
}
