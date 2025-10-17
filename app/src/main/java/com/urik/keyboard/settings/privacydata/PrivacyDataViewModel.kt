package com.urik.keyboard.settings.privacydata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages privacy and data management operations.
 */
@HiltViewModel
class PrivacyDataViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        fun clearLearnedWords() {
            viewModelScope.launch {
                settingsRepository
                    .clearLearnedWords()
                    .onSuccess { _events.emit(SettingsEvent.Success.LearnedWordsCleared) }
                    .onFailure { _events.emit(SettingsEvent.Error.ClearLearnedWordsFailed) }
            }
        }

        fun clearAllData() {
            viewModelScope.launch {
                settingsRepository
                    .clearAllData()
                    .onSuccess { _events.emit(SettingsEvent.Success.LearnedWordsCleared) }
                    .onFailure { _events.emit(SettingsEvent.Error.ClearLearnedWordsFailed) }
            }
        }

        fun resetToDefaults() {
            viewModelScope.launch {
                settingsRepository
                    .resetToDefaults()
                    .onSuccess { _events.emit(SettingsEvent.Success.SettingsReset) }
                    .onFailure { _events.emit(SettingsEvent.Error.ResetToDefaultsFailed) }
            }
        }
    }
