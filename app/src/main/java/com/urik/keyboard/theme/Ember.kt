package com.urik.keyboard.theme

import android.graphics.Color
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeColors

data object Ember : KeyboardTheme {
    override val id = "ember"
    override val displayName = "Ember"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#2b1410"),
            keyBackgroundCharacter = Color.parseColor("#4a2418"),
            keyBackgroundAction = Color.parseColor("#5c2f1a"),
            keyBackgroundSpace = Color.parseColor("#4a2418"),
            keyTextCharacter = Color.parseColor("#ffb380"),
            keyTextAction = Color.parseColor("#ffd4a8"),
            keyBorder = Color.parseColor("#6b3d28"),
            keyBorderFocused = Color.parseColor("#ffd4a8"),
            keyBorderPressed = Color.parseColor("#ffd4a8"),
            statePressed = Color.parseColor("#7a5238"),
            stateActivated = Color.parseColor("#7a5238"),
            stateCapsLock = Color.parseColor("#5c2f1a"),
            suggestionBarBackground = Color.parseColor("#5c2f1a"),
            suggestionText = Color.parseColor("#ffb380"),
            keyShadow = 0x1A000000,
            focusIndicator = Color.parseColor("#ffd4a8"),
            swipePrimary = Color.parseColor("#ffb380"),
            swipeSecondary = Color.parseColor("#ffd4a8"),
            swipeCurrent = Color.parseColor("#6b3d28"),
        )
}
