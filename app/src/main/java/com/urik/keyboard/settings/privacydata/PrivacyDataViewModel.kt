package com.urik.keyboard.settings.privacydata

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.service.DictionaryBackupManager
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
class PrivacyDataViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val dictionaryBackupManager: DictionaryBackupManager,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        data class PrivacyDataUiState(
            val clipboardEnabled: Boolean = true,
        )

        val uiState: StateFlow<PrivacyDataUiState> =
            settingsRepository.settings
                .map { settings ->
                    PrivacyDataUiState(
                        clipboardEnabled = settings.clipboardEnabled,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = PrivacyDataUiState(),
                )

        fun updateClipboardEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository
                    .updateClipboardEnabled(enabled)
                    .onFailure { _events.emit(SettingsEvent.Error.ClipboardToggleFailed) }
            }
        }

        fun clearLearnedWords() {
            viewModelScope.launch {
                settingsRepository
                    .clearLearnedWords()
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

        fun exportDictionary(uri: Uri) {
            viewModelScope.launch {
                dictionaryBackupManager
                    .exportToUri(uri)
                    .onSuccess { result ->
                        _events.emit(SettingsEvent.Success.DictionaryExported(result.wordCount))
                    }.onFailure { _events.emit(SettingsEvent.Error.DictionaryExportFailed) }
            }
        }

        fun importDictionary(uri: Uri) {
            viewModelScope.launch {
                dictionaryBackupManager
                    .importFromUri(uri)
                    .onSuccess { result ->
                        _events.emit(
                            SettingsEvent.Success.DictionaryImported(
                                result.newWords,
                                result.updatedWords,
                            ),
                        )
                    }.onFailure { _events.emit(SettingsEvent.Error.DictionaryImportFailed) }
            }
        }
    }
