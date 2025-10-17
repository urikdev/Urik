package com.urik.keyboard.settings.soundfeedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.settings.VibrationStrength
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
 * Manages sound and feedback settings state and updates.
 */
@HiltViewModel
class SoundFeedbackViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        val uiState: StateFlow<SoundFeedbackUiState> =
            settingsRepository.settings
                .map { settings ->
                    SoundFeedbackUiState(
                        hapticFeedback = settings.hapticFeedback,
                        vibrationStrength = settings.vibrationStrength,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = SoundFeedbackUiState(),
                )

        fun updateHapticFeedback(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateHapticFeedback(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.HapticFeedbackToggleFailed) }
            }
        }

        fun updateVibrationStrength(strength: VibrationStrength) {
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

/**
 * UI state for sound and feedback settings.
 */
data class SoundFeedbackUiState(
    val hapticFeedback: Boolean = true,
    val vibrationStrength: VibrationStrength = VibrationStrength.MEDIUM,
)
