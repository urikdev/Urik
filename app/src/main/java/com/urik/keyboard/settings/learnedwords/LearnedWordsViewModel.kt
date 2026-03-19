package com.urik.keyboard.settings.learnedwords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.settings.SettingsEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LearnedWordsUiState(val words: List<String> = emptyList(), val isLoading: Boolean = true)

@HiltViewModel
class LearnedWordsViewModel
@Inject
constructor(private val wordLearningEngine: WordLearningEngine) : ViewModel() {
    private val _uiState = MutableStateFlow(LearnedWordsUiState())
    val uiState: StateFlow<LearnedWordsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        loadWords()
    }

    fun loadWords() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            wordLearningEngine.getAllLearnedWordsUnified()
                .onSuccess { words ->
                    _uiState.value = LearnedWordsUiState(words = words, isLoading = false)
                }
                .onFailure {
                    _uiState.value = LearnedWordsUiState(words = emptyList(), isLoading = false)
                }
        }
    }

    fun deleteWord(word: String) {
        viewModelScope.launch {
            wordLearningEngine.deleteWordCompletely(word)
                .onSuccess {
                    _events.emit(SettingsEvent.Success.WordDeleted)
                    loadWords()
                }
                .onFailure {
                    _events.emit(SettingsEvent.Error.DeleteWordFailed)
                }
        }
    }

    fun deleteAllWords() {
        viewModelScope.launch {
            wordLearningEngine.deleteAllWordsCompletely()
                .onSuccess {
                    _events.emit(SettingsEvent.Success.AllWordsDeleted)
                    loadWords()
                }
                .onFailure {
                    _events.emit(SettingsEvent.Error.DeleteAllWordsFailed)
                }
        }
    }
}
