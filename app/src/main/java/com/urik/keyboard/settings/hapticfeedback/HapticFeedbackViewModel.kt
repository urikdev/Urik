package com.urik.keyboard.settings.hapticfeedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
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

@HiltViewModel
class HapticFeedbackViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        val uiState: StateFlow<HapticFeedbackUiState> =
            settingsRepository.settings
                .map { settings ->
                    HapticFeedbackUiState(
                        hapticFeedback = settings.hapticFeedback,
                        vibrationStrength = settings.vibrationStrength,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = HapticFeedbackUiState(),
                )

        fun updateHapticFeedback(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateHapticFeedback(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.HapticFeedbackToggleFailed) }
            }
        }

        fun updateVibrationStrength(strength: Int) {
            viewModelScope.launch {
                settingsRepository
                    .updateVibrationStrength(strength)
                    .onFailure { _events.emit(SettingsEvent.Error.VibrationStrengthUpdateFailed) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }

data class HapticFeedbackUiState(
    val hapticFeedback: Boolean = true,
    val vibrationStrength: Int = 128,
)
