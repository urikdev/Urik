package com.urik.keyboard

import android.content.ClipboardManager
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
import com.urik.keyboard.model.ActionButton
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.SuggestionActionType
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
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ScriptDetector
import com.urik.keyboard.utils.SecureFieldDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    @Volatile
    private var displayBuffer = ""
    private var processingSequence = 0L
    private val processingLock = Any()

    @Volatile
    private var wordState = WordState()

    private var currentScriptCode = UScript.LATIN

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
    private fun isValidTextInput(text: String): Boolean {
        if (text.isBlank()) return false

        return text.any { char ->
            Character.isLetter(char.code) ||
                Character.isIdeographic(char.code) ||
                Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                char == '\'' ||
                char == '\u2019' ||
                char == '-'
        }
    }

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
        currentScriptCode = ScriptDetector.getScriptFromLocale(locale)

        layoutManager.updateScriptContext(locale)
        swipeDetector.updateScriptContext(locale)
        textInputProcessor.updateScriptContext(locale, currentScriptCode)
        spellCheckManager.clearCaches()
    }

    /**
     * Clears spell confirmation state and optionally commits current word.
     *
     * @param commitWord Whether to commit word or restore composing text
     */
    private fun clearSpellConfirmationState(commitWord: Boolean = false) {
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null

        if (commitWord) {
            unhighlightCurrentWord()
        } else {
            try {
                if (displayBuffer.isNotEmpty()) {
                    currentInputConnection?.setComposingText(displayBuffer, 1)
                } else {
                    currentInputConnection?.finishComposingText()
                }
            } catch (_: Exception) {
            }
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
     * Removes highlighting from current word.
     */
    private fun unhighlightCurrentWord() {
        try {
            currentInputConnection?.finishComposingText()
        } catch (_: Exception) {
        }
    }

    /**
     * Learns word and invalidates relevant caches.
     *
     * @param word Word to learn
     * @param inputMethod Method used to input word
     * @return True if learning succeeded or is disabled
     */
    private suspend fun learnWordAndInvalidateCache(
        word: String,
        inputMethod: InputMethod,
    ): Boolean {
        return try {
            val settings = textInputProcessor.getCurrentSettings()
            if (!settings.isWordLearningEnabled) {
                return true
            }

            val learnResult = wordLearningEngine.learnWord(word, inputMethod)
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

                currentInputConnection?.beginBatchEdit()
                try {
                    if (wordToLearn != null) {
                        learnWordAndInvalidateCache(
                            wordToLearn,
                            InputMethod.SELECTED_FROM_SUGGESTION,
                        )

                        currentInputConnection?.finishComposingText()
                        currentInputConnection?.commitText(" ", 1)
                    } else {
                        coordinateWordCompletion(InputMethod.TYPED)
                        currentInputConnection?.commitText(" ", 1)
                    }

                    coordinateStateClear()
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion(InputMethod.TYPED)
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

        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        clearSpellConfirmationState()
        swipeKeyboardView?.clearSuggestions()

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

                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.commitText("$suggestion ", 1)
                    learnWordAndInvalidateCache(suggestion, InputMethod.SELECTED_FROM_SUGGESTION)

                    displayBuffer = ""
                    wordState = WordState()
                    pendingSuggestions = emptyList()
                    spellConfirmationState = SpellConfirmationState.NORMAL
                    pendingWordForLearning = null
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
     * @param inputMethod Method used to input word
     */
    private suspend fun coordinateWordCompletion(inputMethod: InputMethod) {
        withContext(Dispatchers.Main) {
            try {
                val wordToCommit = displayBuffer.ifEmpty { wordState.buffer }

                if (wordToCommit.isNotEmpty()) {
                    currentInputConnection?.finishComposingText()

                    val wordToLearn =
                        wordState.normalizedBuffer.ifEmpty {
                            wordToCommit.lowercase()
                        }
                    learnWordAndInvalidateCache(wordToLearn, inputMethod)
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
            if (currentLanguage != null) {
                try {
                    wordLearningEngine.initializeLearnedWordsCache(currentLanguage.languageTag)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context =
                            mapOf(
                                "phase" to "word_learning_init",
                                "language" to currentLanguage.languageTag,
                            ),
                    )
                }

                val locale = ULocale.forLanguageTag(currentLanguage.languageTag)
                updateScriptContext(locale)
            }

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
                    initialize(layoutManager, swipeDetector, spellCheckManager)
                    setOnKeyClickListener { key -> handleKeyPress(key) }
                    setOnSwipeWordListener { validatedWord -> handleSwipeWord(validatedWord) }
                    setOnSuggestionClickListener { suggestion -> handleSuggestionSelected(suggestion) }
                    setOnActionButtonClickListener { action -> handleActionButtonClick(action) }
                    setOnSuggestionLongPressListener { suggestion ->
                        handleSuggestionRemoval(
                            suggestion,
                        )
                    }
                }

            swipeKeyboardView = swipeView
            updateSwipeKeyboard()
            observeViewModel()
            configureActionButtons()

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
     * Configures available action buttons for suggestion bar.
     */
    private fun configureActionButtons() {
        val defaultActions =
            listOf(
                ActionButton(
                    id = "paste",
                    iconRes = R.drawable.ic_paste,
                    contentDescriptionRes = R.string.action_paste,
                    action = SuggestionActionType.PASTE,
                    isVisible = { true },
                    isEnabled = { hasClipboardContent() },
                    requiresClipboard = true,
                ),
                ActionButton(
                    id = "emoji",
                    iconRes = R.drawable.ic_emoji,
                    contentDescriptionRes = R.string.action_emoji,
                    action = SuggestionActionType.EMOJI,
                    isVisible = { true },
                    isEnabled = { true },
                ),
            )

        swipeKeyboardView?.setAvailableActionButtons(defaultActions)
    }

    /**
     * Handles action button clicks.
     *
     * @param action Action type clicked
     */
    private fun handleActionButtonClick(action: SuggestionActionType) {
        when (action) {
            SuggestionActionType.PASTE -> {
                handlePasteAction()
            }

            SuggestionActionType.EMOJI -> {
                handleEmojiAction()
            }

            else -> {
            }
        }
    }

    /**
     * Handles paste action from clipboard.
     */
    private fun handlePasteAction() {
        serviceScope.launch {
            try {
                val clipboardManager =
                    getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return@launch

                val clipData = clipboardManager.primaryClip
                if (clipData == null || clipData.itemCount == 0) return@launch

                val clipText = clipData.getItemAt(0)?.text?.toString()

                if (clipText != null && clipText.isNotEmpty()) {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        if (!isSecureField && displayBuffer.isNotEmpty()) {
                            coordinateWordCompletion(InputMethod.TYPED)
                        }

                        currentInputConnection?.commitText(clipText, 1)

                        if (!isSecureField && isValidTextInput(clipText)) {
                            learnWordAndInvalidateCache(clipText, InputMethod.TYPED)
                        }
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }

                    swipeKeyboardView?.hideActionButtons()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Toggles emoji picker visibility.
     */
    private fun handleEmojiAction() {
        swipeKeyboardView?.let { keyboardView ->
            if (keyboardView.isEmojiPickerShowing()) {
                keyboardView.hideEmojiPicker()
            } else {
                keyboardView.showEmojiPicker { selectedEmoji ->
                    handleEmojiSelected(selectedEmoji)
                }
            }
        }
    }

    /**
     * Handles emoji selection from picker.
     *
     * @param emoji Selected emoji string
     */
    private fun handleEmojiSelected(emoji: String) {
        serviceScope.launch {
            try {
                if (!isSecureField && displayBuffer.isNotEmpty()) {
                    coordinateWordCompletion(InputMethod.TYPED)
                }

                currentInputConnection?.commitText(emoji, 1)
                swipeKeyboardView?.hideActionButtons()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Checks if clipboard has content available.
     *
     * @return True if clipboard contains text
     */
    private fun hasClipboardContent(): Boolean {
        return try {
            val clipboardManager =
                getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return false

            val clipData = clipboardManager.primaryClip
            if (clipData == null || clipData.itemCount == 0) return false

            val text = clipData.getItemAt(0)?.text
            text != null && text.isNotEmpty()
        } catch (_: Exception) {
            false
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

                    layoutManager.updateRepeatKeyDelay(newSettings.repeatKeyDelay)

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
                    if (detectedLanguage != null) {
                        val locale = ULocale.forLanguageTag(detectedLanguage.languageTag)
                        updateScriptContext(locale)
                    }
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
        layoutManager.updateRepeatKeyDelay(currentSettings.repeatKeyDelay)
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

        val isNumberInput =
            info?.inputType?.let { inputType ->
                val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
                inputClass == android.text.InputType.TYPE_CLASS_NUMBER
            } ?: false

        if (isNumberInput) {
            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.NUMBERS))
        } else if (viewModel.state.value.currentMode == KeyboardMode.NUMBERS) {
            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))
        }

        if (isSecureField) {
            clearSecureFieldState()
        } else {
            serviceScope.launch {
                try {
                    currentInputConnection?.finishComposingText()
                } catch (_: Exception) {
                }

                val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                viewModel.checkAndApplyAutoCapitalization(textBefore)
            }
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

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                clearSpellConfirmationState(commitWord = false)
            }

            displayBuffer += char
            currentInputConnection?.setComposingText(displayBuffer, 1)

            wordState =
                wordState.copy(
                    buffer = displayBuffer,
                    graphemeCount = displayBuffer.length,
                )

            val currentSequence = synchronized(processingLock) { ++processingSequence }
            val bufferSnapshot = displayBuffer

            serviceScope.launch(Dispatchers.Default) {
                try {
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
                                            val shouldCapitalize =
                                                displayBuffer.firstOrNull()?.isUpperCase() == true
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

                                    is ProcessingResult.Error -> {
                                    }
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

                if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    clearSpellConfirmationState(commitWord = false)
                }

                if (displayBuffer.isNotEmpty() && wordState.requiresSpellCheck) {
                    val config = textInputProcessor.getCurrentConfig()
                    if (wordState.graphemeCount >= config.minWordLengthForSpellCheck) {
                        val isValid = textInputProcessor.validateWord(wordState.normalizedBuffer)
                        if (!isValid) {
                            currentInputConnection?.beginBatchEdit()
                            try {
                                learnWordAndInvalidateCache(
                                    wordState.normalizedBuffer,
                                    InputMethod.TYPED,
                                )
                                coordinateWordCompletion(InputMethod.TYPED)
                                currentInputConnection?.commitText(char, 1)

                                if (char in setOf(".", "!", "?") && !isSecureField) {
                                    val textBefore =
                                        currentInputConnection
                                            ?.getTextBeforeCursor(50, 0)
                                            ?.toString()
                                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                                }
                            } finally {
                                currentInputConnection?.endBatchEdit()
                            }
                            return@launch
                        }
                    }
                }

                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion(InputMethod.TYPED)
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
                    coordinateWordCompletion(InputMethod.TYPED)
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

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                clearSpellConfirmationState(commitWord = false)
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
                                        scriptCode = currentScriptCode,
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
                                scriptCode = currentScriptCode,
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
                coordinateWordCompletion(InputMethod.TYPED)
            }

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
        } catch (_: Exception) {
            coordinateStateClear()
        }

        if (imeAction == EditorInfo.IME_ACTION_NONE && !isSecureField) {
            val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
            viewModel.checkAndApplyAutoCapitalization(textBefore)
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

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                spellConfirmationState = SpellConfirmationState.NORMAL
                pendingWordForLearning = null
            }

            if (displayBuffer.isNotEmpty()) {
                displayBuffer = displayBuffer.dropLast(1)

                if (displayBuffer.isNotEmpty()) {
                    currentInputConnection?.setComposingText(displayBuffer, 1)
                    wordState =
                        wordState.copy(
                            buffer = displayBuffer,
                            normalizedBuffer = displayBuffer.lowercase(),
                            graphemeCount = displayBuffer.length,
                        )

                    val currentSequence = synchronized(processingLock) { ++processingSequence }
                    val bufferSnapshot = displayBuffer

                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            val result =
                                textInputProcessor.processCharacterInput(
                                    "",
                                    bufferSnapshot,
                                    InputMethod.TYPED,
                                )

                            withContext(Dispatchers.Main) {
                                synchronized(processingLock) {
                                    if (currentSequence == processingSequence && displayBuffer == bufferSnapshot) {
                                        when (result) {
                                            is ProcessingResult.Success -> {
                                                wordState = result.wordState
                                                if (result.wordState.suggestions.isNotEmpty()) {
                                                    swipeKeyboardView?.updateSuggestions(result.wordState.suggestions)
                                                } else {
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
                } else {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        currentInputConnection?.setComposingText("", 1)
                        coordinateStateClear()
                    } finally {
                        currentInputConnection?.endBatchEdit()
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

                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                val result =
                                    textInputProcessor.processCharacterInput(
                                        "",
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
    private fun extractWordBeforeCursor(textBeforeCursor: String): Pair<String, Int>? {
        if (textBeforeCursor.isEmpty()) return null

        val lastWordBoundary =
            textBeforeCursor.indexOfLast { char ->
                char.isWhitespace() || char in ".,!?;:\n"
            }

        val wordBeforeCursor =
            if (lastWordBoundary >= 0) {
                textBeforeCursor.substring(lastWordBoundary + 1)
            } else {
                textBeforeCursor
            }

        if (wordBeforeCursor.isEmpty() || !isValidTextInput(wordBeforeCursor)) {
            return null
        }

        return Pair(wordBeforeCursor, lastWordBoundary)
    }

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

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                spellConfirmationState = SpellConfirmationState.NORMAL
                pendingWordForLearning = null
            }

            if (displayBuffer.isNotEmpty()) {
                displayBuffer = displayBuffer.dropLast(1)

                if (displayBuffer.isNotEmpty()) {
                    currentInputConnection?.setComposingText(displayBuffer, 1)
                    wordState =
                        wordState.copy(
                            buffer = displayBuffer,
                            normalizedBuffer = displayBuffer.lowercase(),
                            graphemeCount = displayBuffer.length,
                        )
                } else {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        currentInputConnection?.setComposingText("", 1)
                        coordinateStateClear()
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }
                }
                return
            }

            val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()

            if (!textBeforeCursor.isNullOrEmpty()) {
                val wordInfo = extractWordBeforeCursor(textBeforeCursor)

                if (wordInfo != null) {
                    val (word, _) = wordInfo
                    val deleteLength = word.length

                    val charBeforeWord =
                        if (textBeforeCursor.length > deleteLength) {
                            textBeforeCursor[textBeforeCursor.length - deleteLength - 1]
                        } else {
                            null
                        }

                    val totalDeleteLength =
                        if (charBeforeWord?.isWhitespace() == true) {
                            deleteLength + 1
                        } else {
                            deleteLength
                        }

                    currentInputConnection?.deleteSurroundingText(totalDeleteLength, 0)
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

                            val wordToLearn =
                                wordState.normalizedBuffer.ifEmpty {
                                    wordState.buffer.lowercase()
                                }
                            learnWordAndInvalidateCache(wordToLearn, InputMethod.TYPED)
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

                if (isSecureField) {
                    currentInputConnection?.commitText(" ", 1)
                    return@launch
                }

                if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    confirmAndLearnWord()
                    return@launch
                }

                if (wordState.hasContent && wordState.requiresSpellCheck) {
                    val config = textInputProcessor.getCurrentConfig()
                    if (wordState.graphemeCount >= config.minWordLengthForSpellCheck) {
                        val isValid = textInputProcessor.validateWord(wordState.normalizedBuffer)
                        if (isValid) {
                            currentInputConnection?.beginBatchEdit()
                            try {
                                coordinateWordCompletion(InputMethod.TYPED)
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
                    coordinateWordCompletion(InputMethod.TYPED)
                    currentInputConnection?.commitText(" ", 1)

                    val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.beginBatchEdit()
                try {
                    coordinateWordCompletion(InputMethod.TYPED)
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

        if (displayBuffer.isNotEmpty() && !isSecureField) {
            serviceScope.launch {
                coordinateWordCompletion(InputMethod.TYPED)
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
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()

        serviceScope.launch {
            if (displayBuffer.isNotEmpty() && !isSecureField) {
                coordinateWordCompletion(InputMethod.TYPED)
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

        if (displayBuffer.isNotEmpty()) {
            if (newSelStart < oldSelStart && candidatesStart == -1 && candidatesEnd == -1) {
                coordinateStateClear()
                clearSpellConfirmationState()
            }
            return
        }

        val noComposingText = (candidatesStart == -1 && candidatesEnd == -1)
        val hasStaleWordState = wordState.hasContent

        if (noComposingText && hasStaleWordState) {
            coordinateStateClear()
            clearSpellConfirmationState()
        }

        if (newSelStart == 0 && newSelEnd == 0 && !isSecureField) {
            serviceScope.launch {
                val textBefore = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString()
                withContext(Dispatchers.Main) {
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                }
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
