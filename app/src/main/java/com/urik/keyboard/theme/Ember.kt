package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Ember : KeyboardTheme {
    override val id = "ember"
    override val displayName = "Ember"
    override val colors =
        ThemeColors(
            keyboardBackground = "#2b1410".toColorInt(),
            keyBackgroundCharacter = "#4a2418".toColorInt(),
            keyBackgroundAction = "#5c2f1a".toColorInt(),
            keyBackgroundSpace = "#4a2418".toColorInt(),
            keyTextCharacter = "#ffb380".toColorInt(),
            keyTextAction = "#ffd4a8".toColorInt(),
            keyBorder = "#6b3d28".toColorInt(),
            keyBorderFocused = "#ffd4a8".toColorInt(),
            keyBorderPressed = "#ffd4a8".toColorInt(),
            statePressed = "#7a5238".toColorInt(),
            stateActivated = "#7a5238".toColorInt(),
            stateCapsLock = "#5c2f1a".toColorInt(),
            suggestionBarBackground = "#5c2f1a".toColorInt(),
            suggestionText = "#ffb380".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#ffd4a8".toColorInt(),
            swipePrimary = "#ffb380".toColorInt(),
            swipeSecondary = "#ffd4a8".toColorInt(),
            swipeCurrent = "#6b3d28".toColorInt(),
        )
}
