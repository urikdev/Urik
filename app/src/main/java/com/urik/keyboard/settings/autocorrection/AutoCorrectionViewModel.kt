package com.urik.keyboard.settings.autocorrection

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

/**
 * Manages auto-correction settings state and updates.
 */
@HiltViewModel
class AutoCorrectionViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        val uiState: StateFlow<AutoCorrectionUiState> =
            settingsRepository.settings
                .map { settings ->
                    AutoCorrectionUiState(
                        spellCheckEnabled = settings.spellCheckEnabled,
                        showSuggestions = settings.showSuggestions,
                        suggestionCount = settings.suggestionCount,
                        learnNewWords = settings.learnNewWords,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = AutoCorrectionUiState(),
                )

        fun updateSpellCheckEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateSpellCheckEnabled(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.SpellCheckToggleFailed) }
            }
        }

        fun updateShowSuggestions(show: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateShowSuggestions(show)
                    .onFailure { _events.emit(SettingsEvent.Error.SuggestionToggleFailed) }
            }
        }

        fun updateSuggestionCount(count: Int) {
            viewModelScope.launch {
                settingsRepository
                    .updateSuggestionCount(count)
                    .onFailure { _events.emit(SettingsEvent.Error.SuggestionCountUpdateFailed) }
            }
        }

        fun updateLearnNewWords(learn: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateLearnNewWords(learn)
                    .onFailure { _events.emit(SettingsEvent.Error.WordLearningToggleFailed) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }

/**
 * UI state for auto-correction settings.
 */
data class AutoCorrectionUiState(
    val spellCheckEnabled: Boolean = true,
    val showSuggestions: Boolean = true,
    val suggestionCount: Int = 3,
    val learnNewWords: Boolean = true,
)
