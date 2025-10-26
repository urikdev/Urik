package com.urik.keyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.InputMethod
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.ProcessingResult
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.service.WordState
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.ui.keyboard.components.SwipeKeyboardView
import com.urik.keyboard.utils.ActionDetector
import com.urik.keyboard.utils.BackspaceUtils
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.KeyboardModeUtils
import com.urik.keyboard.utils.SecureFieldDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Main input method service for the Urik keyboard.
 *
 * Handles text input processing, word learning, spell checking, suggestions,
 * swipe gestures, and keyboard lifecycle management.
 */
@AndroidEntryPoint
class UrikInputMethodService :
    InputMethodService(),
    LifecycleOwner {
    /**
     * Spell confirmation state for misspelled words.
     */
    private enum class SpellConfirmationState {
        NORMAL,
        AWAITING_CONFIRMATION,
    }

    @Inject
    lateinit var repository: KeyboardRepository

    @Inject
    lateinit var swipeDetector: SwipeDetector

    @Inject
    lateinit var languageManager: LanguageManager

    @Inject
    lateinit var wordLearningEngine: WordLearningEngine

    @Inject
    lateinit var cacheMemoryManager: CacheMemoryManager

    @Inject
    lateinit var characterVariationService: CharacterVariationService

    @Inject
    lateinit var spellCheckManager: SpellCheckManager

    @Inject
    lateinit var textInputProcessor: TextInputProcessor

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private lateinit var viewModel: KeyboardViewModel
    private lateinit var layoutManager: KeyboardLayoutManager
    private lateinit var lifecycleRegistry: LifecycleRegistry

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val observerJobs = mutableListOf<Job>()

    private var swipeKeyboardView: SwipeKeyboardView? = null

    private var lastSpaceTime: Long = 0
    private var doubleTapSpaceThreshold: Long = 300L

    private var lastShiftTime: Long = 0
    private var doubleShiftThreshold: Long = 400L

    private var suggestionDebounceJob: Job? = null
    private val suggestionDebounceDelay = 200L

    @Volatile
    private var displayBuffer = ""
    private var processingSequence = 0L
    private val processingLock = Any()

    @Volatile
    private var wordState = WordState()

    @Volatile
    private var composingRegionStart: Int = -1

    @Volatile
    private var isActivelyEditing = false

    @Volatile
    private var isRecomposing = false

    @Volatile
    private var pendingSuggestions: List<String> = emptyList()

    @Volatile
    private var spellConfirmationState = SpellConfirmationState.NORMAL

    @Volatile
    private var pendingWordForLearning: String? = null

    @Volatile
    private var isSecureField: Boolean = false

    @Volatile
    private var currentInputAction: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER

    private var currentSettings: KeyboardSettings = KeyboardSettings()

    @Volatile
    private var isAcceleratedDeletion = false

    fun setAcceleratedDeletion(active: Boolean) {
        isAcceleratedDeletion = active
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /**
     * Clears all state for secure text fields.
     */
    private fun clearSecureFieldState() {
        displayBuffer = ""
        wordState = WordState()
        synchronized(processingLock) {
            processingSequence++
        }
        pendingSuggestions = emptyList()
        clearSpellConfirmationState()
        swipeKeyboardView?.clearSuggestions()
        currentInputConnection?.finishComposingText()

        spellCheckManager.clearCaches()
        textInputProcessor.clearCaches()

        lastSpaceTime = 0
        lastShiftTime = 0
    }

    /**
     * Validates whether text contains valid input characters.
     *
     * @param text Text to validate
     * @return True if text contains letters, ideographs, or valid punctuation
     */
    private fun isValidTextInput(text: String): Boolean = CursorEditingUtils.isValidTextInput(text)

    /**
     * Checks if character input represents a letter.
     *
     * @param char Character string to check
     * @return True if character is valid letter input
     */
    private fun isLetterInput(char: String): Boolean = char.isNotEmpty() && isValidTextInput(char)

    /**
     * Updates script context for all relevant components.
     *
     * @param locale Locale to derive script from
     */
    private fun updateScriptContext(locale: ULocale) {
        layoutManager.updateScriptContext()
        swipeDetector.updateScriptContext(locale)
        textInputProcessor.updateScriptContext(locale, UScript.LATIN)
        spellCheckManager.clearCaches()
    }

    /**
     * Clears spell confirmation state and optionally commits current word.
     */
    private fun clearSpellConfirmationState() {
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null

        try {
            if (displayBuffer.isNotEmpty()) {
                currentInputConnection?.setComposingText(displayBuffer, 1)
            } else {
                currentInputConnection?.finishComposingText()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Highlights current word with error styling.
     */
    private fun highlightCurrentWord() {
        try {
            if (displayBuffer.isNotEmpty()) {
                val spannableString = SpannableString(displayBuffer)

                spannableString.setSpan(
                    BackgroundColorSpan(Color.RED),
                    0,
                    displayBuffer.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                spannableString.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    0,
                    displayBuffer.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                currentInputConnection?.setComposingText(spannableString, 1)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Learns word and invalidates relevant caches.
     *
     * @param word Word to learn
     * @return True if learning succeeded or is disabled
     */
    private suspend fun learnWordAndInvalidateCache(word: String): Boolean {
        return try {
            val settings = textInputProcessor.getCurrentSettings()
            if (!settings.isWordLearningEnabled) {
                return true
            }

            val learnResult = wordLearningEngine.learnWord(word, InputMethod.SELECTED_FROM_SUGGESTION)
            if (learnResult.isSuccess) {
                spellCheckManager.invalidateWordCache(word)
                spellCheckManager.removeFromBlacklist(word)
                textInputProcessor.invalidateWord(word)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Confirms spell-checked word and learns it if pending.
     */
    private suspend fun confirmAndLearnWord() {
        withContext(Dispatchers.Main) {
            try {
                val wordToLearn = pendingWordForLearning

                isActivelyEditing = true

                currentInputConnection?.beginBatchEdit()
                try {
                    if (wordToLearn != null) {
                        learnWordAndInvalidateCache(
                            wordToLearn,
                        )

                        currentInputConnection?.finishComposingText()
                        currentInputConnection?.commitText(" ", 1)
                    } else {
                        coordinateWordCompletion()
                        currentInputConnection?.commitText(" ", 1)
                    }

                    coordinateStateClear()
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(" ", 1)
                    coordinateStateClear()
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }

            val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
            viewModel.checkAndApplyAutoCapitalization(textBefore)
        }
    }

    /**
     * Clears all input state and invalidates in-flight processing.
     */
    private fun coordinateStateClear() {
        synchronized(processingLock) {
            processingSequence++
        }

        suggestionDebounceJob?.cancel()

        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        clearSpellConfirmationState()
        swipeKeyboardView?.clearSuggestions()
        composingRegionStart = -1

        try {
            currentInputConnection?.finishComposingText()
        } catch (_: Exception) {
        }
    }

    /**
     * Updates word state and displays suggestions.
     *
     * @param newWordState New word state with suggestions
     */
    private fun coordinateStateTransition(newWordState: WordState) {
        wordState = newWordState

        if (newWordState.suggestions.isNotEmpty()) {
            val currentWord = displayBuffer.ifEmpty { wordState.buffer }
            val shouldCapitalize = currentWord.firstOrNull()?.isUpperCase() == true
            val displaySuggestions =
                if (shouldCapitalize) {
                    newWordState.suggestions.map { it.replaceFirstChar { c -> c.uppercase() } }
                } else {
                    newWordState.suggestions
                }

            pendingSuggestions = displaySuggestions
            swipeKeyboardView?.updateSuggestions(displaySuggestions)
        } else {
            pendingSuggestions = emptyList()
            swipeKeyboardView?.clearSuggestions()
        }
    }

    /**
     * Commits selected suggestion and learns word.
     *
     * @param suggestion Selected suggestion to commit
     */
    private suspend fun coordinateSuggestionSelection(suggestion: String) {
        withContext(Dispatchers.Main) {
            try {
                synchronized(processingLock) {
                    processingSequence++
                }

                isActivelyEditing = true

                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.commitText("$suggestion ", 1)
                    learnWordAndInvalidateCache(suggestion)

                    displayBuffer = ""
                    wordState = WordState()
                    pendingSuggestions = emptyList()
                    spellConfirmationState = SpellConfirmationState.NORMAL
                    pendingWordForLearning = null
                    composingRegionStart = -1
                    swipeKeyboardView?.clearSuggestions()

                    currentInputConnection?.finishComposingText()
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                coordinateStateClear()
            }
        }
    }

    /**
     * Completes current word and learns it.
     *
     */
    private suspend fun coordinateWordCompletion() {
        withContext(Dispatchers.Main) {
            try {
                isActivelyEditing = true

                val wordToCommit = displayBuffer.ifEmpty { wordState.buffer }

                if (wordToCommit.isNotEmpty()) {
                    currentInputConnection?.finishComposingText()
                }

                coordinateStateClear()
            } catch (_: Exception) {
                coordinateStateClear()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            lifecycleRegistry = LifecycleRegistry(this)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED

            initializeCoreComponents()

            serviceScope.launch {
                initializeServices()
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "onCreate"),
            )
            throw e
        }
    }

    /**
     * Initializes core keyboard components.
     */
    private fun initializeCoreComponents() {
        try {
            viewModel = KeyboardViewModel(repository, languageManager)
            layoutManager =
                KeyboardLayoutManager(
                    context = this,
                    onKeyClick = { key -> handleKeyPress(key) },
                    onWordDelete = { handleBackspaceWord() },
                    onAcceleratedDeletionChanged = { active -> setAcceleratedDeletion(active) },
                    characterVariationService = characterVariationService,
                    languageManager = languageManager,
                    cacheMemoryManager = cacheMemoryManager,
                )
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "core_init"),
            )
            throw e
        }
    }

    /**
     * Initializes language, spell check, and word learning services.
     */
    private suspend fun initializeServices() {
        try {
            val result = languageManager.initialize()
            if (result.isFailure) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception =
                        result.exceptionOrNull()
                            ?: Exception("Language manager initialization failed"),
                    context = mapOf("phase" to "language_init"),
                )
                return
            }

            try {
                spellCheckManager.clearCaches()
            } catch (_: Exception) {
            }

            try {
                textInputProcessor.clearCaches()
            } catch (_: Exception) {
            }

            val currentLanguage = languageManager.currentLanguage.value
            try {
                wordLearningEngine.initializeLearnedWordsCache(currentLanguage)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context =
                        mapOf(
                            "phase" to "word_learning_init",
                            "language" to currentLanguage,
                        ),
                )
            }

            val locale = ULocale.forLanguageTag(currentLanguage)
            updateScriptContext(locale)

            try {
                wordLearningEngine.getLearningStats()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "services_init"),
            )
        }
    }

    override fun onCreateInputView(): View? {
        try {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED

            val actualWindow = window?.window
            if (actualWindow != null) {
                WindowCompat.setDecorFitsSystemWindows(actualWindow, false)

                val layoutParams = actualWindow.attributes
                layoutParams.gravity = Gravity.BOTTOM
                layoutParams.flags =
                    layoutParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                actualWindow.attributes = layoutParams
            }

            return createSwipeKeyboardView()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "onCreateInputView"),
            )
            return null
        }
    }

    /**
     * Creates and configures swipe keyboard view.
     *
     * @return Configured keyboard view or null on failure
     */
    private fun createSwipeKeyboardView(): View? =
        try {
            if (!::viewModel.isInitialized || !::layoutManager.isInitialized) {
                initializeCoreComponents()
            }

            val swipeView =
                SwipeKeyboardView(this).apply {
                    initialize(layoutManager, swipeDetector, spellCheckManager, wordLearningEngine)
                    setOnKeyClickListener { key -> handleKeyPress(key) }
                    setOnSwipeWordListener { validatedWord -> handleSwipeWord(validatedWord) }
                    setOnSuggestionClickListener { suggestion -> handleSuggestionSelected(suggestion) }
                    setOnSuggestionLongPressListener { suggestion ->
                        handleSuggestionRemoval(
                            suggestion,
                        )
                    }
                    setOnEmojiSelectedListener { selectedEmoji ->
                        handleEmojiSelected(selectedEmoji)
                    }
                }

            swipeKeyboardView = swipeView
            updateSwipeKeyboard()
            observeViewModel()

            swipeView
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("phase" to "create_keyboard_view"),
            )
            null
        }

    /**
     * Handles emoji selection from picker.
     *
     * @param emoji Selected emoji string
     */
    private fun handleEmojiSelected(emoji: String) {
        serviceScope.launch {
            try {
                if (displayBuffer.isNotEmpty()) {
                    val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                    val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                if (!isSecureField && displayBuffer.isNotEmpty()) {
                    coordinateWordCompletion()
                }

                currentInputConnection?.commitText(emoji, 1)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Observes settings changes and updates keyboard components.
     */
    private fun observeSettings() {
        observerJobs.add(
            serviceScope.launch {
                settingsRepository.settings.collect { newSettings ->
                    if (!::layoutManager.isInitialized || !::swipeDetector.isInitialized) {
                        return@collect
                    }

                    val needsLayoutRebuild =
                        currentSettings.keySize != newSettings.keySize ||
                            currentSettings.spaceBarSize != newSettings.spaceBarSize ||
                            currentSettings.keyLabelSize != newSettings.keyLabelSize ||
                            currentSettings.theme != newSettings.theme ||
                            currentSettings.showNumberRow != newSettings.showNumberRow

                    currentSettings = newSettings

                    layoutManager.updateLongPressDuration(newSettings.longPressDuration)

                    layoutManager.updateKeySize(newSettings.keySize)
                    layoutManager.updateSpaceBarSize(newSettings.spaceBarSize)
                    layoutManager.updateKeyLabelSize(newSettings.keyLabelSize)
                    layoutManager.updateTheme(newSettings.theme)

                    layoutManager.updateHapticSettings(
                        newSettings.hapticFeedback,
                        newSettings.vibrationStrength.durationMs,
                    )

                    if (needsLayoutRebuild) {
                        withContext(Dispatchers.Main) {
                            updateSwipeKeyboard()
                        }
                    }
                }
            },
        )
    }

    /**
     * Observes view model state, layout, and language changes.
     */
    private fun observeViewModel() {
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        observerJobs.add(
            serviceScope.launch {
                viewModel.state.collect { updateSwipeKeyboard() }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                viewModel.layout.collect { layout ->
                    if (layout != null) updateSwipeKeyboard()
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.currentLanguage.collect { detectedLanguage ->
                    val locale = ULocale.forLanguageTag(detectedLanguage)
                    updateScriptContext(locale)
                }
            },
        )

        observeSettings()
    }

    /**
     * Updates keyboard view with current layout and state.
     */
    private fun updateSwipeKeyboard() {
        try {
            val state = viewModel.state.value
            val layout = viewModel.layout.value

            if (layout != null && swipeKeyboardView != null) {
                val filteredLayout =
                    if (
                        !currentSettings.showNumberRow &&
                        layout.mode == KeyboardMode.LETTERS &&
                        layout.rows.isNotEmpty()
                    ) {
                        layout.copy(rows = layout.rows.drop(1))
                    } else {
                        layout
                    }

                swipeKeyboardView?.updateKeyboard(filteredLayout, state)
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        layoutManager.updateLongPressDuration(currentSettings.longPressDuration)
        layoutManager.updateKeySize(currentSettings.keySize)
        layoutManager.updateSpaceBarSize(currentSettings.spaceBarSize)
        layoutManager.updateKeyLabelSize(currentSettings.keyLabelSize)
        layoutManager.updateTheme(currentSettings.theme)

        layoutManager.updateHapticSettings(
            currentSettings.hapticFeedback,
            currentSettings.vibrationStrength.durationMs,
        )

        if (serviceJob.isCancelled) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

            observerJobs.forEach { it.cancel() }
            observerJobs.clear()

            observeViewModel()
        }

        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        isSecureField = SecureFieldDetector.isSecure(info)
        currentInputAction = ActionDetector.detectAction(info)

        val targetMode = KeyboardModeUtils.determineTargetMode(info, viewModel.state.value.currentMode)
        if (targetMode != viewModel.state.value.currentMode) {
            viewModel.onEvent(KeyboardEvent.ModeChanged(targetMode))
        }

        if (isSecureField) {
            clearSecureFieldState()
        } else {
            try {
                currentInputConnection?.finishComposingText()
            } catch (_: Exception) {
            }

            val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
            viewModel.checkAndApplyAutoCapitalization(textBefore)
        }

        updateKeyboardForCurrentAction()
    }

    /**
     * Updates keyboard action key based on editor info.
     */
    private fun updateKeyboardForCurrentAction() {
        viewModel.updateActionType(currentInputAction)
    }

    /**
     * Handles key press events.
     *
     * @param key Pressed key
     */
    private fun handleKeyPress(key: KeyboardKey) {
        try {
            when (key) {
                is KeyboardKey.Character -> {
                    val char = viewModel.getCharacterForInput(key)
                    viewModel.clearShiftAfterCharacter(key)

                    if (isLetterInput(char)) {
                        handleLetterInput(char)
                    } else {
                        handleNonLetterInput(char)
                    }

                    viewModel.onEvent(KeyboardEvent.KeyPressed(key))
                }

                is KeyboardKey.Action -> {
                    handleActionKey(key)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Handles letter character input.
     *
     * @param char Letter character to process
     */
    private fun handleLetterInput(char: String) {
        try {
            if (isSecureField) {
                currentInputConnection?.commitText(char, 1)
                return
            }

            if (displayBuffer.isNotEmpty()) {
                val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                clearSpellConfirmationState()
            }

            isActivelyEditing = true

            val cursorPosInWord =
                if (composingRegionStart != -1 && displayBuffer.isNotEmpty()) {
                    val absoluteCursorPos = currentInputConnection?.getTextBeforeCursor(1000, 0)?.length ?: displayBuffer.length
                    CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, composingRegionStart, displayBuffer.length)
                } else {
                    displayBuffer.length
                }

            val oldComposingStart = composingRegionStart

            displayBuffer =
                if (displayBuffer.isEmpty()) {
                    composingRegionStart = -1
                    char
                } else {
                    displayBuffer.substring(0, cursorPosInWord) + char + displayBuffer.substring(cursorPosInWord)
                }

            val newCursorPos = cursorPosInWord + 1

            currentInputConnection?.beginBatchEdit()
            try {
                currentInputConnection?.setComposingText(displayBuffer, 1)

                if (oldComposingStart != -1) {
                    val currentTextLength = currentInputConnection?.getTextBeforeCursor(1000, 0)?.length ?: displayBuffer.length
                    composingRegionStart = CursorEditingUtils.recalculateComposingRegionStart(currentTextLength, displayBuffer.length)

                    val newAbsoluteCursorPos = composingRegionStart + newCursorPos
                    currentInputConnection?.setSelection(newAbsoluteCursorPos, newAbsoluteCursorPos)
                }
            } finally {
                currentInputConnection?.endBatchEdit()
            }

            wordState =
                wordState.copy(
                    buffer = displayBuffer,
                    graphemeCount = displayBuffer.length,
                )

            val currentSequence = synchronized(processingLock) { ++processingSequence }
            val bufferSnapshot = displayBuffer

            suggestionDebounceJob?.cancel()
            suggestionDebounceJob =
                serviceScope.launch(Dispatchers.Default) {
                    try {
                        delay(suggestionDebounceDelay)

                        val result =
                            textInputProcessor.processCharacterInput(
                                char,
                                bufferSnapshot,
                                InputMethod.TYPED,
                            )

                        withContext(Dispatchers.Main) {
                            synchronized(processingLock) {
                                if (currentSequence == processingSequence && displayBuffer == bufferSnapshot) {
                                    when (result) {
                                        is ProcessingResult.Success -> {
                                            wordState = result.wordState

                                            if (result.wordState.suggestions.isNotEmpty() && currentSettings.showSuggestions) {
                                                val shouldCapitalize = displayBuffer.firstOrNull()?.isUpperCase() == true
                                                val displaySuggestions =
                                                    if (shouldCapitalize) {
                                                        result.wordState.suggestions.map { it.replaceFirstChar { c -> c.uppercase() } }
                                                    } else {
                                                        result.wordState.suggestions
                                                    }
                                                pendingSuggestions = displaySuggestions
                                                swipeKeyboardView?.updateSuggestions(displaySuggestions)
                                            } else {
                                                pendingSuggestions = emptyList()
                                                swipeKeyboardView?.clearSuggestions()
                                            }
                                        }
                                        is ProcessingResult.Error -> {}
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
        } catch (_: Exception) {
            currentInputConnection?.commitText(char, 1)
        }
    }

    /**
     * Handles non-letter character input.
     *
     * @param char Non-letter character to process
     */
    private fun handleNonLetterInput(char: String) {
        serviceScope.launch {
            try {
                if (isSecureField) {
                    currentInputConnection?.commitText(char, 1)
                    return@launch
                }

                if (displayBuffer.isNotEmpty()) {
                    val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                    val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        confirmAndLearnWord()
                        currentInputConnection?.commitText(char, 1)

                        if (char in setOf(".", "!", "?") && !isSecureField) {
                            val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                            viewModel.checkAndApplyAutoCapitalization(textBefore)
                        }
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }
                    return@launch
                }

                if (displayBuffer.isNotEmpty() && wordState.requiresSpellCheck) {
                    if (wordState.graphemeCount >= 2) {
                        val isValid = textInputProcessor.validateWord(wordState.normalizedBuffer)
                        if (!isValid) {
                            spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                            pendingWordForLearning = wordState.normalizedBuffer
                            highlightCurrentWord()

                            val suggestions = textInputProcessor.getSuggestions(wordState.normalizedBuffer)
                            pendingSuggestions = suggestions
                            if (suggestions.isNotEmpty()) {
                                swipeKeyboardView?.updateSuggestions(suggestions)
                            } else {
                                swipeKeyboardView?.clearSuggestions()
                            }
                            return@launch
                        }
                    }
                }

                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(char, 1)

                    if (char in setOf(".", "!", "?") && !isSecureField) {
                        val textBefore =
                            currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                        viewModel.checkAndApplyAutoCapitalization(textBefore)
                    }
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(char, 1)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }
        }
    }

    /**
     * Handles swiped word input.
     *
     * @param validatedWord Validated word from swipe gesture
     */
    private fun handleSwipeWord(validatedWord: String) {
        try {
            if (isSecureField) {
                currentInputConnection?.setComposingText(validatedWord, 1)
                return
            }

            if (displayBuffer.isNotEmpty()) {
                val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                clearSpellConfirmationState()
            }

            if (validatedWord.isEmpty()) return

            val shouldCapitalize =
                viewModel.state.value.isShiftPressed || viewModel.state.value.isCapsLockOn
            val displayWord =
                if (shouldCapitalize) {
                    validatedWord.replaceFirstChar { it.uppercase() }
                } else {
                    validatedWord
                }

            if (viewModel.state.value.isShiftPressed && !viewModel.state.value.isCapsLockOn) {
                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
            }

            serviceScope.launch {
                try {
                    val result =
                        textInputProcessor.processWordInput(validatedWord, InputMethod.SWIPED)

                    when (result) {
                        is ProcessingResult.Success -> {
                            withContext(Dispatchers.Main) {
                                currentInputConnection?.setComposingText(displayWord, 1)
                                displayBuffer = displayWord
                                coordinateStateTransition(result.wordState)

                                if (result.shouldHighlight) {
                                    spellConfirmationState =
                                        SpellConfirmationState.AWAITING_CONFIRMATION
                                    pendingWordForLearning = result.wordState.normalizedBuffer
                                    highlightCurrentWord()
                                }
                            }
                        }

                        is ProcessingResult.Error -> {
                            withContext(Dispatchers.Main) {
                                currentInputConnection?.setComposingText(displayWord, 1)
                                displayBuffer = displayWord
                                wordState =
                                    WordState(
                                        buffer = displayWord,
                                        normalizedBuffer = validatedWord.lowercase(),
                                        isFromSwipe = true,
                                        graphemeCount = displayWord.length,
                                        scriptCode = UScript.LATIN,
                                    )
                            }
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        currentInputConnection?.setComposingText(displayWord, 1)
                        displayBuffer = displayWord
                        wordState =
                            WordState(
                                buffer = displayWord,
                                normalizedBuffer = validatedWord.lowercase(),
                                isFromSwipe = true,
                                graphemeCount = displayWord.length,
                                scriptCode = UScript.LATIN,
                            )
                    }
                }
            }
        } catch (_: Exception) {
            currentInputConnection?.setComposingText(validatedWord, 1)
        }
    }

    /**
     * Handles suggestion selection.
     *
     * @param suggestion Selected suggestion
     */
    private fun handleSuggestionSelected(suggestion: String) {
        serviceScope.launch {
            if (isSecureField) {
                return@launch
            }

            coordinateSuggestionSelection(suggestion)
        }
    }

    /**
     * Handles suggestion removal via long press.
     *
     * @param suggestion Suggestion to remove
     */
    private fun handleSuggestionRemoval(suggestion: String) {
        serviceScope.launch {
            try {
                wordLearningEngine.removeWord(suggestion)

                spellCheckManager.blacklistSuggestion(suggestion)
                spellCheckManager.invalidateWordCache(suggestion)
                textInputProcessor.invalidateWord(suggestion)

                withContext(Dispatchers.Main) {
                    val currentSuggestions = pendingSuggestions.filter { it != suggestion }
                    pendingSuggestions = currentSuggestions
                    if (currentSuggestions.isNotEmpty()) {
                        swipeKeyboardView?.updateSuggestions(currentSuggestions)
                    } else {
                        swipeKeyboardView?.clearSuggestions()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handles action key presses.
     *
     * @param key Action key pressed
     */
    private fun handleActionKey(key: KeyboardKey.Action) {
        when (key.action) {
            KeyboardKey.ActionType.BACKSPACE -> {
                handleBackspace()
            }

            KeyboardKey.ActionType.SPACE -> {
                handleSpace()
            }

            KeyboardKey.ActionType.ENTER,
            KeyboardKey.ActionType.SEARCH,
            KeyboardKey.ActionType.SEND,
            KeyboardKey.ActionType.DONE,
            KeyboardKey.ActionType.GO,
            KeyboardKey.ActionType.NEXT,
            KeyboardKey.ActionType.PREVIOUS,
            -> {
                serviceScope.launch {
                    performInputAction(
                        when (key.action) {
                            KeyboardKey.ActionType.SEARCH -> EditorInfo.IME_ACTION_SEARCH
                            KeyboardKey.ActionType.SEND -> EditorInfo.IME_ACTION_SEND
                            KeyboardKey.ActionType.DONE -> EditorInfo.IME_ACTION_DONE
                            KeyboardKey.ActionType.GO -> EditorInfo.IME_ACTION_GO
                            KeyboardKey.ActionType.NEXT -> EditorInfo.IME_ACTION_NEXT
                            KeyboardKey.ActionType.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
                            else -> EditorInfo.IME_ACTION_NONE
                        },
                    )
                }
            }

            KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> {
                viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))
            }

            KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> {
                viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.NUMBERS))
            }

            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> {
                viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.SYMBOLS))
            }

            KeyboardKey.ActionType.SHIFT -> {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastShift = currentTime - lastShiftTime
                val currentState = viewModel.state.value

                when {
                    timeSinceLastShift <= doubleShiftThreshold -> {
                        when {
                            currentState.isShiftPressed && !currentState.isCapsLockOn -> {
                                viewModel.onEvent(KeyboardEvent.CapsLockToggled)
                                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                            }

                            else -> {
                                viewModel.onEvent(KeyboardEvent.CapsLockToggled)
                                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                            }
                        }
                        lastShiftTime = 0
                    }

                    else -> {
                        when {
                            currentState.isCapsLockOn -> {
                                viewModel.onEvent(KeyboardEvent.CapsLockToggled)
                            }

                            !currentState.isShiftPressed -> {
                                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
                            }

                            currentState.isShiftPressed -> {
                                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                            }
                        }
                        lastShiftTime = currentTime
                    }
                }
            }

            KeyboardKey.ActionType.CAPS_LOCK -> {
                viewModel.onEvent(KeyboardEvent.CapsLockToggled)
            }
        }
    }

    /**
     * Performs IME action.
     *
     * @param imeAction Action to perform
     */
    private suspend fun performInputAction(imeAction: Int) {
        try {
            if (!isSecureField && displayBuffer.isNotEmpty()) {
                val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                } else {
                    coordinateWordCompletion()
                }
            }

            isActivelyEditing = true

            when (imeAction) {
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_PREVIOUS,
                -> {
                    currentInputConnection?.performEditorAction(imeAction) ?: false
                }

                else -> {
                    currentInputConnection?.commitText("\n", 1)
                    true
                }
            }

            coordinateStateClear()
            clearSpellConfirmationState()
        } catch (_: Exception) {
            coordinateStateClear()
        }
    }

    /**
     * Handles backspace key press.
     */
    private fun handleBackspace() {
        try {
            if (isSecureField) {
                val textBefore = currentInputConnection?.getTextBeforeCursor(1, 0)
                if (!textBefore.isNullOrEmpty()) {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
                return
            }

            if (displayBuffer.isNotEmpty()) {
                val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                    return
                }
            }

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                spellConfirmationState = SpellConfirmationState.NORMAL
                pendingWordForLearning = null
            }

            isActivelyEditing = true

            if (displayBuffer.isNotEmpty()) {
                val cursorPosInWord =
                    if (composingRegionStart != -1) {
                        val absoluteCursorPos = currentInputConnection?.getTextBeforeCursor(1000, 0)?.length ?: displayBuffer.length
                        CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, composingRegionStart, displayBuffer.length)
                    } else {
                        displayBuffer.length
                    }

                if (cursorPosInWord > 0) {
                    val oldComposingStart = composingRegionStart

                    displayBuffer = displayBuffer.substring(0, cursorPosInWord - 1) +
                        displayBuffer.substring(cursorPosInWord)

                    if (displayBuffer.isNotEmpty()) {
                        val newCursorPos = cursorPosInWord - 1

                        currentInputConnection?.beginBatchEdit()
                        try {
                            currentInputConnection?.setComposingText(displayBuffer, 1)

                            if (oldComposingStart != -1) {
                                val currentTextLength = currentInputConnection?.getTextBeforeCursor(1000, 0)?.length ?: displayBuffer.length
                                composingRegionStart =
                                    CursorEditingUtils.recalculateComposingRegionStart(currentTextLength, displayBuffer.length)

                                val newAbsoluteCursorPos = composingRegionStart + newCursorPos
                                currentInputConnection?.setSelection(newAbsoluteCursorPos, newAbsoluteCursorPos)
                            }
                        } finally {
                            currentInputConnection?.endBatchEdit()
                        }

                        wordState =
                            wordState.copy(
                                buffer = displayBuffer,
                                normalizedBuffer = displayBuffer.lowercase(),
                                graphemeCount = displayBuffer.length,
                            )

                        if (!isAcceleratedDeletion) {
                            val currentSequence = synchronized(processingLock) { ++processingSequence }
                            val bufferSnapshot = displayBuffer

                            suggestionDebounceJob?.cancel()
                            suggestionDebounceJob =
                                serviceScope.launch(Dispatchers.Default) {
                                    try {
                                        delay(suggestionDebounceDelay)

                                        val result =
                                            textInputProcessor.processWordInput(
                                                bufferSnapshot,
                                                InputMethod.TYPED,
                                            )

                                        withContext(Dispatchers.Main) {
                                            synchronized(processingLock) {
                                                if (currentSequence == processingSequence && displayBuffer == bufferSnapshot) {
                                                    when (result) {
                                                        is ProcessingResult.Success -> {
                                                            wordState = result.wordState
                                                            if (result.wordState.suggestions.isNotEmpty() &&
                                                                currentSettings.showSuggestions
                                                            ) {
                                                                val shouldCapitalize = displayBuffer.firstOrNull()?.isUpperCase() == true
                                                                val displaySuggestions =
                                                                    if (shouldCapitalize) {
                                                                        result.wordState.suggestions.map {
                                                                            it.replaceFirstChar { c ->
                                                                                c.uppercase()
                                                                            }
                                                                        }
                                                                    } else {
                                                                        result.wordState.suggestions
                                                                    }
                                                                pendingSuggestions = displaySuggestions
                                                                swipeKeyboardView?.updateSuggestions(displaySuggestions)
                                                            } else {
                                                                pendingSuggestions = emptyList()
                                                                swipeKeyboardView?.clearSuggestions()
                                                            }
                                                        }
                                                        is ProcessingResult.Error -> {
                                                            pendingSuggestions = emptyList()
                                                            swipeKeyboardView?.clearSuggestions()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                        }
                    } else {
                        currentInputConnection?.beginBatchEdit()
                        try {
                            currentInputConnection?.setComposingText("", 1)
                            coordinateStateClear()
                            composingRegionStart = -1
                        } finally {
                            currentInputConnection?.endBatchEdit()
                        }
                    }
                }
            } else {
                handleCommittedTextBackspace()
            }
        } catch (_: Exception) {
            try {
                val textBefore = currentInputConnection?.getTextBeforeCursor(1, 0)
                if (!textBefore.isNullOrEmpty()) {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
            } catch (_: Exception) {
            }
            coordinateStateClear()
        }
    }

    /**
     * Handles backspace on committed text by re-composing the word.
     */
    private fun handleCommittedTextBackspace() {
        try {
            val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()

            if (!textBeforeCursor.isNullOrEmpty()) {
                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.deleteSurroundingText(1, 0)

                    val remainingText = textBeforeCursor.dropLast(1)
                    val wordInfo = extractWordBeforeCursor(remainingText)

                    if (wordInfo != null) {
                        val (wordBeforeCursor, _) = wordInfo

                        currentInputConnection?.deleteSurroundingText(wordBeforeCursor.length, 0)
                        currentInputConnection?.setComposingText(wordBeforeCursor, 1)

                        displayBuffer = wordBeforeCursor
                        wordState =
                            wordState.copy(
                                buffer = wordBeforeCursor,
                                normalizedBuffer = wordBeforeCursor.lowercase(),
                                graphemeCount = wordBeforeCursor.length,
                            )

                        val currentSequence = synchronized(processingLock) { ++processingSequence }
                        val bufferSnapshot = displayBuffer

                        suggestionDebounceJob?.cancel()
                        suggestionDebounceJob =
                            serviceScope.launch(Dispatchers.Default) {
                                try {
                                    delay(suggestionDebounceDelay)

                                    val result =
                                        textInputProcessor.processWordInput(
                                            bufferSnapshot,
                                            InputMethod.TYPED,
                                        )

                                    withContext(Dispatchers.Main) {
                                        synchronized(processingLock) {
                                            if (currentSequence == processingSequence && displayBuffer == bufferSnapshot) {
                                                when (result) {
                                                    is ProcessingResult.Success -> {
                                                        wordState = result.wordState
                                                        if (result.wordState.suggestions.isNotEmpty() && currentSettings.showSuggestions) {
                                                            val shouldCapitalize =
                                                                displayBuffer
                                                                    .firstOrNull()
                                                                    ?.isUpperCase() == true
                                                            val displaySuggestions =
                                                                if (shouldCapitalize) {
                                                                    result.wordState.suggestions.map {
                                                                        it.replaceFirstChar { c ->
                                                                            c.uppercase()
                                                                        }
                                                                    }
                                                                } else {
                                                                    result.wordState.suggestions
                                                                }
                                                            pendingSuggestions = displaySuggestions
                                                            swipeKeyboardView?.updateSuggestions(
                                                                displaySuggestions,
                                                            )
                                                        } else {
                                                            pendingSuggestions = emptyList()
                                                            swipeKeyboardView?.clearSuggestions()
                                                        }
                                                    }

                                                    is ProcessingResult.Error -> {
                                                        pendingSuggestions = emptyList()
                                                        swipeKeyboardView?.clearSuggestions()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                    } else {
                        coordinateStateClear()
                    }
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }
        } catch (_: Exception) {
            coordinateStateClear()
        }
    }

    /**
     * Extracts word before cursor using boundary detection.
     *
     * @param textBeforeCursor Text before cursor position
     * @return Pair of (word, boundary index) or null if no valid word found
     */
    private fun extractWordBeforeCursor(textBeforeCursor: String): Pair<String, Int>? =
        BackspaceUtils.extractWordBeforeCursor(textBeforeCursor)

    /**
     * Handles word-mode backspace deletion.
     *
     * Deletes whole words without re-composition.
     * If composing text exists, deletes char-by-char until empty.
     */
    private fun handleBackspaceWord() {
        try {
            if (isSecureField) {
                handleBackspace()
                return
            }

            if (displayBuffer.isNotEmpty()) {
                val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                    return
                }
            }

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                spellConfirmationState = SpellConfirmationState.NORMAL
                pendingWordForLearning = null
            }

            if (displayBuffer.isNotEmpty()) {
                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.setComposingText("", 1)
                    coordinateStateClear()
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
                return
            }

            val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()

            if (!textBeforeCursor.isNullOrEmpty()) {
                val wordInfo = BackspaceUtils.extractWordBeforeCursor(textBeforeCursor)

                if (wordInfo != null) {
                    val (word, _) = wordInfo
                    val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(textBeforeCursor, word.length)
                    val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)

                    currentInputConnection?.deleteSurroundingText(deleteLength, 0)
                } else {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
            }
        } catch (_: Exception) {
            try {
                currentInputConnection?.deleteSurroundingText(1, 0)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handles space key press.
     */
    private fun handleSpace() {
        serviceScope.launch {
            try {
                if (isSecureField) {
                    currentInputConnection?.commitText(" ", 1)
                    return@launch
                }

                if (displayBuffer.isNotEmpty()) {
                    val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                    val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                val currentTime = System.currentTimeMillis()
                val timeSinceLastSpace = currentTime - lastSpaceTime

                if (timeSinceLastSpace <= doubleTapSpaceThreshold &&
                    spellConfirmationState == SpellConfirmationState.NORMAL &&
                    currentSettings.doubleSpacePeriod
                ) {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        if (wordState.hasContent && !isSecureField) {
                            currentInputConnection?.finishComposingText()
                            coordinateStateClear()
                        }
                        currentInputConnection?.deleteSurroundingText(1, 0)
                        currentInputConnection?.commitText(". ", 1)

                        val textBefore =
                            currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                        viewModel.checkAndApplyAutoCapitalization(textBefore)
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }

                    lastSpaceTime = 0
                    return@launch
                }

                lastSpaceTime = currentTime

                if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    confirmAndLearnWord()
                    return@launch
                }

                if (wordState.hasContent && wordState.requiresSpellCheck) {
                    if (wordState.graphemeCount >= 2) {
                        val isValid = textInputProcessor.validateWord(wordState.normalizedBuffer)
                        if (isValid) {
                            currentInputConnection?.beginBatchEdit()
                            try {
                                currentInputConnection?.finishComposingText()
                                coordinateStateClear()
                                currentInputConnection?.commitText(" ", 1)

                                val textBefore =
                                    currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                                viewModel.checkAndApplyAutoCapitalization(textBefore)
                            } finally {
                                currentInputConnection?.endBatchEdit()
                            }

                            return@launch
                        }

                        val suggestions =
                            textInputProcessor.getSuggestions(wordState.normalizedBuffer)

                        spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                        pendingWordForLearning = wordState.normalizedBuffer

                        highlightCurrentWord()
                        pendingSuggestions = suggestions
                        if (suggestions.isNotEmpty()) {
                            swipeKeyboardView?.updateSuggestions(suggestions)
                        } else {
                            swipeKeyboardView?.clearSuggestions()
                        }

                        return@launch
                    }
                }

                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(" ", 1)

                    val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(" ", 1)

                    val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)

        swipeKeyboardView?.hideEmojiPicker()

        if (displayBuffer.isNotEmpty() && !isSecureField) {
            serviceScope.launch {
                val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                } else {
                    coordinateWordCompletion()
                }
            }
        }

        serviceScope.launch {
            try {
                currentInputConnection?.finishComposingText()
                coordinateStateClear()
            } catch (_: Exception) {
                coordinateStateClear()
            }
        }

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)

        coordinateStateClear()

        lastSpaceTime = 0
        lastShiftTime = 0

        isSecureField = SecureFieldDetector.isSecure(attribute)
        currentInputAction = ActionDetector.detectAction(attribute)

        if (isSecureField) {
            clearSecureFieldState()
        } else {
            val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
            viewModel.checkAndApplyAutoCapitalization(textBefore)
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()

        swipeKeyboardView?.hideEmojiPicker()

        serviceScope.launch {
            if (displayBuffer.isNotEmpty() && !isSecureField) {
                val actualTextBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val actualTextAfter = currentInputConnection?.getTextAfterCursor(1, 0)?.toString() ?: ""

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                } else {
                    coordinateWordCompletion()
                }
            }

            try {
                currentInputConnection?.finishComposingText()
            } catch (_: Exception) {
            }

            coordinateStateClear()
            clearSpellConfirmationState()
        }

        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )

        if (isSecureField) return

        if (newSelStart == 0 && newSelEnd == 0) {
            val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
            val textAfter = currentInputConnection?.getTextAfterCursor(50, 0)?.toString()

            if (CursorEditingUtils.shouldClearStateOnEmptyField(
                    newSelStart,
                    newSelEnd,
                    textBefore,
                    textAfter,
                    displayBuffer,
                    wordState.hasContent,
                )
            ) {
                coordinateStateClear()
                clearSpellConfirmationState()
            }

            viewModel.checkAndApplyAutoCapitalization(textBefore)
        }

        if (isActivelyEditing) {
            isActivelyEditing = false
            return
        }

        val hasComposingText = (candidatesStart != -1 && candidatesEnd != -1)
        val cursorInComposingRegion =
            hasComposingText &&
                newSelStart >= candidatesStart &&
                newSelStart <= candidatesEnd &&
                newSelEnd >= candidatesStart &&
                newSelEnd <= candidatesEnd
        val hasSelection = (newSelStart != newSelEnd)

        if (hasComposingText && !cursorInComposingRegion && !hasSelection) {
            if (isRecomposing) return

            isActivelyEditing = true
            coordinateStateClear()
            clearSpellConfirmationState()

            val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
            val textAfterCursor = currentInputConnection?.getTextAfterCursor(50, 0)?.toString()

            if (!textBeforeCursor.isNullOrEmpty() || !textAfterCursor.isNullOrEmpty()) {
                val wordAtCursor = extractWordAtCursor(textBeforeCursor ?: "", textAfterCursor ?: "")

                if (wordAtCursor != null) {
                    isRecomposing = true
                    serviceScope.launch {
                        try {
                            recomposeWordAtCursor(wordAtCursor, textBeforeCursor ?: "")
                        } finally {
                            isRecomposing = false
                        }
                    }
                }
            }
            return
        }

        if (!hasComposingText && !hasSelection) {
            if (isRecomposing) return

            val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
            val textAfterCursor = currentInputConnection?.getTextAfterCursor(50, 0)?.toString()

            if (!textBeforeCursor.isNullOrEmpty() || !textAfterCursor.isNullOrEmpty()) {
                val wordAtCursor = extractWordAtCursor(textBeforeCursor ?: "", textAfterCursor ?: "")

                if (wordAtCursor != null) {
                    isRecomposing = true
                    serviceScope.launch {
                        try {
                            recomposeWordAtCursor(wordAtCursor, textBeforeCursor ?: "")
                        } finally {
                            isRecomposing = false
                        }
                    }
                }
            }
            return
        }

        if (!hasComposingText && wordState.hasContent) {
            coordinateStateClear()
            clearSpellConfirmationState()
        }
    }

    private fun extractWordAtCursor(
        textBefore: String,
        textAfter: String,
    ): String? = CursorEditingUtils.extractWordAtCursor(textBefore, textAfter)

    private suspend fun recomposeWordAtCursor(
        word: String,
        textBeforeCursor: String,
    ) {
        withContext(Dispatchers.Main) {
            isActivelyEditing = true

            try {
                val beforeLastBoundary =
                    textBeforeCursor.indexOfLast { char ->
                        char.isWhitespace() || char in ".,!?;:\n"
                    }

                val wordStartOffset =
                    if (beforeLastBoundary >= 0) {
                        textBeforeCursor.length - beforeLastBoundary - 1
                    } else {
                        textBeforeCursor.length
                    }

                val cursorPos = currentInputConnection?.getTextBeforeCursor(1000, 0)?.length ?: 0
                val wordStart = cursorPos - wordStartOffset
                val wordEnd = wordStart + word.length

                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.setComposingRegion(wordStart, wordEnd)
                    composingRegionStart = wordStart

                    displayBuffer = word
                    wordState =
                        wordState.copy(
                            buffer = word,
                            normalizedBuffer = word.lowercase(),
                            graphemeCount = word.length,
                        )

                    val currentSequence = synchronized(processingLock) { ++processingSequence }

                    suggestionDebounceJob?.cancel()
                    suggestionDebounceJob =
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                delay(suggestionDebounceDelay)

                                val result =
                                    textInputProcessor.processWordInput(
                                        word,
                                        InputMethod.TYPED,
                                    )

                                withContext(Dispatchers.Main) {
                                    synchronized(processingLock) {
                                        if (currentSequence == processingSequence && displayBuffer == word) {
                                            when (result) {
                                                is ProcessingResult.Success -> {
                                                    wordState = result.wordState
                                                    if (result.wordState.suggestions.isNotEmpty() && currentSettings.showSuggestions) {
                                                        swipeKeyboardView?.updateSuggestions(result.wordState.suggestions)
                                                    } else {
                                                        swipeKeyboardView?.clearSuggestions()
                                                    }
                                                }
                                                is ProcessingResult.Error -> {
                                                    swipeKeyboardView?.clearSuggestions()
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        coordinateStateClear()
        swipeKeyboardView = null

        try {
            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()
        } catch (_: Exception) {
        }

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
