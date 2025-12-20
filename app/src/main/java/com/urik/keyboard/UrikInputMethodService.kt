package com.urik.keyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.KeyboardConstants.InputTimingConstants
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
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import com.urik.keyboard.ui.keyboard.components.ClipboardPanel
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
import com.urik.keyboard.utils.UrlEmailDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    @Inject
    lateinit var themeManager: ThemeManager

    @Inject
    lateinit var clipboardRepository: com.urik.keyboard.data.ClipboardRepository

    private lateinit var viewModel: KeyboardViewModel
    private lateinit var layoutManager: KeyboardLayoutManager
    private lateinit var lifecycleRegistry: LifecycleRegistry

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val observerJobs = mutableListOf<Job>()

    private var swipeKeyboardView: SwipeKeyboardView? = null
    private var keyboardRootContainer: LinearLayout? = null
    private var lastDisplayDensity: Float = 0f
    private var lastKeyboardConfig: Int = android.content.res.Configuration.KEYBOARD_UNDEFINED

    private var lastSpaceTime: Long = 0
    private var doubleTapSpaceThreshold: Long = InputTimingConstants.DOUBLE_TAP_SPACE_THRESHOLD_MS

    private var lastShiftTime: Long = 0
    private var doubleShiftThreshold: Long = InputTimingConstants.DOUBLE_SHIFT_THRESHOLD_MS

    private var suggestionDebounceJob: Job? = null
    private val suggestionDebounceDelay = InputTimingConstants.SUGGESTION_DEBOUNCE_MS

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
    private var pendingSuggestions: List<String> = emptyList()

    @Volatile
    private var spellConfirmationState = SpellConfirmationState.NORMAL

    @Volatile
    private var pendingWordForLearning: String? = null

    @Volatile
    private var isSecureField: Boolean = false

    @Volatile
    private var currentInputAction: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER

    private fun safeGetTextBeforeCursor(
        length: Int,
        flags: Int = 0,
    ): String =
        try {
            currentInputConnection
                ?.getTextBeforeCursor(length, flags)
                ?.toString()
                ?.take(length)
                ?: ""
        } catch (_: Exception) {
            ""
        }

    private fun safeGetTextAfterCursor(
        length: Int,
        flags: Int = 0,
    ): String =
        try {
            currentInputConnection
                ?.getTextAfterCursor(length, flags)
                ?.toString()
                ?.take(length)
                ?: ""
        } catch (_: Exception) {
            ""
        }

    private var currentSettings: KeyboardSettings = KeyboardSettings()

    @Volatile
    private var isAcceleratedDeletion = false

    @Volatile
    private var isUrlOrEmailField: Boolean = false

    fun setAcceleratedDeletion(active: Boolean) {
        isAcceleratedDeletion = active
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /**
     * Clears all state for secure text fields.
     */
    private fun clearSecureFieldState() {
        suggestionDebounceJob?.cancel()
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
        spellCheckManager.clearBlacklist()
        textInputProcessor.clearCaches()
        wordLearningEngine.clearCurrentLanguageCache()

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
     * Checks if character input represents a letter or number
     *
     * @param char Character string to check
     * @return True if character is valid
     */
    private fun isAlphaNumericInput(char: String): Boolean {
        if (char.isEmpty()) return false

        if (displayBuffer.isNotEmpty() && char.all { it.isDigit() }) {
            return true
        }

        return isValidTextInput(char)
    }

    /**
     * Updates script context for all relevant components.
     *
     * @param locale Locale to derive script from
     */
    private fun updateScriptContext(locale: ULocale) {
        val currentLayout = viewModel.layout.value
        val isRTL = currentLayout?.isRTL ?: false
        val scriptCode =
            currentLayout?.script?.let { scriptStr ->
                when (scriptStr) {
                    "Arab" -> UScript.ARABIC
                    "Cyrl" -> UScript.CYRILLIC
                    "Latn" -> UScript.LATIN
                    else -> UScript.LATIN
                }
            } ?: UScript.LATIN

        layoutManager.updateScriptContext()
        swipeDetector.updateScriptContext(locale, isRTL, scriptCode)
        textInputProcessor.updateScriptContext(locale, scriptCode)
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
     * Auto-capitalizes "I" for English.
     *
     * Converts lowercase "i" and common contractions (i'm, i'll, i've, i'd)
     * to properly capitalized forms before word completion.
     */
    private fun autoCapitalizePronounI() {
        try {
            val currentLanguage =
                languageManager.currentLanguage.value
                    .split("-")
                    .first()
            if (currentLanguage != "en") {
                return
            }

            if (displayBuffer.isEmpty()) {
                return
            }

            val lowercaseBuffer = displayBuffer.lowercase()
            val capitalizedVersion =
                when (lowercaseBuffer) {
                    "i" -> "I"
                    "i'm" -> "I'm"
                    "i'll" -> "I'll"
                    "i've" -> "I've"
                    "i'd" -> "I'd"
                    else -> null
                }

            if (capitalizedVersion != null && capitalizedVersion != displayBuffer) {
                displayBuffer = capitalizedVersion
                currentInputConnection?.setComposingText(capitalizedVersion, 1)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Learns word and invalidates relevant caches.
     *
     * @param word Word to learn
     * @param inputMethod Source of the word (TYPED, SWIPED, SELECTED_FROM_SUGGESTION)
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

            val isInDictionary = spellCheckManager.isWordInSymSpellDictionary(word)
            if (isInDictionary) {
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
            val wordToLearn = pendingWordForLearning

            isActivelyEditing = true

            currentInputConnection?.beginBatchEdit()
            try {
                autoCapitalizePronounI()

                if (wordToLearn != null) {
                    learnWordAndInvalidateCache(
                        wordToLearn,
                        InputMethod.TYPED,
                    )

                    currentInputConnection?.finishComposingText()
                } else {
                    coordinateWordCompletion()
                }

                currentInputConnection?.commitText(" ", 1)
                val cursorPos =
                    currentInputConnection
                        ?.getTextBeforeCursor(
                            KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                            0,
                        )?.length
                        ?: 0
                currentInputConnection?.setSelection(cursorPos, cursorPos)
                coordinateStateClear()
            } catch (_: Exception) {
                autoCapitalizePronounI()
                coordinateWordCompletion()
                currentInputConnection?.commitText(" ", 1)
                val cursorPos =
                    currentInputConnection
                        ?.getTextBeforeCursor(
                            KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                            0,
                        )?.length
                        ?: 0
                currentInputConnection?.setSelection(cursorPos, cursorPos)
                coordinateStateClear()
            } finally {
                currentInputConnection?.endBatchEdit()
            }

            val textBefore = safeGetTextBeforeCursor(50)
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

        if (!viewModel.state.value.isCapsLockOn) {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
        }
    }

    private fun applyCapitalizationToSuggestions(suggestions: List<String>): List<String> {
        val state = viewModel.state.value
        return when {
            state.isCapsLockOn -> {
                suggestions.map { it.uppercase() }
            }

            state.isShiftPressed || displayBuffer.firstOrNull()?.isUpperCase() == true -> {
                suggestions.map { it.replaceFirstChar { c -> c.uppercase() } }
            }

            else -> {
                suggestions
            }
        }
    }

    private fun isSentenceEndingPunctuation(char: Char): Boolean = UCharacter.hasBinaryProperty(char.code, UProperty.S_TERM)

    /**
     * Updates word state and displays suggestions.
     *
     * @param newWordState New word state with suggestions
     */
    private fun coordinateStateTransition(newWordState: WordState) {
        wordState = newWordState

        if (newWordState.suggestions.isNotEmpty()) {
            val displaySuggestions = applyCapitalizationToSuggestions(newWordState.suggestions)
            pendingSuggestions = displaySuggestions
            swipeKeyboardView?.updateSuggestions(displaySuggestions)
        } else {
            pendingSuggestions = emptyList()
            swipeKeyboardView?.clearSuggestions()
        }
    }

    /**
     * Commits selected suggestion without learning.
     *
     * Suggestions are already validated (dictionary or learned words),
     * so we just commit them without adding to learned words again.
     *
     * @param suggestion Selected suggestion to commit
     */
    private suspend fun coordinateSuggestionSelection(suggestion: String) {
        withContext(Dispatchers.Main) {
            try {
                isActivelyEditing = true

                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.commitText("$suggestion ", 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)

                    coordinateStateClear()

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
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

            lastDisplayDensity = resources.displayMetrics.density
            swipeDetector.updateDisplayMetrics(lastDisplayDensity)

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
            viewModel = KeyboardViewModel(repository, languageManager, themeManager)
            layoutManager =
                KeyboardLayoutManager(
                    context = this,
                    onKeyClick = { key -> handleKeyPress(key) },
                    onAcceleratedDeletionChanged = { active -> setAcceleratedDeletion(active) },
                    onSymbolsLongPress = { handleClipboardButtonClick() },
                    onLanguageSwitch = { languageCode -> handleLanguageSwitch(languageCode) },
                    characterVariationService = characterVariationService,
                    languageManager = languageManager,
                    themeManager = themeManager,
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

            val currentLayoutLanguage = languageManager.currentLayoutLanguage.value
            val locale = ULocale.forLanguageTag(currentLayoutLanguage)
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
                val layoutParams = actualWindow.attributes
                layoutParams.gravity = Gravity.BOTTOM
                actualWindow.attributes = layoutParams
            }

            val keyboardView = createSwipeKeyboardView() ?: return null

            val rootContainer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )

                    setBackgroundColor(themeManager.currentTheme.value.colors.keyboardBackground)

                    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.setPadding(
                            systemBars.left,
                            0,
                            systemBars.right,
                            systemBars.bottom,
                        )
                        WindowInsetsCompat.CONSUMED
                    }

                    addView(
                        keyboardView,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )

                    ViewCompat.requestApplyInsets(this)
                }

            keyboardRootContainer = rootContainer
            return rootContainer
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

    override fun onEvaluateInputViewShown(): Boolean {
        val config = resources.configuration
        return when (config.keyboard) {
            android.content.res.Configuration.KEYBOARD_NOKEYS,
            android.content.res.Configuration.KEYBOARD_UNDEFINED,
            -> super.onEvaluateInputViewShown()

            android.content.res.Configuration.KEYBOARD_QWERTY,
            android.content.res.Configuration.KEYBOARD_12KEY,
            -> false

            else -> super.onEvaluateInputViewShown()
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
                    initialize(layoutManager, swipeDetector, spellCheckManager, wordLearningEngine, themeManager, languageManager)
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
                    setOnBackspacePressedListener {
                        handleBackspace()
                    }
                    setOnSpacebarCursorMoveListener { distance ->
                        handleSpacebarCursorMove(distance)
                    }
                    setOnBackspaceSwipeDeleteListener {
                        handleBackspaceSwipeDelete()
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
                    val actualTextBefore = safeGetTextBeforeCursor(1)
                    val actualTextAfter = safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                if (!isSecureField && displayBuffer.isNotEmpty()) {
                    coordinateWordCompletion()
                }

                currentInputConnection?.commitText(emoji, 1)
                val cursorPos =
                    currentInputConnection
                        ?.getTextBeforeCursor(
                            KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                            0,
                        )?.length
                        ?: 0
                currentInputConnection?.setSelection(cursorPos, cursorPos)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handles clipboard button click.
     *
     * Shows clipboard panel with consent screen or clipboard items.
     */
    private fun handleClipboardButtonClick() {
        serviceScope.launch {
            try {
                val settings = settingsRepository.settings.first()

                if (!settings.clipboardEnabled) return@launch

                val clipboardPanel = ClipboardPanel(this@UrikInputMethodService, themeManager)

                if (!settings.clipboardConsentShown) {
                    clipboardPanel.showConsentScreen {
                        serviceScope.launch {
                            settingsRepository.updateClipboardConsentShown(true)
                            clipboardPanel.dismiss()
                        }
                    }
                } else {
                    val pinnedResult = clipboardRepository.getPinnedItems()
                    val recentResult = clipboardRepository.getRecentItems()

                    val pinnedItems = pinnedResult.getOrElse { emptyList() }
                    val recentItems = recentResult.getOrElse { emptyList() }

                    clipboardPanel.showClipboardContent(
                        pinnedItems = pinnedItems,
                        recentItems = recentItems,
                        onItemClick = { content ->
                            handleClipboardItemPaste(content)
                            clipboardPanel.dismiss()
                        },
                        onPinToggle = { item ->
                            handleClipboardPinToggle(item, clipboardPanel)
                        },
                        onDelete = { item ->
                            handleClipboardItemDelete(item, clipboardPanel)
                        },
                        onDeleteAll = {
                            handleClipboardDeleteAll(clipboardPanel)
                        },
                    )
                }

                withContext(Dispatchers.Main) {
                    val anchorView = swipeKeyboardView ?: return@withContext
                    clipboardPanel.width = anchorView.width
                    clipboardPanel.height = (anchorView.height * 0.75).toInt()
                    clipboardPanel.showAsDropDown(anchorView, 0, -anchorView.height)
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleClipboardButtonClick"),
                )
            }
        }
    }

    private fun handleLanguageSwitch(languageCode: String) {
        serviceScope.launch {
            try {
                coordinateStateClear()
                viewModel.clearShiftAndCapsState()

                languageManager.switchLayoutLanguage(languageCode)

                val locale =
                    ULocale
                        .forLanguageTag(languageCode)
                updateScriptContext(locale)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleLanguageSwitch"),
                )
            }
        }
    }

    private fun handleClipboardItemPaste(content: String) {
        serviceScope.launch {
            try {
                currentInputConnection?.commitText(content, 1)
                val cursorPos =
                    currentInputConnection
                        ?.getTextBeforeCursor(
                            KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                            0,
                        )?.length
                        ?: 0
                currentInputConnection?.setSelection(cursorPos, cursorPos)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun refreshClipboardPanel(panel: ClipboardPanel) {
        val pinnedResult = clipboardRepository.getPinnedItems()
        val recentResult = clipboardRepository.getRecentItems()

        val pinnedItems = pinnedResult.getOrElse { emptyList() }
        val recentItems = recentResult.getOrElse { emptyList() }

        withContext(Dispatchers.Main) {
            panel.refreshContent(pinnedItems, recentItems)
        }
    }

    private fun handleClipboardPinToggle(
        item: com.urik.keyboard.data.database.ClipboardItem,
        panel: ClipboardPanel,
    ) {
        serviceScope.launch {
            clipboardRepository.togglePin(item.id, !item.isPinned)
            refreshClipboardPanel(panel)
        }
    }

    private fun handleClipboardItemDelete(
        item: com.urik.keyboard.data.database.ClipboardItem,
        panel: ClipboardPanel,
    ) {
        serviceScope.launch {
            clipboardRepository.deleteItem(item.id)
            refreshClipboardPanel(panel)
        }
    }

    private fun handleClipboardDeleteAll(panel: ClipboardPanel) {
        serviceScope.launch {
            clipboardRepository.deleteAllUnpinned()
            refreshClipboardPanel(panel)
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
                            currentSettings.showNumberRow != newSettings.showNumberRow ||
                            currentSettings.clipboardEnabled != newSettings.clipboardEnabled

                    currentSettings = newSettings

                    swipeDetector.setSwipeEnabled(newSettings.swipeEnabled)

                    layoutManager.updateLongPressDuration(newSettings.longPressDuration)
                    layoutManager.updateSpacebarLongPressPunctuation(newSettings.spacebarLongPressPunctuation)

                    layoutManager.updateKeySize(newSettings.keySize)
                    layoutManager.updateSpaceBarSize(newSettings.spaceBarSize)
                    layoutManager.updateKeyLabelSize(newSettings.keyLabelSize)

                    layoutManager.updateHapticSettings(
                        newSettings.hapticFeedback,
                        newSettings.vibrationStrength,
                    )

                    layoutManager.updateClipboardEnabled(newSettings.clipboardEnabled)

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
                languageManager.currentLayoutLanguage.collect { detectedLanguage ->
                    val locale = ULocale.forLanguageTag(detectedLanguage)
                    updateScriptContext(locale)
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.activeLanguages.collect { languages ->
                    layoutManager.updateActiveLanguages(languages)
                    swipeDetector.updateActiveLanguages(languages)
                    updateSwipeKeyboard()
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                themeManager.currentTheme.collect { theme ->
                    keyboardRootContainer?.setBackgroundColor(theme.colors.keyboardBackground)
                    updateSwipeKeyboard()
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
                    when {
                        !currentSettings.showNumberRow &&
                            layout.mode == KeyboardMode.LETTERS &&
                            layout.rows.isNotEmpty() -> {
                            layout.copy(rows = layout.rows.drop(1))
                        }

                        currentSettings.showNumberRow &&
                            layout.mode == KeyboardMode.SYMBOLS &&
                            layout.rows.isNotEmpty() -> {
                            val numberRow =
                                listOf(
                                    KeyboardKey.Character("1", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("2", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("3", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("4", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("5", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("6", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("7", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("8", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("9", KeyboardKey.KeyType.NUMBER),
                                    KeyboardKey.Character("0", KeyboardKey.KeyType.NUMBER),
                                )
                            layout.copy(rows = listOf(numberRow) + layout.rows)
                        }

                        else -> {
                            layout
                        }
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
        layoutManager.updateSpacebarLongPressPunctuation(currentSettings.spacebarLongPressPunctuation)
        layoutManager.updateKeySize(currentSettings.keySize)
        layoutManager.updateSpaceBarSize(currentSettings.spaceBarSize)
        layoutManager.updateKeyLabelSize(currentSettings.keyLabelSize)

        layoutManager.updateHapticSettings(
            currentSettings.hapticFeedback,
            currentSettings.vibrationStrength,
        )

        layoutManager.updateClipboardEnabled(currentSettings.clipboardEnabled)

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

        val inputType = info?.inputType ?: 0
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        isUrlOrEmailField = variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        val targetMode = KeyboardModeUtils.determineTargetMode(info, viewModel.state.value.currentMode)
        if (targetMode != viewModel.state.value.currentMode) {
            viewModel.onEvent(KeyboardEvent.ModeChanged(targetMode))
        }

        if (isSecureField) {
            clearSecureFieldState()
        } else if (!isUrlOrEmailField) {
            try {
                currentInputConnection?.finishComposingText()
            } catch (_: Exception) {
            }

            val textBefore = safeGetTextBeforeCursor(50)
            viewModel.checkAndApplyAutoCapitalization(textBefore)
        } else {
            if (displayBuffer.isNotEmpty()) {
                val actualTextBefore = safeGetTextBeforeCursor(1)
                val actualTextAfter = safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            try {
                currentInputConnection?.finishComposingText()
            } catch (_: Exception) {
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

                    if (isAlphaNumericInput(char)) {
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
            lastSpaceTime = 0

            if (isSecureField) {
                currentInputConnection?.commitText(char, 1)
                val cursorPos =
                    currentInputConnection
                        ?.getTextBeforeCursor(
                            KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                            0,
                        )?.length
                        ?: 0
                currentInputConnection?.setSelection(cursorPos, cursorPos)
                return
            }

            if (displayBuffer.isNotEmpty() && wordState.isFromSwipe) {
                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.commitText("$displayBuffer ", 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)

                    coordinateStateClear()

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }

            if (displayBuffer.isNotEmpty()) {
                val actualTextBefore = safeGetTextBeforeCursor(1)
                val actualTextAfter = safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                clearSpellConfirmationState()
            }

            isActivelyEditing = true

            if (composingRegionStart != -1 && displayBuffer.isNotEmpty()) {
                val currentText = safeGetTextBeforeCursor(displayBuffer.length + 10)
                val expectedComposingText =
                    if (currentText.length >= displayBuffer.length) {
                        currentText.substring(maxOf(0, currentText.length - displayBuffer.length))
                    } else {
                        ""
                    }

                if (expectedComposingText != displayBuffer) {
                    composingRegionStart = -1
                }
            }

            val cursorPosInWord =
                if (composingRegionStart != -1 && displayBuffer.isNotEmpty()) {
                    val absoluteCursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: displayBuffer.length
                    CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, composingRegionStart, displayBuffer.length)
                } else {
                    displayBuffer.length
                }

            displayBuffer =
                if (displayBuffer.isEmpty()) {
                    composingRegionStart = -1
                    char
                } else {
                    StringBuilder(displayBuffer)
                        .insert(cursorPosInWord, char)
                        .toString()
                }

            val ic = currentInputConnection
            if (ic != null) {
                try {
                    ic.beginBatchEdit()
                    ic.setComposingText(displayBuffer, 1)
                } finally {
                    ic.endBatchEdit()
                }
            }

            wordState =
                wordState.copy(
                    buffer = displayBuffer,
                    graphemeCount = displayBuffer.length,
                )

            if (isUrlOrEmailField) {
                return
            }

            val (currentSequence, bufferSnapshot) =
                synchronized(processingLock) {
                    ++processingSequence to displayBuffer
                }

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
                                                val displaySuggestions = applyCapitalizationToSuggestions(result.wordState.suggestions)
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
            val cursorPos =
                currentInputConnection?.getTextBeforeCursor(KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS, 0)?.length
                    ?: 0
            currentInputConnection?.setSelection(cursorPos, cursorPos)
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
                lastSpaceTime = 0

                if (isSecureField) {
                    currentInputConnection?.commitText(char, 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)
                    return@launch
                }

                if (displayBuffer.isNotEmpty()) {
                    val actualTextBefore = safeGetTextBeforeCursor(1)
                    val actualTextAfter = safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        confirmAndLearnWord()
                        currentInputConnection?.commitText(char, 1)
                        val cursorPos =
                            currentInputConnection
                                ?.getTextBeforeCursor(
                                    KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                    0,
                                )?.length
                                ?: 0
                        currentInputConnection?.setSelection(cursorPos, cursorPos)

                        val singleChar = char.single()

                        if (isSentenceEndingPunctuation(singleChar) && !isSecureField) {
                            viewModel.disableCapsLockAfterPunctuation()
                            val textBefore = safeGetTextBeforeCursor(50)
                            viewModel.checkAndApplyAutoCapitalization(textBefore)
                        }
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }
                    return@launch
                }

                if (displayBuffer.isNotEmpty() && wordState.requiresSpellCheck) {
                    if (wordState.graphemeCount >= 2) {
                        val textBefore = safeGetTextBeforeCursor(100)
                        val isUrlOrEmail =
                            UrlEmailDetector.isUrlOrEmailContext(
                                currentWord = displayBuffer,
                                textBeforeCursor = textBefore,
                                nextChar = char,
                            )

                        if (!isUrlOrEmail) {
                            val isValid = textInputProcessor.validateWord(wordState.normalizedBuffer)
                            if (!isValid) {
                                val isPunctuation =
                                    char.length == 1 && CursorEditingUtils.isPunctuation(char.single())

                                if (isPunctuation) {
                                    currentInputConnection?.beginBatchEdit()
                                    try {
                                        autoCapitalizePronounI()
                                        learnWordAndInvalidateCache(
                                            wordState.normalizedBuffer,
                                            InputMethod.TYPED,
                                        )
                                        currentInputConnection?.finishComposingText()
                                        currentInputConnection?.commitText(char, 1)
                                        val cursorPos =
                                            currentInputConnection
                                                ?.getTextBeforeCursor(
                                                    KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                                    0,
                                                )?.length
                                                ?: 0
                                        currentInputConnection?.setSelection(cursorPos, cursorPos)

                                        val singleChar = char.single()
                                        if (isSentenceEndingPunctuation(singleChar) && !isSecureField) {
                                            viewModel.disableCapsLockAfterPunctuation()
                                            val textAfter = safeGetTextBeforeCursor(50)
                                            viewModel.checkAndApplyAutoCapitalization(textAfter)
                                        }

                                        coordinateStateClear()
                                    } finally {
                                        currentInputConnection?.endBatchEdit()
                                    }
                                    return@launch
                                } else {
                                    spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                    pendingWordForLearning = wordState.normalizedBuffer
                                    highlightCurrentWord()

                                    val suggestions = textInputProcessor.getSuggestions(wordState.normalizedBuffer)
                                    val displaySuggestions = applyCapitalizationToSuggestions(suggestions)
                                    pendingSuggestions = displaySuggestions
                                    if (displaySuggestions.isNotEmpty()) {
                                        swipeKeyboardView?.updateSuggestions(displaySuggestions)
                                    } else {
                                        swipeKeyboardView?.clearSuggestions()
                                    }
                                    return@launch
                                }
                            }
                        }
                    }
                }

                currentInputConnection?.beginBatchEdit()
                try {
                    autoCapitalizePronounI()
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(char, 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)

                    val singleChar = char.single()

                    if (isSentenceEndingPunctuation(singleChar) && !isSecureField) {
                        viewModel.disableCapsLockAfterPunctuation()
                        val textBefore =
                            safeGetTextBeforeCursor(50)
                        viewModel.checkAndApplyAutoCapitalization(textBefore)
                    }
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.beginBatchEdit()
                try {
                    autoCapitalizePronounI()
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(char, 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)
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
                currentInputConnection?.beginBatchEdit()
                try {
                    autoCapitalizePronounI()
                    currentInputConnection?.commitText("$displayBuffer ", 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)

                    coordinateStateClear()

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                clearSpellConfirmationState()
            }

            if (validatedWord.isEmpty()) return

            val shouldCapitalize =
                viewModel.state.value.isShiftPressed || viewModel.state.value.isCapsLockOn

            val currentLanguage =
                languageManager.currentLanguage.value
                    .split("-")
                    .first()
            val isEnglishPronounI =
                currentLanguage == "en" &&
                    (
                        validatedWord.lowercase() == "i" ||
                            validatedWord.lowercase() == "i'm" ||
                            validatedWord.lowercase() == "i'll" ||
                            validatedWord.lowercase() == "i've" ||
                            validatedWord.lowercase() == "i'd"
                    )

            val displayWord =
                if (isEnglishPronounI) {
                    when (validatedWord.lowercase()) {
                        "i" -> "I"
                        "i'm" -> "I'm"
                        "i'll" -> "I'll"
                        "i've" -> "I've"
                        "i'd" -> "I'd"
                        else -> validatedWord
                    }
                } else if (shouldCapitalize) {
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
                textInputProcessor.removeSuggestion(suggestion)

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
                val actualTextBefore = safeGetTextBeforeCursor(1)
                val actualTextAfter = safeGetTextAfterCursor(1)

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
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)
                }
            }

            coordinateStateClear()

            if (imeAction == EditorInfo.IME_ACTION_NONE) {
                val textBefore = safeGetTextBeforeCursor(50)
                viewModel.checkAndApplyAutoCapitalization(textBefore)
            }
        } catch (_: Exception) {
            coordinateStateClear()
        }
    }

    /**
     * Handles backspace key press.
     */
    private fun handleBackspace() {
        try {
            val selectedText = currentInputConnection?.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                currentInputConnection?.commitText("", 1)
                coordinateStateClear()
                return
            }

            if (displayBuffer.isNotEmpty() && wordState.isFromSwipe) {
                currentInputConnection?.setComposingText("", 1)
                coordinateStateClear()
                return
            }

            val textBeforeCursor = safeGetTextBeforeCursor(50)

            if (isSecureField) {
                if (textBeforeCursor.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                    currentInputConnection?.deleteSurroundingText(graphemeLength, 0)
                }
                return
            }

            if (displayBuffer.isNotEmpty()) {
                val actualTextBefore = safeGetTextBeforeCursor(1)
                val actualTextAfter = safeGetTextAfterCursor(1)

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
                val currentText = safeGetTextBeforeCursor(displayBuffer.length + 10)
                val expectedComposingText =
                    if (currentText.length >= displayBuffer.length) {
                        currentText.substring(maxOf(0, currentText.length - displayBuffer.length))
                    } else {
                        ""
                    }

                if (expectedComposingText != displayBuffer) {
                    coordinateStateClear()
                    handleCommittedTextBackspace()
                    return
                }
            }

            isActivelyEditing = true

            if (displayBuffer.isNotEmpty()) {
                val absoluteCursorPos =
                    currentInputConnection
                        ?.getTextBeforeCursor(
                            KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                            0,
                        )?.length
                        ?: displayBuffer.length

                val cursorPosInWord =
                    if (composingRegionStart != -1) {
                        CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, composingRegionStart, displayBuffer.length)
                    } else {
                        val potentialStart = absoluteCursorPos - displayBuffer.length
                        if (potentialStart >= 0) {
                            composingRegionStart = potentialStart
                            CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, composingRegionStart, displayBuffer.length)
                        } else {
                            displayBuffer.length
                        }
                    }

                if (cursorPosInWord == 0) {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        coordinateStateClear()
                        val textBefore = safeGetTextBeforeCursor(50)
                        if (textBefore.isNotEmpty()) {
                            val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBefore)
                            currentInputConnection?.deleteSurroundingText(graphemeLength, 0)
                        }
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }
                    return
                }

                if (cursorPosInWord > 0) {
                    val deletedChar = displayBuffer.getOrNull(cursorPosInWord - 1)
                    val shouldResetShift =
                        deletedChar != null &&
                            isSentenceEndingPunctuation(deletedChar) &&
                            viewModel.state.value.isShiftPressed &&
                            !viewModel.state.value.isCapsLockOn

                    displayBuffer = BackspaceUtils.deleteGraphemeClusterBeforePosition(displayBuffer, cursorPosInWord)

                    if (shouldResetShift) {
                        viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                    }

                    if (displayBuffer.isNotEmpty()) {
                        val ic = currentInputConnection
                        if (ic != null) {
                            try {
                                ic.beginBatchEdit()
                                ic.setComposingText(displayBuffer, 1)
                            } finally {
                                ic.endBatchEdit()
                            }
                        }

                        if (!isAcceleratedDeletion) {
                            val (currentSequence, bufferSnapshot) =
                                synchronized(processingLock) {
                                    ++processingSequence to displayBuffer
                                }

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
                                                                val displaySuggestions =
                                                                    applyCapitalizationToSuggestions(result.wordState.suggestions)
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
                        currentInputConnection?.setComposingText("", 1)
                        coordinateStateClear()
                    }
                }
            } else {
                handleCommittedTextBackspace()
            }
        } catch (_: Exception) {
            try {
                val textBefore = safeGetTextBeforeCursor(50)
                if (textBefore.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBefore)
                    currentInputConnection?.deleteSurroundingText(graphemeLength, 0)
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
            val textBeforeCursor = safeGetTextBeforeCursor(50)

            if (textBeforeCursor.isNotEmpty()) {
                currentInputConnection?.beginBatchEdit()
                try {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                    val deletedChar = textBeforeCursor.lastOrNull()

                    currentInputConnection?.deleteSurroundingText(graphemeLength, 0)

                    if (deletedChar != null) {
                        val shouldResetShift =
                            if (isSentenceEndingPunctuation(deletedChar)) {
                                true
                            } else if (deletedChar.isWhitespace()) {
                                val trimmed = textBeforeCursor.dropLast(1).trimEnd()
                                trimmed.isNotEmpty() && isSentenceEndingPunctuation(trimmed.last())
                            } else {
                                false
                            }

                        if (shouldResetShift &&
                            viewModel.state.value.isShiftPressed &&
                            !viewModel.state.value.isCapsLockOn
                        ) {
                            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                        }
                    }

                    if (!isAcceleratedDeletion) {
                        val remainingText = textBeforeCursor.dropLast(1)
                        val wordInfo = extractWordBeforeCursor(remainingText)

                        if (wordInfo != null) {
                            val (wordBeforeCursor, _) = wordInfo

                            val actualCursorPos =
                                currentInputConnection
                                    ?.getTextBeforeCursor(
                                        KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                        0,
                                    )?.length
                                    ?: 0
                            val wordStart = actualCursorPos - wordBeforeCursor.length

                            currentInputConnection?.setComposingRegion(wordStart, actualCursorPos)

                            displayBuffer = wordBeforeCursor
                            composingRegionStart = wordStart

                            val (currentSequence, bufferSnapshot) =
                                synchronized(processingLock) {
                                    ++processingSequence to displayBuffer
                                }

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
                                                                val displaySuggestions =
                                                                    applyCapitalizationToSuggestions(result.wordState.suggestions)
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
                        } else {
                            coordinateStateClear()
                        }
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
     * Handles space key press.
     */
    private fun handleSpace() {
        serviceScope.launch {
            try {
                if (isSecureField) {
                    currentInputConnection?.commitText(" ", 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)
                    return@launch
                }

                if (displayBuffer.isNotEmpty()) {
                    val actualTextBefore = safeGetTextBeforeCursor(1)
                    val actualTextAfter = safeGetTextAfterCursor(1)

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
                        val cursorPos =
                            currentInputConnection
                                ?.getTextBeforeCursor(
                                    KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                    0,
                                )?.length
                                ?: 0
                        currentInputConnection?.setSelection(cursorPos, cursorPos)

                        val textBefore =
                            safeGetTextBeforeCursor(50)
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
                        val textBeforeForUrlCheck = safeGetTextBeforeCursor(100)
                        val isUrlOrEmail =
                            UrlEmailDetector.isUrlOrEmailContext(
                                currentWord = displayBuffer,
                                textBeforeCursor = textBeforeForUrlCheck,
                                nextChar = " ",
                            )

                        if (!isUrlOrEmail) {
                            val isValid = textInputProcessor.validateWord(wordState.normalizedBuffer)
                            if (isValid) {
                                currentInputConnection?.beginBatchEdit()
                                try {
                                    autoCapitalizePronounI()
                                    coordinateStateClear()
                                    currentInputConnection?.commitText(" ", 1)
                                    val cursorPos =
                                        currentInputConnection
                                            ?.getTextBeforeCursor(
                                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                                0,
                                            )?.length
                                            ?: 0
                                    currentInputConnection?.setSelection(cursorPos, cursorPos)

                                    val textBefore =
                                        safeGetTextBeforeCursor(50)
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
                            val displaySuggestions = applyCapitalizationToSuggestions(suggestions)
                            pendingSuggestions = displaySuggestions
                            if (displaySuggestions.isNotEmpty()) {
                                swipeKeyboardView?.updateSuggestions(displaySuggestions)
                            } else {
                                swipeKeyboardView?.clearSuggestions()
                            }

                            return@launch
                        }
                    }
                }

                currentInputConnection?.beginBatchEdit()
                try {
                    autoCapitalizePronounI()
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(" ", 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.beginBatchEdit()
                try {
                    autoCapitalizePronounI()
                    coordinateWordCompletion()
                    currentInputConnection?.commitText(" ", 1)
                    val cursorPos =
                        currentInputConnection
                            ?.getTextBeforeCursor(
                                KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                                0,
                            )?.length
                            ?: 0
                    currentInputConnection?.setSelection(cursorPos, cursorPos)

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }
        }
    }

    private fun handleSpacebarCursorMove(distance: Int) {
        if (isSecureField || !currentSettings.spacebarCursorControl) {
            return
        }

        try {
            val currentSelection =
                currentInputConnection
                    ?.getTextBeforeCursor(
                        KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS,
                        0,
                    )?.length ?: 0

            val newPosition = (currentSelection + distance).coerceAtLeast(0)
            currentInputConnection?.setSelection(newPosition, newPosition)
        } catch (_: Exception) {
        }
    }

    private fun handleBackspaceSwipeDelete() {
        if (isSecureField || !currentSettings.backspaceSwipeDelete) {
            return
        }

        try {
            isActivelyEditing = true
            val textBeforeCursor = safeGetTextBeforeCursor(50)
            if (textBeforeCursor.isEmpty()) {
                return
            }

            val lastChar = textBeforeCursor.last()

            if (Character.isWhitespace(lastChar)) {
                currentInputConnection?.deleteSurroundingText(1, 0)
                coordinateStateClear()
                return
            }

            if (Character.isLetterOrDigit(lastChar)) {
                val wordInfo = BackspaceUtils.extractWordBeforeCursor(textBeforeCursor)
                if (wordInfo != null) {
                    val (word, _) = wordInfo
                    currentInputConnection?.deleteSurroundingText(word.length, 0)
                    coordinateStateClear()
                }
                return
            }

            var idx = textBeforeCursor.length
            while (idx > 0 && !Character.isLetterOrDigit(textBeforeCursor[idx - 1]) && !Character.isWhitespace(textBeforeCursor[idx - 1])) {
                idx--
            }
            val trailingPunctuationCount = textBeforeCursor.length - idx

            if (idx > 0 && !Character.isWhitespace(textBeforeCursor[idx - 1])) {
                val textBeforePunctuation = textBeforeCursor.take(idx)
                val wordInfo = BackspaceUtils.extractWordBeforeCursor(textBeforePunctuation)
                if (wordInfo != null) {
                    val (word, _) = wordInfo
                    currentInputConnection?.deleteSurroundingText(word.length + trailingPunctuationCount, 0)
                    coordinateStateClear()
                    return
                }
            }

            currentInputConnection?.deleteSurroundingText(trailingPunctuationCount, 0)
            coordinateStateClear()
        } catch (_: Exception) {
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)

        suggestionDebounceJob?.cancel()
        swipeKeyboardView?.hideEmojiPicker()

        if (displayBuffer.isNotEmpty() && !isSecureField) {
            val actualTextBefore = safeGetTextBeforeCursor(1)
            val actualTextAfter = safeGetTextAfterCursor(1)

            if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                coordinateStateClear()
            } else {
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

        try {
            currentInputConnection?.finishComposingText()
            coordinateStateClear()
        } catch (_: Exception) {
            coordinateStateClear()
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

        val inputType = attribute?.inputType ?: 0
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        isUrlOrEmailField = variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        if (isSecureField) {
            clearSecureFieldState()
        } else if (!isUrlOrEmailField) {
            val textBefore = safeGetTextBeforeCursor(50)
            viewModel.checkAndApplyAutoCapitalization(textBefore)
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()

        suggestionDebounceJob?.cancel()
        swipeKeyboardView?.hideEmojiPicker()

        if (displayBuffer.isNotEmpty() && !isSecureField) {
            val actualTextBefore = safeGetTextBeforeCursor(1)
            val actualTextAfter = safeGetTextAfterCursor(1)

            if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                coordinateStateClear()
            } else {
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

        try {
            currentInputConnection?.finishComposingText()
        } catch (_: Exception) {
        }

        coordinateStateClear()

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
            val textBefore = safeGetTextBeforeCursor(50)
            val textAfter = safeGetTextAfterCursor(50)

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
            }

            if (!isUrlOrEmailField) {
                viewModel.checkAndApplyAutoCapitalization(textBefore)
            }
        }

        if (isUrlOrEmailField) return

        val hasComposingText = (candidatesStart != -1 && candidatesEnd != -1)
        val cursorInComposingRegion =
            hasComposingText &&
                newSelStart >= candidatesStart &&
                newSelStart <= candidatesEnd &&
                newSelEnd >= candidatesStart &&
                newSelEnd <= candidatesEnd

        if (isActivelyEditing) {
            isActivelyEditing = false
            return
        }

        if (hasComposingText && !cursorInComposingRegion) {
            coordinateStateClear()
            return
        }

        if (!hasComposingText && (wordState.hasContent || displayBuffer.isNotEmpty())) {
            coordinateStateClear()
            return
        }

        if (!hasComposingText && !isActivelyEditing && newSelStart == newSelEnd) {
            val textBefore = safeGetTextBeforeCursor(50)
            val textAfter = safeGetTextAfterCursor(50)

            val wordBeforeInfo = if (textBefore.isNotEmpty()) extractWordBeforeCursor(textBefore) else null

            if (wordBeforeInfo != null && wordBeforeInfo.first.isNotEmpty()) {
                val wordAfterStart =
                    textAfter.indexOfFirst { char ->
                        char.isWhitespace() || char == '\n' || CursorEditingUtils.isPunctuation(char)
                    }
                val wordAfter = if (wordAfterStart >= 0) textAfter.take(wordAfterStart) else textAfter
                val trimmedWordAfter = if (wordAfter.isNotEmpty() && CursorEditingUtils.isValidTextInput(wordAfter)) wordAfter else ""

                val fullWord = wordBeforeInfo.first + trimmedWordAfter
                val wordStart = newSelStart - wordBeforeInfo.first.length

                if (fullWord.length >= 2) {
                    currentInputConnection?.setComposingRegion(wordStart, wordStart + fullWord.length)
                    displayBuffer = fullWord
                    composingRegionStart = wordStart
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        val currentDensity = resources.displayMetrics.density

        if (lastDisplayDensity != 0f && lastDisplayDensity != currentDensity) {
            layoutManager.onDensityChanged()
            swipeDetector.updateDisplayMetrics(currentDensity)
            swipeKeyboardView?.let { view ->
                if (view.currentLayout != null && view.currentState != null) {
                    view.updateKeyboard(view.currentLayout!!, view.currentState!!)
                }
            }
        }

        lastDisplayDensity = currentDensity

        val currentKeyboard = newConfig.keyboard
        if (lastKeyboardConfig != android.content.res.Configuration.KEYBOARD_UNDEFINED &&
            lastKeyboardConfig != currentKeyboard
        ) {
            updateInputViewShown()
        }
        lastKeyboardConfig = currentKeyboard
    }

    override fun onDestroy() {
        serviceJob.cancel()

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        coordinateStateClear()
        swipeKeyboardView = null

        if (::layoutManager.isInitialized) {
            layoutManager.cleanup()
        }

        cacheMemoryManager.cleanup()

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
