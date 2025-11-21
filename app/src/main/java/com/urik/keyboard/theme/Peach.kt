package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Peach : KeyboardTheme {
    override val id = "peach"
    override val displayName = "Peach"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#2e1f1a"),
            keyBackgroundCharacter = Color.parseColor("#4a3428"),
            keyBackgroundAction = Color.parseColor("#5c4238"),
            keyBackgroundSpace = Color.parseColor("#4a3428"),
            keyTextCharacter = Color.parseColor("#ffc4a3"),
            keyTextAction = Color.parseColor("#ffd9bf"),
            keyBorder = Color.parseColor("#6b4f3d"),
            keyBorderFocused = Color.parseColor("#ffd9bf"),
            keyBorderPressed = Color.parseColor("#ffd9bf"),
            statePressed = Color.parseColor("#7a5f4d"),
            stateActivated = Color.parseColor("#7a5f4d"),
            stateCapsLock = Color.parseColor("#5c4238"),
            suggestionBarBackground = Color.parseColor("#5c4238"),
            suggestionText = Color.parseColor("#ffc4a3"),
            keyShadow = 0x1A000000,
            focusIndicator = Color.parseColor("#ffd9bf"),
            swipePrimary = Color.parseColor("#ffc4a3"),
            swipeSecondary = Color.parseColor("#ffd9bf"),
            swipeCurrent = Color.parseColor("#6b4f3d"),
        )
}
