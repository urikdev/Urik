package com.urik.keyboard.settings.languages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.settings.KeyboardSettings
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
 * Manages language selection state and updates.
 */
@HiltViewModel
class LanguagesViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<SettingsEvent>()
        val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

        val uiState: StateFlow<LanguagesUiState> =
            settingsRepository.settings
                .map { settings ->
                    LanguagesUiState(
                        primaryLanguage = settings.primaryLanguage,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = LanguagesUiState(),
                )

        fun selectLanguage(languageTag: String) {
            viewModelScope.launch {
                settingsRepository
                    .updateLanguageSettings(
                        activeLanguages = setOf(languageTag),
                        primaryLanguage = languageTag,
                    ).onFailure { _events.emit(SettingsEvent.Error.LanguageUpdateFailed) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }

/**
 * UI state for language settings.
 */
data class LanguagesUiState(
    val primaryLanguage: String = KeyboardSettings.DEFAULT_LANGUAGE,
)
