package com.urik.keyboard.settings.appearance

import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.KeyLabelSize
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.settings.Theme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages appearance settings state and updates.
 */
@HiltViewModel
class AppearanceViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        val uiState: StateFlow<AppearanceUiState> =
            settingsRepository.settings
                .map { settings ->
                    AppearanceUiState(
                        theme = settings.theme,
                        keySize = settings.keySize,
                        keyLabelSize = settings.keyLabelSize,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = AppearanceUiState(),
                )

        fun updateTheme(theme: Theme) {
            viewModelScope.launch {
                settingsRepository
                    .updateTheme(theme)
                    .onSuccess {
                        val nightMode =
                            when (theme) {
                                Theme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                                Theme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                                Theme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            }
                        AppCompatDelegate.setDefaultNightMode(nightMode)
                    }.onFailure { _events.emit(SettingsEvent.Error.ThemeUpdateFailed) }
            }
        }

        fun updateKeySize(size: KeySize) {
            viewModelScope.launch {
                settingsRepository
                    .updateKeySize(size)
                    .onFailure { _events.emit(SettingsEvent.Error.KeySizeUpdateFailed) }
            }
        }

        fun updateKeyLabelSize(size: KeyLabelSize) {
            viewModelScope.launch {
                settingsRepository
                    .updateKeyLabelSize(size)
                    .onFailure { _events.emit(SettingsEvent.Error.KeyLabelSizeUpdateFailed) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }

/**
 * UI state for appearance settings.
 */
data class AppearanceUiState(
    val theme: Theme = Theme.SYSTEM,
    val keySize: KeySize = KeySize.MEDIUM,
    val keyLabelSize: KeyLabelSize = KeyLabelSize.MEDIUM,
)
