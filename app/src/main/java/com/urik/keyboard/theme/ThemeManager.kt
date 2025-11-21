package com.urik.keyboard.theme

import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ThemeManager(
    private val settingsRepository: SettingsRepository,
) {
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _currentTheme = MutableStateFlow<KeyboardTheme>(Default)
    val currentTheme: StateFlow<KeyboardTheme> = _currentTheme.asStateFlow()

    init {
        managerScope.launch {
            settingsRepository.settings.collect { settings ->
                val theme = KeyboardTheme.fromId(settings.keyboardTheme)
                _currentTheme.emit(theme)
            }
        }
    }

    suspend fun setTheme(theme: KeyboardTheme) {
        settingsRepository.updateKeyboardTheme(theme.id)
    }
}
