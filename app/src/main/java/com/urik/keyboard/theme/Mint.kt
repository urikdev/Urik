package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Mint : KeyboardTheme {
    override val id = "mint"
    override val displayName = "Mint"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#1a2e26"),
            keyBackgroundCharacter = Color.parseColor("#2a4a3d"),
            keyBackgroundAction = Color.parseColor("#3d5c4a"),
            keyBackgroundSpace = Color.parseColor("#2a4a3d"),
            keyTextCharacter = Color.parseColor("#a3f5d9"),
            keyTextAction = Color.parseColor("#c4ffeb"),
            keyBorder = Color.parseColor("#4a6b58"),
            keyBorderFocused = Color.parseColor("#c4ffeb"),
            keyBorderPressed = Color.parseColor("#c4ffeb"),
            statePressed = Color.parseColor("#5a7a68"),
            stateActivated = Color.parseColor("#5a7a68"),
            stateCapsLock = Color.parseColor("#3d5c4a"),
            suggestionBarBackground = Color.parseColor("#3d5c4a"),
            suggestionText = Color.parseColor("#a3f5d9"),
            keyShadow = 0x1A000000,
            focusIndicator = Color.parseColor("#c4ffeb"),
            swipePrimary = Color.parseColor("#a3f5d9"),
            swipeSecondary = Color.parseColor("#c4ffeb"),
            swipeCurrent = Color.parseColor("#4a6b58"),
        )
}
