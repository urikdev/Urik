package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Crimson : KeyboardTheme {
    override val id = "crimson"
    override val displayName = "Crimson"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#1a0a0f"),
            keyBackgroundCharacter = Color.parseColor("#3d1a24"),
            keyBackgroundAction = Color.parseColor("#5c2433"),
            keyBackgroundSpace = Color.parseColor("#3d1a24"),
            keyTextCharacter = Color.parseColor("#ffb3c1"),
            keyTextAction = Color.parseColor("#ffd4dd"),
            keyBorder = Color.parseColor("#4a2030"),
            keyBorderFocused = Color.parseColor("#ffd4dd"),
            keyBorderPressed = Color.parseColor("#ffd4dd"),
            statePressed = Color.parseColor("#6b2f42"),
            stateActivated = Color.parseColor("#6b2f42"),
            stateCapsLock = Color.parseColor("#5c2433"),
            suggestionBarBackground = Color.parseColor("#5c2433"),
            suggestionText = Color.parseColor("#ffb3c1"),
            keyShadow = 0x1A000000,
            focusIndicator = Color.parseColor("#ffd4dd"),
            swipePrimary = Color.parseColor("#ffb3c1"),
            swipeSecondary = Color.parseColor("#ffd4dd"),
            swipeCurrent = Color.parseColor("#ff8899"),
        )
}
