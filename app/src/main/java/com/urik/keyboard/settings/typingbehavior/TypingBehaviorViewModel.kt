package com.urik.keyboard.settings.typingbehavior

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.LongPressDuration
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
                        longPressDuration = settings.longPressDuration,
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

        fun updateLongPressDuration(duration: LongPressDuration) {
            viewModelScope.launch {
                settingsRepository
                    .updateLongPressDuration(duration)
                    .onFailure { _events.emit(SettingsEvent.Error.LongPressDurationUpdateFailed) }
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
    val longPressDuration: LongPressDuration = LongPressDuration.MEDIUM,
)
