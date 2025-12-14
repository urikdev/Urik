package com.urik.keyboard.theme

data class MaterialYou(
    override val colors: ThemeColors,
) : KeyboardTheme {
    override val id = "material_you"
    override val displayName = "Material You"

    companion object {
        val Default =
            MaterialYou(
                ThemeColors(
                    keyboardBackground = 0,
                    keyBackgroundCharacter = 0,
                    keyBackgroundAction = 0,
                    keyBackgroundSpace = 0,
                    keyTextCharacter = 0,
                    keyTextAction = 0,
                    keyBorder = 0,
                    keyBorderFocused = 0,
                    keyBorderPressed = 0,
                    statePressed = 0,
                    stateActivated = 0,
                    stateCapsLock = 0,
                    suggestionBarBackground = 0,
                    suggestionText = 0,
                    keyShadow = 0,
                    focusIndicator = 0,
                    swipePrimary = 0,
                    swipeSecondary = 0,
                    swipeCurrent = 0,
                ),
            )
    }
}
