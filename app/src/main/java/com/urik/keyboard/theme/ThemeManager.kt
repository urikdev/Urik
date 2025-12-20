package com.urik.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.RequiresApi
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ThemeManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _currentTheme = MutableStateFlow<KeyboardTheme>(Default)
    val currentTheme: StateFlow<KeyboardTheme> = _currentTheme.asStateFlow()

    private var cachedMaterialYouTheme: KeyboardTheme? = null

    init {
        managerScope.launch {
            settingsRepository.settings.collect { settings ->
                val theme =
                    if (settings.keyboardTheme == MaterialYou.Default.id &&
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    ) {
                        generateMaterialYouTheme()
                    } else {
                        KeyboardTheme.fromId(settings.keyboardTheme)
                    }
                _currentTheme.emit(theme)
            }
        }
    }

    @RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun generateMaterialYouTheme(): KeyboardTheme {
        cachedMaterialYouTheme?.let { return it }

        val isLightTheme =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_NO

        val colors = extractMaterialYouColors(context, isLightTheme)

        return MaterialYou(colors).also { cachedMaterialYouTheme = it }
    }

    @RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun extractMaterialYouColors(
        context: Context,
        isLightTheme: Boolean,
    ): ThemeColors =
        if (isLightTheme) {
            ThemeColors(
                keyboardBackground = context.getColor(android.R.color.system_neutral1_50),
                keyBackgroundCharacter = context.getColor(android.R.color.system_accent1_100),
                keyBackgroundAction = context.getColor(android.R.color.system_accent2_200),
                keyBackgroundSpace = context.getColor(android.R.color.system_accent1_100),
                keyTextCharacter = context.getColor(android.R.color.system_accent1_700),
                keyTextAction = context.getColor(android.R.color.system_accent2_800),
                keyBorder = context.getColor(android.R.color.system_neutral2_400),
                keyBorderFocused = context.getColor(android.R.color.system_accent1_500),
                keyBorderPressed = context.getColor(android.R.color.system_accent1_600),
                statePressed = context.getColor(android.R.color.system_neutral2_200),
                stateActivated = context.getColor(android.R.color.system_accent1_200),
                stateCapsLock = context.getColor(android.R.color.system_accent2_300),
                suggestionBarBackground = context.getColor(android.R.color.system_neutral2_100),
                suggestionText = context.getColor(android.R.color.system_neutral1_900),
                keyShadow = 0x0D000000,
                focusIndicator = context.getColor(android.R.color.system_accent1_500),
                swipePrimary = context.getColor(android.R.color.system_accent1_600),
                swipeSecondary = context.getColor(android.R.color.system_accent2_500),
                swipeCurrent = context.getColor(android.R.color.system_accent3_400),
            )
        } else {
            ThemeColors(
                keyboardBackground = context.getColor(android.R.color.system_neutral1_900),
                keyBackgroundCharacter = context.getColor(android.R.color.system_accent1_800),
                keyBackgroundAction = context.getColor(android.R.color.system_accent2_700),
                keyBackgroundSpace = context.getColor(android.R.color.system_accent1_800),
                keyTextCharacter = context.getColor(android.R.color.system_accent1_100),
                keyTextAction = context.getColor(android.R.color.system_accent2_100),
                keyBorder = context.getColor(android.R.color.system_neutral2_600),
                keyBorderFocused = context.getColor(android.R.color.system_accent1_300),
                keyBorderPressed = context.getColor(android.R.color.system_accent1_200),
                statePressed = context.getColor(android.R.color.system_neutral2_700),
                stateActivated = context.getColor(android.R.color.system_accent1_700),
                stateCapsLock = context.getColor(android.R.color.system_accent2_600),
                suggestionBarBackground = context.getColor(android.R.color.system_neutral2_800),
                suggestionText = context.getColor(android.R.color.system_neutral1_100),
                keyShadow = 0x1AFFFFFF,
                focusIndicator = context.getColor(android.R.color.system_accent1_300),
                swipePrimary = context.getColor(android.R.color.system_accent1_200),
                swipeSecondary = context.getColor(android.R.color.system_accent2_300),
                swipeCurrent = context.getColor(android.R.color.system_accent3_400),
            )
        }

    fun getAllThemes(): List<KeyboardTheme> =
        buildList {
            add(Default)
            add(Light)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(generateMaterialYouTheme())
            }
            add(Abyss)
            add(Crimson)
            add(Forest)
            add(Sunset)
            add(Ocean)
            add(Lavender)
            add(Mocha)
            add(Slate)
            add(Peach)
            add(Mint)
            add(Neon)
            add(Ember)
            add(Steel)
        }

    suspend fun setTheme(theme: KeyboardTheme) {
        settingsRepository.updateKeyboardTheme(theme.id)
    }
}
