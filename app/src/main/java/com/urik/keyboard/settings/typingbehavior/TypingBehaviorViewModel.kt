package com.urik.keyboard.settings.typingbehavior

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.CursorSpeed
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.LongPressPunctuationMode
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

/**
 * Manages typing behavior settings state and updates.
 */
@HiltViewModel
class TypingBehaviorViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        val uiState: StateFlow<TypingBehaviorUiState> =
            settingsRepository.settings
                .map { settings ->
                    TypingBehaviorUiState(
                        doubleSpacePeriod = settings.doubleSpacePeriod,
                        swipeEnabled = settings.swipeEnabled,
                        spacebarCursorControl = settings.spacebarCursorControl,
                        cursorSpeed = settings.cursorSpeed,
                        backspaceSwipeDelete = settings.backspaceSwipeDelete,
                        longPressPunctuationMode = settings.longPressPunctuationMode,
                        longPressDuration = settings.longPressDuration,
                        hapticFeedback = settings.hapticFeedback,
                        vibrationStrength = settings.vibrationStrength,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = TypingBehaviorUiState(),
                )

        fun updateDoubleSpacePeriod(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateDoubleSpacePeriod(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.DoubleSpacePeriodToggleFailed) }
            }
        }

        fun updateSwipeEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateSwipeEnabled(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.SwipeToggleFailed) }
            }
        }

        fun updateSpacebarCursorControl(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateSpacebarCursorControl(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.SpacebarCursorToggleFailed) }
            }
        }

        fun updateCursorSpeed(speed: CursorSpeed) {
            viewModelScope.launch {
                settingsRepository
                    .updateCursorSpeed(speed)
                    .onFailure { _events.emit(SettingsEvent.Error.CursorSpeedUpdateFailed) }
            }
        }

        fun updateBackspaceSwipeDelete(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateBackspaceSwipeDelete(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.BackspaceSwipeToggleFailed) }
            }
        }

        fun updateLongPressPunctuationMode(mode: LongPressPunctuationMode) {
            viewModelScope.launch {
                settingsRepository
                    .updateLongPressPunctuationMode(mode)
                    .onFailure { _events.emit(SettingsEvent.Error.LongPressPunctuationModeUpdateFailed) }
            }
        }

        fun updateLongPressDuration(duration: LongPressDuration) {
            viewModelScope.launch {
                settingsRepository
                    .updateLongPressDuration(duration)
                    .onFailure { _events.emit(SettingsEvent.Error.LongPressDurationUpdateFailed) }
            }
        }

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

/**
 * UI state for typing behavior settings.
 */
data class TypingBehaviorUiState(
    val doubleSpacePeriod: Boolean = true,
    val swipeEnabled: Boolean = true,
    val spacebarCursorControl: Boolean = true,
    val cursorSpeed: CursorSpeed = CursorSpeed.MEDIUM,
    val backspaceSwipeDelete: Boolean = true,
    val longPressPunctuationMode: LongPressPunctuationMode = LongPressPunctuationMode.PERIOD,
    val longPressDuration: LongPressDuration = LongPressDuration.MEDIUM,
    val hapticFeedback: Boolean = true,
    val vibrationStrength: Int = 128,
)
