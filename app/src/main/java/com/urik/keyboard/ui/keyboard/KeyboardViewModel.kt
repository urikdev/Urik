package com.urik.keyboard.ui.keyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ibm.icu.util.ULocale
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keyboard UI state and layout management.
 *
 */
@HiltViewModel
class KeyboardViewModel
    @Inject
    constructor(
        private val repository: KeyboardRepository,
        private val languageManager: LanguageManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(KeyboardState())
        val state: StateFlow<KeyboardState> = _state.asStateFlow()

        private val _layout = MutableStateFlow<KeyboardLayout?>(null)
        val layout: StateFlow<KeyboardLayout?> = _layout.asStateFlow()

        @Suppress("ktlint:standard:backing-property-naming")
        private val _events = MutableSharedFlow<KeyboardEvent>()

        private var currentActionType: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER
        private var loadJob: Job? = null

        /**
         * Updates action key type (enter/search/send/go/etc).
         *
         * Cancels in-flight layout load, reloads current mode with new action.
         * Called when input field type changes (text → URL → email).
         */
        fun updateActionType(actionType: KeyboardKey.ActionType) {
            if (actionType != currentActionType) {
                currentActionType = actionType
                startLoadLayout(_state.value.currentMode)
            }
        }

        init {
            viewModelScope.launch {
                loadLayout(KeyboardMode.LETTERS)
            }

            viewModelScope.launch {
                _events.collect { event ->
                    handleEvent(event)
                }
            }

            viewModelScope.launch {
                languageManager.currentLanguage
                    .drop(1)
                    .collect { language ->
                        if (language != null) {
                            startLoadLayout(_state.value.currentMode)
                        }
                    }
            }
        }

        fun onEvent(event: KeyboardEvent) {
            viewModelScope.launch {
                _events.emit(event)
            }
        }

        /**
         * Returns character to insert, applying shift/caps lock.
         *
         * Capitalization rules:
         * - Letters: Uppercase if shift OR caps lock active
         * - Others: No transformation
         *
         * Note: Shift cleared by caller after character inserted.
         */
        fun getCharacterForInput(key: KeyboardKey.Character): String {
            val shouldCap = shouldCapitalize()
            return when {
                key.type == KeyboardKey.KeyType.LETTER && shouldCap -> {
                    val result = key.value.uppercase()
                    result
                }
                else -> {
                    key.value
                }
            }
        }

        private fun shouldCapitalize(): Boolean {
            val result = _state.value.isShiftPressed || _state.value.isCapsLockOn
            return result
        }

        fun enableAutoCapitalization() {
            updateState { it.copy(isShiftPressed = true) }
        }

        /**
         * Checks if auto-capitalization should trigger.
         *
         * Triggers when:
         * - Buffer empty (sentence start)
         * - After sentence-ending punctuation (. ! ?) followed by whitespace only
         *
         * Does NOT trigger mid-sentence or after comma/semicolon.
         */
        fun shouldAutoCapitalize(textBeforeCursor: String?): Boolean {
            if (textBeforeCursor.isNullOrBlank()) {
                return true
            }

            val trimmed = textBeforeCursor.trim()
            if (trimmed.isEmpty()) {
                return true
            }

            val lastChar = trimmed.lastOrNull()
            if (lastChar in setOf('.', '!', '?')) {
                val afterPunctuation = textBeforeCursor.removePrefix(trimmed)
                return afterPunctuation.isEmpty() || afterPunctuation.all { it.isWhitespace() }
            }

            return false
        }

        fun checkAndApplyAutoCapitalization(textBeforeCursor: String?) {
            if (shouldAutoCapitalize(textBeforeCursor) && !_state.value.isCapsLockOn) {
                enableAutoCapitalization()
            }
        }

        private fun handleEvent(event: KeyboardEvent) {
            when (event) {
                is KeyboardEvent.KeyPressed -> handleKeyPress(event.key)
                is KeyboardEvent.ModeChanged -> switchMode(event.mode)
                is KeyboardEvent.ShiftStateChanged -> handleShiftStateChange(event.isPressed)
                is KeyboardEvent.CapsLockToggled -> handleCapsLockToggle()
            }
        }

        private fun handleKeyPress(key: KeyboardKey) {
            when (key) {
                is KeyboardKey.Character -> {
                    // Shift clearing happens after getCharacterForInput() is called
                }
                is KeyboardKey.Action -> {
                    viewModelScope.launch {
                        handleActionKey(key.action)
                    }
                }
            }
        }

        /**
         * Clears shift state after letter insertion.
         *
         * Only clears if shift (not caps lock) was active.
         * Call after character inserted to editor.
         */
        fun clearShiftAfterCharacter(key: KeyboardKey.Character) {
            if (key.type == KeyboardKey.KeyType.LETTER && _state.value.isShiftPressed && !_state.value.isCapsLockOn) {
                updateState { it.copy(isShiftPressed = false) }
            }
        }

        private fun handleActionKey(action: KeyboardKey.ActionType) {
            when (action) {
                KeyboardKey.ActionType.SHIFT -> {
                    val newShiftState = !_state.value.isShiftPressed
                    updateState { it.copy(isShiftPressed = newShiftState) }
                }
                KeyboardKey.ActionType.CAPS_LOCK -> {
                    val newCapsState = !_state.value.isCapsLockOn
                    updateState {
                        it.copy(
                            isCapsLockOn = newCapsState,
                            isShiftPressed = false,
                        )
                    }
                }
                KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> switchMode(KeyboardMode.LETTERS)
                KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> switchMode(KeyboardMode.NUMBERS)
                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> switchMode(KeyboardMode.SYMBOLS)
                else -> { }
            }
        }

        private fun handleShiftStateChange(isPressed: Boolean) {
            updateState { it.copy(isShiftPressed = isPressed) }
        }

        private fun handleCapsLockToggle() {
            updateState {
                it.copy(
                    isCapsLockOn = !it.isCapsLockOn,
                    isShiftPressed = false,
                )
            }
        }

        private fun switchMode(mode: KeyboardMode) {
            if (mode != _state.value.currentMode) {
                startLoadLayout(mode)
                updateState { it.copy(currentMode = mode) }
            }
        }

        /**
         * Cancels in-flight layout load and starts new load.
         *
         * Prevents race conditions when mode/language/action changes rapidly.
         * Only one layout load active at a time.
         */
        private fun startLoadLayout(mode: KeyboardMode) {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    loadLayout(mode)
                }
        }

        private suspend fun loadLayout(mode: KeyboardMode) {
            updateState { it.copy(isLoading = true, error = null) }

            try {
                val currentLocale = getCurrentLocale()

                val result = repository.getLayoutForMode(mode, currentLocale, currentActionType)

                if (result.isSuccess) {
                    _layout.value = result.getOrNull()
                    updateState { it.copy(isLoading = false) }
                } else {
                    val exception = result.exceptionOrNull()
                    ErrorLogger.logException(
                        component = "KeyboardViewModel",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = exception ?: Exception("Layout loading failed"),
                        context =
                            mapOf(
                                "mode" to mode.name,
                                "locale" to currentLocale.toString(),
                            ),
                    )
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = exception?.message ?: "Unknown error loading layout",
                        )
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KeyboardViewModel",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context =
                        mapOf(
                            "mode" to mode.name,
                        ),
                )
                updateState {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load keyboard layout",
                    )
                }
            }
        }

        private fun getCurrentLocale(): ULocale =
            languageManager.currentLanguage.value?.let { userLanguage ->
                val languageOnly = userLanguage.languageTag.split("-").first()
                ULocale.forLanguageTag(languageOnly)
            } ?: ULocale.forLanguageTag("en")

        private fun updateState(update: (KeyboardState) -> KeyboardState) {
            _state.value = update(_state.value)
        }
    }
