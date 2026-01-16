package com.urik.keyboard.settings.layoutinput

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.AlternativeKeyboardLayout
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.settings.SpaceBarSize
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
 * Manages layout and input settings state and updates.
 */
@HiltViewModel
class LayoutInputViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        val uiState: StateFlow<LayoutInputUiState> =
            settingsRepository.settings
                .map { settings ->
                    LayoutInputUiState(
                        showNumberRow = settings.showNumberRow,
                        spaceBarSize = settings.spaceBarSize,
                        alternativeKeyboardLayout = settings.alternativeKeyboardLayout,
                        adaptiveKeyboardModesEnabled = settings.adaptiveKeyboardModesEnabled,
                        oneHandedModeEnabled = settings.oneHandedModeEnabled,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = LayoutInputUiState(),
                )

        fun updateShowNumberRow(show: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateShowNumberRow(show)
                    .onFailure { _events.emit(SettingsEvent.Error.NumberRowToggleFailed) }
            }
        }

        fun updateSpaceBarSize(size: SpaceBarSize) {
            viewModelScope.launch {
                settingsRepository
                    .updateSpaceBarSize(size)
                    .onFailure { _events.emit(SettingsEvent.Error.SpaceBarSizeUpdateFailed) }
            }
        }

        fun updateAlternativeKeyboardLayout(layout: AlternativeKeyboardLayout) {
            viewModelScope.launch {
                settingsRepository
                    .updateAlternativeKeyboardLayout(layout)
                    .onFailure { _events.emit(SettingsEvent.Error.AlternativeLayoutUpdateFailed) }
            }
        }

        fun updateAdaptiveKeyboardModesEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.updateAdaptiveKeyboardModesEnabled(enabled)
            }
        }

        fun updateOneHandedModeEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.updateOneHandedModeEnabled(enabled)
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }

/**
 * UI state for layout and input settings.
 */
data class LayoutInputUiState(
    val showNumberRow: Boolean = true,
    val spaceBarSize: SpaceBarSize = SpaceBarSize.STANDARD,
    val alternativeKeyboardLayout: AlternativeKeyboardLayout = AlternativeKeyboardLayout.DEFAULT,
    val adaptiveKeyboardModesEnabled: Boolean = true,
    val oneHandedModeEnabled: Boolean = false,
)
