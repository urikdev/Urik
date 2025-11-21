package com.urik.keyboard.theme

import android.graphics.Color

data object Steel : KeyboardTheme {
    override val id = "steel"
    override val displayName = "Steel"
    override val colors =
        ThemeColors(
            keyboardBackground = Color.parseColor("#141618"),
            keyBackgroundCharacter = Color.parseColor("#242629"),
            keyBackgroundAction = Color.parseColor("#2d3033"),
            keyBackgroundSpace = Color.parseColor("#242629"),
            keyTextCharacter = Color.parseColor("#c4c9d1"),
            keyTextAction = Color.parseColor("#e8ecf0"),
            keyBorder = Color.parseColor("#3d4145"),
            keyBorderFocused = Color.parseColor("#e8ecf0"),
            keyBorderPressed = Color.parseColor("#e8ecf0"),
            statePressed = Color.parseColor("#4a4f54"),
            stateActivated = Color.parseColor("#4a4f54"),
            stateCapsLock = Color.parseColor("#2d3033"),
            suggestionBarBackground = Color.parseColor("#2d3033"),
            suggestionText = Color.parseColor("#c4c9d1"),
            keyShadow = 0x1AFFFFFF,
            focusIndicator = Color.parseColor("#e8ecf0"),
            swipePrimary = Color.parseColor("#c4c9d1"),
            swipeSecondary = Color.parseColor("#e8ecf0"),
            swipeCurrent = Color.parseColor("#3d4145"),
        )
}
