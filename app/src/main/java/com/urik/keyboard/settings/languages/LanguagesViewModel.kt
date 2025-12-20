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
import kotlinx.coroutines.flow.first
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
                        activeLanguages = settings.activeLanguages,
                        primaryLanguage = settings.primaryLanguage,
                        primaryLayoutLanguage = settings.primaryLayoutLanguage,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = LanguagesUiState(),
                )

        fun toggleLanguage(languageTag: String) {
            viewModelScope.launch {
                val currentSettings = settingsRepository.settings.first()
                val currentActiveLanguages = currentSettings.activeLanguages.toMutableList()

                if (languageTag in currentActiveLanguages) {
                    if (currentActiveLanguages.size > 1) {
                        currentActiveLanguages.remove(languageTag)
                    }
                } else {
                    if (currentActiveLanguages.size < KeyboardSettings.MAX_ACTIVE_LANGUAGES) {
                        currentActiveLanguages.add(languageTag)
                    }
                }

                val newPrimaryLayoutLanguage =
                    currentActiveLanguages.firstOrNull()
                        ?: KeyboardSettings.DEFAULT_LANGUAGE

                settingsRepository
                    .updateActiveLanguages(
                        activeLanguages = currentActiveLanguages,
                        primaryLayoutLanguage = newPrimaryLayoutLanguage,
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
    val activeLanguages: List<String> = listOf(KeyboardSettings.DEFAULT_LANGUAGE),
    val primaryLanguage: String = KeyboardSettings.DEFAULT_LANGUAGE,
    val primaryLayoutLanguage: String = KeyboardSettings.DEFAULT_LANGUAGE,
)
