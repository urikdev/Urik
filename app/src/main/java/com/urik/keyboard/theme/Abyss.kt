package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Abyss : KeyboardTheme {
    override val id = "abyss"
    override val displayName = "Abyss"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#000000"),
            keyBackgroundCharacter = Color.parseColor("#0a0a0a"),
            keyBackgroundAction = Color.parseColor("#141414"),
            keyBackgroundSpace = Color.parseColor("#0a0a0a"),
            keyTextCharacter = Color.parseColor("#e0e0e0"),
            keyTextAction = Color.parseColor("#00d9ff"),
            keyBorder = Color.parseColor("#1a1a1a"),
            keyBorderFocused = Color.parseColor("#00d9ff"),
            keyBorderPressed = Color.parseColor("#00d9ff"),
            statePressed = Color.parseColor("#1f1f1f"),
            stateActivated = Color.parseColor("#1f1f1f"),
            stateCapsLock = Color.parseColor("#141414"),
            suggestionBarBackground = Color.parseColor("#141414"),
            suggestionText = Color.parseColor("#e0e0e0"),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = Color.parseColor("#00d9ff"),
            swipePrimary = Color.parseColor("#00d9ff"),
            swipeSecondary = Color.parseColor("#0099ff"),
            swipeCurrent = Color.parseColor("#00ffff"),
        )
}
