package com.urik.keyboard.settings.layoutmapper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.data.CustomKeyMappingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LayoutMapperEvent {
    data object MappingSaved : LayoutMapperEvent()

    data object MappingRemoved : LayoutMapperEvent()

    data object AllMappingsCleared : LayoutMapperEvent()

    data object SaveFailed : LayoutMapperEvent()

    data object RemoveFailed : LayoutMapperEvent()

    data object ClearFailed : LayoutMapperEvent()
}

/**
 * ViewModel for the Layout Mapper settings screen.
 */
@HiltViewModel
class LayoutMapperViewModel
    @Inject
    constructor(
        private val repository: CustomKeyMappingRepository,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<LayoutMapperEvent>()
        val events: SharedFlow<LayoutMapperEvent> = _events.asSharedFlow()

        private val _selectedKey = MutableStateFlow<String?>(null)
        val selectedKey: StateFlow<String?> = _selectedKey.asStateFlow()

        val mappings: StateFlow<Map<String, String>> =
            repository.mappings
                .map { list -> list.associate { it.baseKey to it.customSymbol } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = emptyMap(),
                )

        fun selectKey(key: String) {
            _selectedKey.value = key.lowercase()
        }

        fun clearSelection() {
            _selectedKey.value = null
        }

        fun saveMapping(
            baseKey: String,
            customSymbol: String,
        ) {
            if (customSymbol.isBlank()) {
                removeMapping(baseKey)
                return
            }

            viewModelScope.launch {
                repository
                    .setMapping(baseKey, customSymbol.trim())
                    .onSuccess {
                        _events.emit(LayoutMapperEvent.MappingSaved)
                        clearSelection()
                    }.onFailure {
                        _events.emit(LayoutMapperEvent.SaveFailed)
                    }
            }
        }

        fun removeMapping(baseKey: String) {
            viewModelScope.launch {
                repository
                    .removeMapping(baseKey)
                    .onSuccess {
                        _events.emit(LayoutMapperEvent.MappingRemoved)
                        clearSelection()
                    }.onFailure {
                        _events.emit(LayoutMapperEvent.RemoveFailed)
                    }
            }
        }

        fun clearAllMappings() {
            viewModelScope.launch {
                repository
                    .clearAllMappings()
                    .onSuccess {
                        _events.emit(LayoutMapperEvent.AllMappingsCleared)
                    }.onFailure {
                        _events.emit(LayoutMapperEvent.ClearFailed)
                    }
            }
        }

        fun getMappingForKey(baseKey: String): String? = mappings.value[baseKey.lowercase()]

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }
