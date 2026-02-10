package com.urik.keyboard

import android.annotation.SuppressLint
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
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
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.ClipboardMonitorService
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.InputMethod
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.ProcessingResult
import com.urik.keyboard.service.AutofillStateTracker
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
import com.urik.keyboard.utils.SelectionChangeResult
import com.urik.keyboard.utils.SelectionStateTracker
import com.urik.keyboard.utils.UrlEmailDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
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
    lateinit var wordFrequencyRepository: com.urik.keyboard.data.WordFrequencyRepository

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

    @Inject
    lateinit var clipboardMonitorService: ClipboardMonitorService

    @Inject
    lateinit var emojiSearchManager: EmojiSearchManager

    @Inject
    lateinit var recentEmojiProvider: com.urik.keyboard.service.RecentEmojiProvider

    @Inject
    lateinit var customKeyMappingService: com.urik.keyboard.service.CustomKeyMappingService

    @Inject
    lateinit var keyboardModeManager: com.urik.keyboard.service.KeyboardModeManager

    @Inject
    lateinit var caseTransformer: com.urik.keyboard.utils.CaseTransformer

    @Inject
    lateinit var swipeSpaceManager: com.urik.keyboard.service.SwipeSpaceManager

    private lateinit var viewModel: KeyboardViewModel
    private lateinit var layoutManager: KeyboardLayoutManager
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var postureDetector: com.urik.keyboard.service.PostureDetector? = null

    private val inputMethodManager: android.view.inputmethod.InputMethodManager by lazy {
        getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    }

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val observerJobs = mutableListOf<Job>()

    private var swipeKeyboardView: SwipeKeyboardView? = null
    private var adaptiveContainer: com.urik.keyboard.ui.keyboard.components.AdaptiveKeyboardContainer? = null
    private var keyboardRootContainer: LinearLayout? = null
    private var clipboardPanel: ClipboardPanel? = null
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
    private var isCurrentWordAtSentenceStart = false

    @Volatile
    private var isCurrentWordManualShifted = false

    @Volatile
    private var pendingSuggestions: List<String> = emptyList()

    @Volatile
    private var currentRawSuggestions: List<com.urik.keyboard.service.SpellingSuggestion> = emptyList()

    @Volatile
    private var spellConfirmationState = SpellConfirmationState.NORMAL

    @Volatile
    private var pendingWordForLearning: String? = null

    @Volatile
    private var isSecureField: Boolean = false

    @Volatile
    private var isDirectCommitField: Boolean = false

    private val requiresDirectCommit: Boolean
        get() = isSecureField || isDirectCommitField

    @Volatile
    private var currentInputAction: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER

    @Volatile
    private var lastCommittedWord: String = ""

    @Volatile
    private var isShowingBigramPredictions: Boolean = false

    private val selectionStateTracker = SelectionStateTracker()

    @Volatile
    private var lastKnownCursorPosition: Int = -1

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

    private fun safeGetCursorPosition(maxChars: Int = KeyboardConstants.TextProcessingConstants.MAX_CURSOR_POSITION_CHARS): Int =
        try {
            currentInputConnection
                ?.getTextBeforeCursor(maxChars, 0)
                ?.take(maxChars)
                ?.length
                ?: 0
        } catch (_: Exception) {
            0
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
     * @return True if learning succeeded or is disabled
     */
    private fun recordWordUsage(word: String) {
        try {
            val currentLanguage = languageManager.currentLanguage.value
            wordFrequencyRepository.incrementFrequency(word, currentLanguage)

            if (lastCommittedWord.isNotBlank()) {
                wordFrequencyRepository.recordBigram(lastCommittedWord, word, currentLanguage)
            }
            lastCommittedWord = word
        } catch (_: Exception) {
        }
    }

    private suspend fun learnWordAndInvalidateCache(
        word: String,
        inputMethod: InputMethod,
    ): Boolean {
        return try {
            val settings = textInputProcessor.getCurrentSettings()
            if (!settings.isWordLearningEnabled) {
                return true
            }

            recordWordUsage(word)

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

            if (wordToLearn != null) {
                learnWordAndInvalidateCache(
                    wordToLearn,
                    InputMethod.TYPED,
                )
            }

            currentInputConnection?.beginBatchEdit()
            try {
                autoCapitalizePronounI()
                currentInputConnection?.finishComposingText()
                currentInputConnection?.commitText(" ", 1)
                clearInternalStateOnly()
                showBigramPredictions()
            } catch (_: Exception) {
                currentInputConnection?.finishComposingText()
                currentInputConnection?.commitText(" ", 1)
                clearInternalStateOnly()
            } finally {
                currentInputConnection?.endBatchEdit()
            }

            val textBefore = safeGetTextBeforeCursor(50)
            viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
        }
    }

    private fun commitPreviousSwipeAndInsertSpace() {
        if (!wordState.isFromSwipe || displayBuffer.isEmpty()) return

        currentInputConnection?.finishComposingText()
        val textBefore = safeGetTextBeforeCursor(1)
        if (!swipeSpaceManager.isWhitespace(textBefore)) {
            currentInputConnection?.commitText(" ", 1)
            swipeSpaceManager.markAutoSpaceInserted()
        }
        displayBuffer = ""
    }

    private fun clearInternalStateOnly() {
        synchronized(processingLock) {
            processingSequence++
        }

        suggestionDebounceJob?.cancel()

        isActivelyEditing = true
        isCurrentWordAtSentenceStart = false
        isCurrentWordManualShifted = false
        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        currentRawSuggestions = emptyList()
        isShowingBigramPredictions = false
        clearSpellConfirmationState()
        swipeKeyboardView?.clearSuggestions()
        composingRegionStart = -1
        lastKnownCursorPosition = -1

        if (!viewModel.state.value.isCapsLockOn) {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
        }
    }

    private fun coordinateStateClear() {
        clearInternalStateOnly()
        currentInputConnection?.finishComposingText()
    }

    private fun attemptRecompositionAtCursor(cursorPosition: Int) {
        if (requiresDirectCommit || isUrlOrEmailField) return
        if (displayBuffer.isNotEmpty()) return

        val textBefore = safeGetTextBeforeCursor(KeyboardConstants.TextProcessingConstants.WORD_BOUNDARY_CONTEXT_LENGTH)
        val textAfter = safeGetTextAfterCursor(KeyboardConstants.TextProcessingConstants.WORD_BOUNDARY_CONTEXT_LENGTH)

        if (textBefore.isNotEmpty() && (textBefore.last().isWhitespace() || textBefore.last() == '\n')) {
            return
        }

        val wordBeforeInfo =
            if (textBefore.isNotEmpty()) {
                CursorEditingUtils.extractWordBoundedByParagraph(textBefore)
            } else {
                null
            }

        if (wordBeforeInfo != null && wordBeforeInfo.first.isNotEmpty()) {
            val wordAfterStart =
                textAfter.indexOfFirst { char ->
                    char.isWhitespace() || char == '\n' || CursorEditingUtils.isPunctuation(char)
                }
            val wordAfter = if (wordAfterStart >= 0) textAfter.take(wordAfterStart) else textAfter
            val trimmedWordAfter = if (wordAfter.isNotEmpty() && CursorEditingUtils.isValidTextInput(wordAfter)) wordAfter else ""

            val fullWord = wordBeforeInfo.first + trimmedWordAfter
            val wordStart = cursorPosition - wordBeforeInfo.first.length

            if (wordStart >= 0 && fullWord.length >= 2) {
                currentInputConnection?.setComposingRegion(wordStart, wordStart + fullWord.length)
                displayBuffer = fullWord
                composingRegionStart = wordStart
            }
        }
    }

    private fun invalidateComposingStateOnCursorJump() {
        synchronized(processingLock) {
            processingSequence++
        }

        suggestionDebounceJob?.cancel()

        isActivelyEditing = true

        currentInputConnection?.finishComposingText()

        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        isShowingBigramPredictions = false
        clearSpellConfirmationState()
        swipeKeyboardView?.clearSuggestions()
        composingRegionStart = -1
        lastKnownCursorPosition = -1
        selectionStateTracker.clearExpectedPosition()
    }

    private fun showBigramPredictions() {
        if (requiresDirectCommit || !currentSettings.showSuggestions || lastCommittedWord.isBlank()) {
            return
        }

        serviceScope.launch {
            try {
                val currentLanguage = languageManager.currentLanguage.value
                val allPredictions =
                    wordFrequencyRepository.getBigramPredictions(
                        lastCommittedWord,
                        currentLanguage,
                        currentSettings.effectiveSuggestionCount,
                    )

                val predictions = allPredictions.filter { !spellCheckManager.isWordBlacklisted(it) }

                if (predictions.isNotEmpty() && displayBuffer.isEmpty()) {
                    val suggestionObjects =
                        predictions.mapIndexed { index, word ->
                            com.urik.keyboard.service.SpellingSuggestion(
                                word = word,
                                confidence = 0.85 - (index * 0.02),
                                ranking = index,
                                source = "bigram",
                                preserveCase = false,
                            )
                        }
                    val textBefore = safeGetTextBeforeCursor(50)
                    val bigramSentenceStart = viewModel.shouldAutoCapitalize(textBefore)
                    val displayPredictions = applyCapitalizationToSuggestions(suggestionObjects, bigramSentenceStart)
                    withContext(Dispatchers.Main) {
                        if (displayBuffer.isEmpty()) {
                            isShowingBigramPredictions = true
                            pendingSuggestions = displayPredictions
                            swipeKeyboardView?.updateSuggestions(displayPredictions)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun clearBigramPredictions() {
        if (isShowingBigramPredictions) {
            isShowingBigramPredictions = false
            pendingSuggestions = emptyList()
            swipeKeyboardView?.clearSuggestions()
        }
    }

    private fun applyCapitalizationToSuggestions(
        suggestions: List<com.urik.keyboard.service.SpellingSuggestion>,
        isSentenceStart: Boolean = false,
    ): List<String> {
        currentRawSuggestions = suggestions
        var state = viewModel.state.value
        if (isCurrentWordManualShifted && !state.isShiftPressed && !state.isCapsLockOn) {
            state = state.copy(isShiftPressed = true, isAutoShift = false)
        }
        return caseTransformer.applyCasingToSuggestions(suggestions, state, isSentenceStart)
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
            val filteredSuggestions =
                newWordState.suggestions.filterNot { suggestion ->
                    suggestion.word.equals(displayBuffer, ignoreCase = true)
                }

            val displaySuggestions = applyCapitalizationToSuggestions(filteredSuggestions, isCurrentWordAtSentenceStart)
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
                val actualCursorPos = safeGetCursorPosition()

                if (composingRegionStart != -1 && displayBuffer.isNotEmpty()) {
                    val expectedCursorRange = composingRegionStart..(composingRegionStart + displayBuffer.length)
                    if (actualCursorPos !in expectedCursorRange) {
                        invalidateComposingStateOnCursorJump()
                        return@withContext
                    }
                }

                isActivelyEditing = true

                recordWordUsage(suggestion)

                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.commitText("$suggestion ", 1)

                    val expectedNewPosition =
                        if (composingRegionStart != -1) {
                            composingRegionStart + suggestion.length + 1
                        } else {
                            actualCursorPos + suggestion.length + 1
                        }
                    selectionStateTracker.setExpectedPositionAfterOperation(expectedNewPosition)
                    lastKnownCursorPosition = expectedNewPosition

                    coordinateStateClear()
                    showBigramPredictions()

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                coordinateStateClear()
            }
        }
    }

    private suspend fun coordinateWordCompletion() {
        withContext(Dispatchers.Main) {
            try {
                isActivelyEditing = true
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

            postureDetector =
                com.urik.keyboard.service
                    .PostureDetector(this, serviceScope)
            postureDetector?.start()
            keyboardModeManager.initialize(serviceScope, postureDetector!!)

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
                    onShowInputMethodPicker = { showInputMethodPicker() },
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
            customKeyMappingService.initialize()

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
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }

            val actualWindow = window?.window
            if (actualWindow != null) {
                val layoutParams = actualWindow.attributes
                layoutParams.gravity = Gravity.BOTTOM
                actualWindow.attributes = layoutParams
                actualWindow.navigationBarColor = themeManager.currentTheme.value.colors.keyboardBackground
            }

            layoutManager.updateSplitGapPx(keyboardModeManager.currentMode.value.splitGapPx)

            val hasMultipleImes = inputMethodManager.enabledInputMethodList.size > 1
            layoutManager.updateHasMultipleImes(hasMultipleImes)

            val keyboardView = createSwipeKeyboardView() ?: return null

            window?.window?.context?.let { windowContext ->
                postureDetector?.attachToWindow(windowContext)
            }

            val adaptive =
                com.urik.keyboard.ui.keyboard.components.AdaptiveKeyboardContainer(this).apply {
                    setThemeManager(themeManager)
                    setKeyboardView(keyboardView)
                    setOnLayoutTransformListener { scaleFactor, offsetX ->
                        swipeDetector.updateLayoutTransform(scaleFactor, offsetX)
                    }
                    setOnModeToggleListener { mode ->
                        keyboardModeManager.setManualMode(mode)
                    }
                }

            adaptiveContainer = adaptive

            val currentModeConfig = keyboardModeManager.currentMode.value
            val currentPostureInfo = postureDetector?.postureInfo?.value
            adaptive.setModeConfig(currentModeConfig, currentPostureInfo?.hingeBounds)

            val panel =
                ClipboardPanel(this, themeManager).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                }
            clipboardPanel = panel

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
                        panel,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )

                    addView(
                        adaptive,
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
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

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
                    initialize(
                        layoutManager,
                        swipeDetector,
                        spellCheckManager,
                        wordLearningEngine,
                        themeManager,
                        languageManager,
                        emojiSearchManager,
                        recentEmojiProvider,
                    )
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
            layoutManager.setSwipeKeyboardView(swipeView)
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

                if (!requiresDirectCommit && displayBuffer.isNotEmpty()) {
                    coordinateWordCompletion()
                }

                currentInputConnection?.commitText(emoji, 1)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handles clipboard button click.
     *
     * Toggles embedded clipboard panel visibility. On first use, shows consent
     * screen. After consent, shows clipboard history. Panel is embedded within
     * the IME's inputView hierarchy to prevent window token conflicts.
     */
    private fun handleClipboardButtonClick() {
        val panel = clipboardPanel ?: return

        if (panel.isShowing) {
            dismissClipboardPanel()
            return
        }

        serviceScope.launch {
            try {
                val settings = settingsRepository.settings.first()

                if (!settings.clipboardEnabled) return@launch

                val keyboardHeight = adaptiveContainer?.height ?: return@launch
                adaptiveContainer?.visibility = View.GONE
                panel.layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        keyboardHeight,
                    )

                if (!settings.clipboardConsentShown) {
                    panel.showConsentScreen {
                        serviceScope.launch {
                            settingsRepository.updateClipboardConsentShown(true)
                            clipboardMonitorService.startMonitoring()
                            showClipboardContentInPanel(panel)
                        }
                    }
                } else {
                    showClipboardContentInPanel(panel)
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

    private fun dismissClipboardPanel() {
        clipboardPanel?.hide()
        adaptiveContainer?.visibility = View.VISIBLE
    }

    private suspend fun showClipboardContentInPanel(panel: ClipboardPanel) {
        val pinnedResult = clipboardRepository.getPinnedItems()
        val recentResult = clipboardRepository.getRecentItems()

        val pinnedItems = pinnedResult.getOrElse { emptyList() }
        val recentItems = recentResult.getOrElse { emptyList() }

        withContext(Dispatchers.Main) {
            panel.showClipboardContent(
                pinnedItems = pinnedItems,
                recentItems = recentItems,
                onItemClick = { content ->
                    handleClipboardItemPaste(content)
                    dismissClipboardPanel()
                },
                onPinToggle = { item ->
                    handleClipboardPinToggle(item)
                },
                onDelete = { item ->
                    handleClipboardItemDelete(item)
                },
                onDeleteAll = {
                    handleClipboardDeleteAll()
                },
            )
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

    private fun showInputMethodPicker() {
        try {
            inputMethodManager.showInputMethodPicker()
        } catch (_: Exception) {
        }
    }

    private fun handleClipboardItemPaste(content: String) {
        serviceScope.launch {
            try {
                currentInputConnection?.commitText(content, 1)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun refreshClipboardPanel() {
        val panel = clipboardPanel ?: return
        val pinnedResult = clipboardRepository.getPinnedItems()
        val recentResult = clipboardRepository.getRecentItems()

        val pinnedItems = pinnedResult.getOrElse { emptyList() }
        val recentItems = recentResult.getOrElse { emptyList() }

        withContext(Dispatchers.Main) {
            panel.refreshContent(pinnedItems, recentItems)
        }
    }

    private fun handleClipboardPinToggle(item: com.urik.keyboard.data.database.ClipboardItem) {
        serviceScope.launch {
            clipboardRepository.togglePin(item.id, !item.isPinned)
            refreshClipboardPanel()
        }
    }

    private fun handleClipboardItemDelete(item: com.urik.keyboard.data.database.ClipboardItem) {
        serviceScope.launch {
            clipboardRepository.deleteItem(item.id)
            refreshClipboardPanel()
        }
    }

    private fun handleClipboardDeleteAll() {
        serviceScope.launch {
            clipboardRepository.deleteAllUnpinned()
            refreshClipboardPanel()
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

                    val layoutChanged =
                        currentSettings.alternativeKeyboardLayout != newSettings.alternativeKeyboardLayout ||
                            currentSettings.showLanguageSwitchKey != newSettings.showLanguageSwitchKey

                    currentSettings = newSettings

                    val currentMode = keyboardModeManager.currentMode.value.mode
                    updateSwipeEnabledState(currentMode)

                    layoutManager.updateLongPressDuration(newSettings.longPressDuration)
                    layoutManager.updateLongPressPunctuationMode(newSettings.longPressPunctuationMode)

                    layoutManager.updateKeySize(newSettings.keySize)
                    layoutManager.updateSpaceBarSize(newSettings.spaceBarSize)
                    layoutManager.updateKeyLabelSize(newSettings.keyLabelSize)

                    swipeKeyboardView?.setCursorSpeed(newSettings.cursorSpeed)

                    layoutManager.updateHapticSettings(
                        newSettings.hapticFeedback,
                        newSettings.vibrationStrength,
                    )

                    layoutManager.updateClipboardEnabled(newSettings.clipboardEnabled)
                    layoutManager.updateShowLanguageSwitchKey(newSettings.showLanguageSwitchKey)

                    if (layoutChanged) {
                        repository.cleanup()
                        viewModel.reloadLayout()
                    }

                    withContext(Dispatchers.Main) {
                        updateSwipeKeyboard()
                    }
                }
            },
        )
    }

    private fun observeViewModel() {
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        observerJobs.add(
            serviceScope.launch {
                var prevShift = false
                var prevCapsLock = false
                var prevAutoShift = false
                viewModel.state.collect { state ->
                    updateSwipeKeyboard()

                    val shiftChanged = state.isShiftPressed != prevShift ||
                        state.isCapsLockOn != prevCapsLock ||
                        state.isAutoShift != prevAutoShift
                    prevShift = state.isShiftPressed
                    prevCapsLock = state.isCapsLockOn
                    prevAutoShift = state.isAutoShift

                    if (shiftChanged && currentRawSuggestions.isNotEmpty()) {
                        var effectiveState = state
                        if (isCurrentWordManualShifted && !state.isShiftPressed && !state.isCapsLockOn) {
                            effectiveState = state.copy(isShiftPressed = true, isAutoShift = false)
                        }
                        val recased = caseTransformer.applyCasingToSuggestions(
                            currentRawSuggestions, effectiveState, isCurrentWordAtSentenceStart
                        )
                        pendingSuggestions = recased
                        swipeKeyboardView?.updateSuggestions(recased)
                    }
                }
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

                    languages.forEach { lang ->
                        wordFrequencyRepository.preloadTopBigrams(lang)
                    }
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                themeManager.currentTheme.collect { theme ->
                    keyboardRootContainer?.setBackgroundColor(theme.colors.keyboardBackground)
                    window?.window?.navigationBarColor = theme.colors.keyboardBackground
                    updateSwipeKeyboard()
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                customKeyMappingService.mappings.collect { mappings ->
                    layoutManager.updateCustomKeyMappings(mappings)
                    updateSwipeKeyboard()
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                keyboardModeManager.currentMode.collect { config ->
                    val ic = currentInputConnection
                    ic?.beginBatchEdit()
                    try {
                        val postureInfo = postureDetector?.postureInfo?.value
                        adaptiveContainer?.setModeConfig(config, postureInfo?.hingeBounds)
                        layoutManager.updateSplitGapPx(config.splitGapPx)
                        updateSwipeEnabledState(config.mode)
                        updateSwipeKeyboard()
                    } finally {
                        ic?.endBatchEdit()
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
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return
            }

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

    private fun updateSwipeEnabledState(mode: KeyboardDisplayMode) {
        if (!::swipeDetector.isInitialized) return

        val userSettingEnabled = currentSettings.swipeEnabled
        val isSplitMode = mode == KeyboardDisplayMode.SPLIT

        val shouldEnableSwipe = userSettingEnabled && !isSplitMode

        swipeDetector.setSwipeEnabled(shouldEnableSwipe)
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        layoutManager.updateLongPressDuration(currentSettings.longPressDuration)
        layoutManager.updateLongPressPunctuationMode(currentSettings.longPressPunctuationMode)
        layoutManager.updateKeySize(currentSettings.keySize)
        layoutManager.updateSpaceBarSize(currentSettings.spaceBarSize)
        layoutManager.updateKeyLabelSize(currentSettings.keyLabelSize)

        layoutManager.updateHapticSettings(
            currentSettings.hapticFeedback,
            currentSettings.vibrationStrength,
        )

        layoutManager.updateClipboardEnabled(currentSettings.clipboardEnabled)
        layoutManager.updateShowLanguageSwitchKey(currentSettings.showLanguageSwitchKey)

        if (serviceJob.isCancelled) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
        }

        if (observerJobs.isEmpty()) {
            observeViewModel()
        }

        super.onStartInputView(info, restarting)

        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        isSecureField = SecureFieldDetector.isSecure(info)
        isDirectCommitField = SecureFieldDetector.isDirectCommit(info)
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
            viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            autofillStateTracker.drainPendingResponse()?.let { buffered ->
                if (!autofillStateTracker.isDismissed() && buffered.inlineSuggestions.isNotEmpty()) {
                    inflateAndDisplaySuggestions(buffered.inlineSuggestions)
                }
            }
        }
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
            clearBigramPredictions()

            if (swipeKeyboardView?.handleSearchInput(key) == true) {
                return
            }

            when (key) {
                is KeyboardKey.Character -> {
                    if (swipeKeyboardView?.clearAutofillIfShowing() == true) {
                        autofillStateTracker.dismiss()
                    }

                    val char = viewModel.getCharacterForInput(key)
                    if (key.type == KeyboardKey.KeyType.LETTER && displayBuffer.isEmpty()) {
                        val state = viewModel.state.value
                        isCurrentWordAtSentenceStart = state.isAutoShift
                        isCurrentWordManualShifted = state.isShiftPressed && !state.isAutoShift && !state.isCapsLockOn
                    }
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

                KeyboardKey.Spacer -> {}
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

            if (requiresDirectCommit) {
                currentInputConnection?.commitText(char, 1)
                return
            }

            if (displayBuffer.isNotEmpty() && wordState.isFromSwipe) {
                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.finishComposingText()
                    currentInputConnection?.commitText(" ", 1)

                    coordinateStateClear()
                    swipeSpaceManager.clearAutoSpaceFlag()

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
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
                val absoluteCursorPos = safeGetCursorPosition()
                val cursorOffsetInWord = (absoluteCursorPos - composingRegionStart).coerceIn(0, displayBuffer.length)
                val charsAfterCursorInWord = displayBuffer.length - cursorOffsetInWord

                val textBeforePart = safeGetTextBeforeCursor(cursorOffsetInWord).takeLast(cursorOffsetInWord)
                val textAfterPart = safeGetTextAfterCursor(charsAfterCursorInWord).take(charsAfterCursorInWord)
                val actualComposingText = textBeforePart + textAfterPart

                if (actualComposingText != displayBuffer) {
                    composingRegionStart = -1
                }
            }

            val cursorPosInWord =
                if (composingRegionStart != -1 && displayBuffer.isNotEmpty()) {
                    val absoluteCursorPos = safeGetCursorPosition()
                    CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, composingRegionStart, displayBuffer.length)
                } else {
                    displayBuffer.length
                }

            val isStartingNewWord = displayBuffer.isEmpty()

            displayBuffer =
                if (isStartingNewWord) {
                    char
                } else {
                    StringBuilder(displayBuffer)
                        .insert(cursorPosInWord, char)
                        .toString()
                }

            val newCursorPositionInText = cursorPosInWord + char.length

            val ic = currentInputConnection
            if (ic != null) {
                try {
                    ic.beginBatchEdit()

                    if (isStartingNewWord) {
                        composingRegionStart = safeGetCursorPosition()
                    }

                    ic.setComposingText(displayBuffer, 1)

                    if (composingRegionStart != -1) {
                        val absoluteCursorPosition = composingRegionStart + newCursorPositionInText
                        ic.setSelection(absoluteCursorPosition, absoluteCursorPosition)
                    }
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
                                                val displaySuggestions = applyCapitalizationToSuggestions(result.wordState.suggestions, isCurrentWordAtSentenceStart)
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
                lastSpaceTime = 0

                if (requiresDirectCommit) {
                    currentInputConnection?.commitText(char, 1)
                    return@launch
                }

                if (char.length == 1) {
                    val textBeforeCursor = safeGetTextBeforeCursor(1)
                    if (swipeSpaceManager.shouldRemoveSpaceForPunctuation(char.single(), textBeforeCursor)) {
                        currentInputConnection?.deleteSurroundingText(1, 0)
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
                    currentInputConnection?.beginBatchEdit()
                    try {
                        confirmAndLearnWord()
                        currentInputConnection?.commitText(char, 1)

                        if (char.length == 1) {
                            val singleChar = char.single()
                            if (isSentenceEndingPunctuation(singleChar) && !requiresDirectCommit) {
                                viewModel.disableCapsLockAfterPunctuation()
                                val textBefore = safeGetTextBeforeCursor(50)
                                viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
                            }
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
                                            wordState.buffer,
                                            InputMethod.TYPED,
                                        )
                                        currentInputConnection?.finishComposingText()
                                        currentInputConnection?.commitText(char, 1)

                                        val singleChar = char.single()
                                        if (isSentenceEndingPunctuation(singleChar) && !requiresDirectCommit) {
                                            viewModel.disableCapsLockAfterPunctuation()
                                            val textAfter = safeGetTextBeforeCursor(50)
                                            viewModel.checkAndApplyAutoCapitalization(textAfter, currentSettings.autoCapitalizationEnabled)
                                        }

                                        coordinateStateClear()
                                        showBigramPredictions()
                                    } finally {
                                        currentInputConnection?.endBatchEdit()
                                    }
                                    return@launch
                                } else {
                                    spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                    pendingWordForLearning = wordState.buffer
                                    highlightCurrentWord()

                                    val suggestions = textInputProcessor.getSuggestions(wordState.normalizedBuffer)
                                    val displaySuggestions = applyCapitalizationToSuggestions(suggestions, isCurrentWordAtSentenceStart)
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
                    showBigramPredictions()
                    currentInputConnection?.commitText(char, 1)

                    if (char.length == 1) {
                        val singleChar = char.single()
                        if (isSentenceEndingPunctuation(singleChar) && !requiresDirectCommit) {
                            viewModel.disableCapsLockAfterPunctuation()
                            val textBefore = safeGetTextBeforeCursor(50)
                            viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
                        }
                    }
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
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
            clearBigramPredictions()

            if (requiresDirectCommit) {
                currentInputConnection?.commitText(validatedWord, 1)
                return
            }

            if (displayBuffer.isNotEmpty()) {
                currentInputConnection?.beginBatchEdit()
                try {
                    autoCapitalizePronounI()
                    currentInputConnection?.commitText("$displayBuffer ", 1)

                    coordinateStateClear()

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            }

            if (spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                clearSpellConfirmationState()
            }

            if (validatedWord.isEmpty()) return

            val keyboardState = viewModel.state.value
            val isSentenceStart = keyboardState.isAutoShift
            val isManualShifted = keyboardState.isShiftPressed && !keyboardState.isAutoShift && !keyboardState.isCapsLockOn
            isCurrentWordAtSentenceStart = isSentenceStart
            isCurrentWordManualShifted = isManualShifted

            val effectiveState =
                if (isManualShifted) {
                    keyboardState.copy(isShiftPressed = true, isAutoShift = false)
                } else {
                    keyboardState
                }

            if (keyboardState.isShiftPressed && !keyboardState.isCapsLockOn) {
                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
            }

            serviceScope.launch {
                try {
                    val currentLanguage =
                        languageManager.currentLanguage.value
                            .split("-")
                            .first()

                    val learnedOriginalCase =
                        wordLearningEngine.getLearnedWordOriginalCase(validatedWord, currentLanguage)

                    val displayWord =
                        computeSwipeDisplayWord(
                            validatedWord = validatedWord,
                            learnedOriginalCase = learnedOriginalCase,
                            currentLanguage = currentLanguage,
                            keyboardState = effectiveState,
                            isSentenceStart = isSentenceStart,
                        )

                    val result =
                        textInputProcessor.processWordInput(validatedWord, InputMethod.SWIPED)

                    when (result) {
                        is ProcessingResult.Success -> {
                            withContext(Dispatchers.Main) {
                                commitPreviousSwipeAndInsertSpace()
                                currentInputConnection?.setComposingText(displayWord, 1)
                                displayBuffer = displayWord
                                coordinateStateTransition(result.wordState)

                                if (result.shouldHighlight) {
                                    spellConfirmationState =
                                        SpellConfirmationState.AWAITING_CONFIRMATION
                                    pendingWordForLearning = result.wordState.buffer
                                    highlightCurrentWord()
                                }
                            }
                        }

                        is ProcessingResult.Error -> {
                            withContext(Dispatchers.Main) {
                                commitPreviousSwipeAndInsertSpace()
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
                        val fallbackSuggestion =
                            com.urik.keyboard.service.SpellingSuggestion(
                                word = validatedWord,
                                confidence = 1.0,
                                ranking = 0,
                                source = "swipe",
                                preserveCase = false,
                            )
                        val fallbackDisplay = caseTransformer.applyCasing(fallbackSuggestion, effectiveState, isSentenceStart)
                        commitPreviousSwipeAndInsertSpace()
                        currentInputConnection?.setComposingText(fallbackDisplay, 1)
                        displayBuffer = fallbackDisplay
                        wordState =
                            WordState(
                                buffer = fallbackDisplay,
                                normalizedBuffer = validatedWord.lowercase(),
                                isFromSwipe = true,
                                graphemeCount = fallbackDisplay.length,
                                scriptCode = UScript.LATIN,
                            )
                    }
                }
            }
        } catch (_: Exception) {
            currentInputConnection?.setComposingText(validatedWord, 1)
        }
    }

    private fun computeSwipeDisplayWord(
        validatedWord: String,
        learnedOriginalCase: String?,
        currentLanguage: String,
        keyboardState: com.urik.keyboard.model.KeyboardState,
        isSentenceStart: Boolean = false,
    ): String {
        val normalizedWord = validatedWord.lowercase()

        if (currentLanguage == "en") {
            val englishPronounForm = getEnglishPronounIForm(normalizedWord)
            if (englishPronounForm != null) {
                return englishPronounForm
            }
        }

        val wordToUse = learnedOriginalCase ?: validatedWord
        val preserveCase = learnedOriginalCase != null

        val suggestion =
            com.urik.keyboard.service.SpellingSuggestion(
                word = wordToUse,
                confidence = 1.0,
                ranking = 0,
                source = if (preserveCase) "learned" else "swipe",
                preserveCase = preserveCase,
            )

        return caseTransformer.applyCasing(suggestion, keyboardState, isSentenceStart)
    }

    private fun getEnglishPronounIForm(normalizedWord: String): String? =
        when (normalizedWord) {
            "i" -> "I"
            "i'm" -> "I'm"
            "i'll" -> "I'll"
            "i've" -> "I've"
            "i'd" -> "I'd"
            else -> null
        }

    /**
     * Handles suggestion selection.
     *
     * @param suggestion Selected suggestion
     */
    private fun handleSuggestionSelected(suggestion: String) {
        serviceScope.launch {
            if (requiresDirectCommit) {
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

            KeyboardKey.ActionType.LANGUAGE_SWITCH -> {
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
            if (!requiresDirectCommit && displayBuffer.isNotEmpty()) {
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
                }
            }

            coordinateStateClear()

            if (imeAction == EditorInfo.IME_ACTION_NONE) {
                val textBefore = safeGetTextBeforeCursor(50)
                viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
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
            val actualCursorPos = safeGetCursorPosition()

            if (displayBuffer.isNotEmpty() && composingRegionStart != -1) {
                val expectedCursorRange = composingRegionStart..(composingRegionStart + displayBuffer.length)
                if (actualCursorPos !in expectedCursorRange) {
                    invalidateComposingStateOnCursorJump()
                }
            }

            if (lastKnownCursorPosition != -1 && actualCursorPos != -1) {
                val cursorDrift = kotlin.math.abs(actualCursorPos - lastKnownCursorPosition)
                if (cursorDrift > KeyboardConstants.SelectionTrackingConstants.NON_SEQUENTIAL_JUMP_THRESHOLD) {
                    invalidateComposingStateOnCursorJump()
                }
            }

            val selectedText = currentInputConnection?.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                currentInputConnection?.commitText("", 1)
                coordinateStateClear()
                return
            }

            if (isDirectCommitField) {
                val textBeforeCursor = safeGetTextBeforeCursor(1)
                val handled = textBeforeCursor.isNotEmpty() &&
                    (currentInputConnection?.deleteSurroundingText(
                        BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor), 0,
                    ) ?: false)
                if (!handled) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                return
            }

            if (displayBuffer.isEmpty()) {
                val textBeforeCursor = safeGetTextBeforeCursor(1)
                if (textBeforeCursor.isEmpty()) {
                    if (isAcceleratedDeletion) {
                        layoutManager.forceStopAcceleratedBackspace()
                    }
                    return
                }
            }

            if (displayBuffer.isNotEmpty() && wordState.isFromSwipe) {
                currentInputConnection?.setComposingText("", 1)
                coordinateStateClear()
                return
            }

            val textBeforeCursor = safeGetTextBeforeCursor(KeyboardConstants.TextProcessingConstants.WORD_BOUNDARY_CONTEXT_LENGTH)

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

            if (displayBuffer.isNotEmpty() && composingRegionStart != -1) {
                val absoluteCursorPos = safeGetCursorPosition()
                val cursorOffsetInWord = (absoluteCursorPos - composingRegionStart).coerceIn(0, displayBuffer.length)
                val charsAfterCursorInWord = displayBuffer.length - cursorOffsetInWord

                val textBeforePart = safeGetTextBeforeCursor(cursorOffsetInWord).takeLast(cursorOffsetInWord)
                val textAfterPart = safeGetTextAfterCursor(charsAfterCursorInWord).take(charsAfterCursorInWord)
                val actualComposingText = textBeforePart + textAfterPart

                if (actualComposingText != displayBuffer) {
                    coordinateStateClear()
                    handleCommittedTextBackspace()
                    return
                }
            } else if (displayBuffer.isNotEmpty()) {
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
                val absoluteCursorPos = safeGetCursorPosition()

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

                    val previousLength = displayBuffer.length
                    displayBuffer = BackspaceUtils.deleteGraphemeClusterBeforePosition(displayBuffer, cursorPosInWord)
                    val graphemeDeleted = previousLength - displayBuffer.length
                    val newCursorPositionInText = cursorPosInWord - graphemeDeleted

                    if (shouldResetShift) {
                        viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                    }

                    if (displayBuffer.isNotEmpty()) {
                        val ic = currentInputConnection
                        if (ic != null) {
                            try {
                                ic.beginBatchEdit()
                                ic.setComposingText(displayBuffer, 1)

                                if (composingRegionStart != -1) {
                                    val absoluteCursorPosition = composingRegionStart + newCursorPositionInText
                                    ic.setSelection(absoluteCursorPosition, absoluteCursorPosition)
                                }
                            } finally {
                                ic.endBatchEdit()
                            }
                        }

                        if (!isAcceleratedDeletion && !isUrlOrEmailField) {
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
                                                                    applyCapitalizationToSuggestions(result.wordState.suggestions, isCurrentWordAtSentenceStart)
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
            val textBeforeCursor = safeGetTextBeforeCursor(KeyboardConstants.TextProcessingConstants.WORD_BOUNDARY_CONTEXT_LENGTH)

            if (textBeforeCursor.isNotEmpty()) {
                val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                val deletedChar = textBeforeCursor.lastOrNull()
                val cursorPositionBeforeDeletion = safeGetCursorPosition()

                if (deletedChar == '\n') {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        isActivelyEditing = true
                        currentInputConnection?.finishComposingText()
                        currentInputConnection?.deleteSurroundingText(graphemeLength, 0)
                        coordinateStateClear()
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }
                    return
                }

                currentInputConnection?.beginBatchEdit()
                try {
                    isActivelyEditing = true
                    currentInputConnection?.finishComposingText()
                    currentInputConnection?.deleteSurroundingText(graphemeLength, 0)

                    val expectedNewPosition = cursorPositionBeforeDeletion - graphemeLength
                    selectionStateTracker.setExpectedPositionAfterOperation(expectedNewPosition)
                    lastKnownCursorPosition = expectedNewPosition

                    if (deletedChar != null) {
                        val shouldResetShift =
                            if (isSentenceEndingPunctuation(deletedChar)) {
                                true
                            } else if (deletedChar.isWhitespace()) {
                                val trimmed = textBeforeCursor.dropLast(graphemeLength).trimEnd()
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

                    if (!isAcceleratedDeletion && !isUrlOrEmailField) {
                        val remainingText = textBeforeCursor.dropLast(graphemeLength)

                        if (remainingText.isNotEmpty() && remainingText.last() == '\n') {
                            coordinateStateClear()
                            return
                        }

                        val composingRegion =
                            calculateParagraphBoundedComposingRegion(
                                remainingText,
                                expectedNewPosition,
                            )

                        if (composingRegion != null) {
                            val (wordStart, wordEnd, word) = composingRegion

                            val actualCursorPos = safeGetCursorPosition()
                            if (CursorEditingUtils.shouldAbortRecomposition(
                                    expectedCursorPosition = expectedNewPosition,
                                    actualCursorPosition = actualCursorPos,
                                    expectedComposingStart = wordStart,
                                    actualComposingStart = composingRegionStart,
                                )
                            ) {
                                coordinateStateClear()
                                return
                            }

                            currentInputConnection?.setComposingRegion(wordStart, wordEnd)

                            displayBuffer = word
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
                                                                    applyCapitalizationToSuggestions(result.wordState.suggestions, isCurrentWordAtSentenceStart)
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

    private fun calculateParagraphBoundedComposingRegion(
        textBeforeCursor: String,
        cursorPosition: Int,
    ): Triple<Int, Int, String>? {
        if (textBeforeCursor.isEmpty()) return null

        val paragraphBoundary = textBeforeCursor.lastIndexOf('\n')
        val textInParagraph =
            if (paragraphBoundary >= 0) {
                textBeforeCursor.substring(paragraphBoundary + 1)
            } else {
                textBeforeCursor
            }

        if (textInParagraph.isEmpty()) return null

        val wordInfo = BackspaceUtils.extractWordBeforeCursor(textInParagraph) ?: return null
        val (word, _) = wordInfo

        if (word.isEmpty()) return null

        val wordStart = cursorPosition - word.length
        if (wordStart < 0) return null

        if (paragraphBoundary >= 0) {
            textBeforeCursor.length - textInParagraph.length
            val absoluteParagraphBoundary = cursorPosition - textInParagraph.length
            if (wordStart < absoluteParagraphBoundary) {
                return null
            }
        }

        return Triple(wordStart, cursorPosition, word)
    }

    /**
     * Handles space key press.
     */
    private fun handleSpace() {
        serviceScope.launch {
            try {
                if (requiresDirectCommit) {
                    currentInputConnection?.commitText(" ", 1)
                    return@launch
                }

                swipeSpaceManager.clearAutoSpaceFlag()

                if (displayBuffer.isNotEmpty()) {
                    val actualTextBefore = safeGetTextBeforeCursor(1)
                    val actualTextAfter = safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        clearInternalStateOnly()
                        currentInputConnection?.finishComposingText()
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
                        if (wordState.hasContent && !requiresDirectCommit) {
                            clearInternalStateOnly()
                            currentInputConnection?.finishComposingText()
                        }
                        currentInputConnection?.deleteSurroundingText(1, 0)
                        currentInputConnection?.commitText(". ", 1)

                        val textBefore =
                            safeGetTextBeforeCursor(50)
                        viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
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
                                isActivelyEditing = true
                                recordWordUsage(wordState.normalizedBuffer)
                                currentInputConnection?.beginBatchEdit()
                                try {
                                    autoCapitalizePronounI()
                                    currentInputConnection?.finishComposingText()
                                    currentInputConnection?.commitText(" ", 1)
                                    clearInternalStateOnly()
                                    showBigramPredictions()

                                    val textBefore =
                                        safeGetTextBeforeCursor(50)
                                    viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
                                } finally {
                                    currentInputConnection?.endBatchEdit()
                                }

                                return@launch
                            }

                            val suggestions =
                                textInputProcessor.getSuggestions(wordState.normalizedBuffer)

                            spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                            pendingWordForLearning = wordState.buffer

                            highlightCurrentWord()
                            val displaySuggestions = applyCapitalizationToSuggestions(suggestions, isCurrentWordAtSentenceStart)
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
                    currentInputConnection?.finishComposingText()
                    currentInputConnection?.commitText(" ", 1)
                    clearInternalStateOnly()
                    showBigramPredictions()

                    val textBefore = safeGetTextBeforeCursor(50)
                    viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } catch (_: Exception) {
                currentInputConnection?.finishComposingText()
                currentInputConnection?.commitText(" ", 1)
                clearInternalStateOnly()
            }
        }
    }

    private fun handleSpacebarCursorMove(distance: Int) {
        if (requiresDirectCommit || !currentSettings.spacebarCursorControl) {
            return
        }

        try {
            val currentSelection = safeGetCursorPosition()
            val newPosition = (currentSelection + distance).coerceAtLeast(0)
            currentInputConnection?.setSelection(newPosition, newPosition)
        } catch (_: Exception) {
        }
    }

    private fun handleBackspaceSwipeDelete() {
        if (requiresDirectCommit || !currentSettings.backspaceSwipeDelete) {
            return
        }

        try {
            isActivelyEditing = true

            if (displayBuffer.isNotEmpty()) {
                val currentText = safeGetTextBeforeCursor(displayBuffer.length + 10)
                val expectedComposingText =
                    if (currentText.length >= displayBuffer.length) {
                        currentText.substring(maxOf(0, currentText.length - displayBuffer.length))
                    } else {
                        ""
                    }

                if (expectedComposingText == displayBuffer) {
                    currentInputConnection?.beginBatchEdit()
                    try {
                        currentInputConnection?.setComposingText("", 1)
                        coordinateStateClear()
                    } finally {
                        currentInputConnection?.endBatchEdit()
                    }
                    return
                } else {
                    coordinateStateClear()
                }
            }

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
                    val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(textBeforeCursor, word.length)
                    val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)
                    currentInputConnection?.deleteSurroundingText(deleteLength, 0)
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
                    val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(textBeforePunctuation, word.length)
                    val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)
                    currentInputConnection?.deleteSurroundingText(deleteLength + trailingPunctuationCount, 0)
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

        if (::layoutManager.isInitialized) {
            layoutManager.forceStopAcceleratedBackspace()
        }

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        suggestionDebounceJob?.cancel()
        swipeKeyboardView?.hideEmojiPicker()
        dismissClipboardPanel()

        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        if (displayBuffer.isNotEmpty() && !requiresDirectCommit) {
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

        autofillStateTracker.scheduleClear(serviceScope) {
            swipeKeyboardView?.forceClearAllSuggestions()
        }
    }

    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)

        if (::layoutManager.isInitialized) {
            layoutManager.forceStopAcceleratedBackspace()
        }

        autofillStateTracker.cancelPendingClear()
        autofillStateTracker.onFieldChanged(
            inputType = attribute?.inputType ?: 0,
            imeOptions = attribute?.imeOptions ?: 0,
            fieldId = attribute?.fieldId ?: 0,
            packageHash = attribute?.packageName?.hashCode() ?: 0,
        )
        selectionStateTracker.reset()
        coordinateStateClear()

        lastSpaceTime = 0
        lastShiftTime = 0
        lastCommittedWord = ""
        lastKnownCursorPosition = -1

        isSecureField = SecureFieldDetector.isSecure(attribute)
        isDirectCommitField = SecureFieldDetector.isDirectCommit(attribute)
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
            viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()

        if (::layoutManager.isInitialized) {
            layoutManager.forceStopAcceleratedBackspace()
        }

        suggestionDebounceJob?.cancel()
        swipeKeyboardView?.hideEmojiPicker()
        dismissClipboardPanel()

        if (displayBuffer.isNotEmpty() && !requiresDirectCommit) {
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

        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
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

        if (requiresDirectCommit) return

        val selectionResult =
            selectionStateTracker.updateSelection(
                newSelStart = newSelStart,
                newSelEnd = newSelEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd,
            )

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
                invalidateComposingStateOnCursorJump()
            }

            if (!isUrlOrEmailField) {
                viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
            }
        }

        if (isUrlOrEmailField) return

        if (selectionResult is SelectionChangeResult.NonSequentialJump) {
            if (displayBuffer.isNotEmpty() || wordState.hasContent) {
                invalidateComposingStateOnCursorJump()
            }
            lastKnownCursorPosition = newSelStart
            if (newSelStart == newSelEnd) {
                attemptRecompositionAtCursor(newSelStart)
            }
            return
        }

        val hasComposingText = (candidatesStart != -1 && candidatesEnd != -1)
        val cursorInComposingRegion =
            hasComposingText &&
                newSelStart >= candidatesStart &&
                newSelStart <= candidatesEnd &&
                newSelEnd >= candidatesStart &&
                newSelEnd <= candidatesEnd

        if (isActivelyEditing) {
            isActivelyEditing = false
            lastKnownCursorPosition = newSelStart
            return
        }

        if (selectionResult.requiresStateInvalidation()) {
            invalidateComposingStateOnCursorJump()
            lastKnownCursorPosition = newSelStart
            return
        }

        if (hasComposingText && !cursorInComposingRegion) {
            invalidateComposingStateOnCursorJump()
            lastKnownCursorPosition = newSelStart
            return
        }

        if (!hasComposingText && (wordState.hasContent || displayBuffer.isNotEmpty())) {
            invalidateComposingStateOnCursorJump()
            lastKnownCursorPosition = newSelStart
            return
        }

        lastKnownCursorPosition = newSelStart

        if (!hasComposingText && !isActivelyEditing && newSelStart == newSelEnd) {
            attemptRecompositionAtCursor(newSelStart)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        val currentDensity = resources.displayMetrics.density

        if (lastDisplayDensity != 0f && lastDisplayDensity != currentDensity) {
            layoutManager.onDensityChanged()
            swipeDetector.updateDisplayMetrics(currentDensity)
            swipeKeyboardView?.let { view ->
                view.updateDensity()
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

    /**
     * Creates inline autofill suggestion request for password managers (Android 11+).
     *
     * Called by Android when autofill service needs to display suggestions inline.
     * Returns specs defining visual constraints for autofill chips.
     *
     * @param uiExtras Bundle with UI extras from system
     * @return InlineSuggestionsRequest with styling specs, or null if SDK < R
     */
    @Suppress("NewApi")
    @SuppressLint("RestrictedApi")
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val theme = themeManager.currentTheme.value
        val density = resources.displayMetrics.density

        val keyHeight = resources.getDimensionPixelSize(R.dimen.key_height)
        val suggestionTextSize = (keyHeight * 0.30f / density).coerceIn(13f, 16f)

        val chipPadding = (8 * density).toInt()

        val stylesBuilder = UiVersions.newStylesBuilder()
        val style =
            InlineSuggestionUi
                .newStyleBuilder()
                .setSingleIconChipStyle(
                    ViewStyle
                        .Builder()
                        .setBackgroundColor(theme.colors.suggestionBarBackground)
                        .setPadding(0, 0, 0, 0)
                        .build(),
                ).setChipStyle(
                    ViewStyle
                        .Builder()
                        .setBackgroundColor(theme.colors.suggestionBarBackground)
                        .setPadding(chipPadding, 0, chipPadding, 0)
                        .build(),
                ).setTitleStyle(
                    TextViewStyle
                        .Builder()
                        .setTextColor(theme.colors.suggestionText)
                        .setTextSize(suggestionTextSize)
                        .build(),
                ).setSubtitleStyle(
                    TextViewStyle
                        .Builder()
                        .setTextColor(theme.colors.suggestionText)
                        .setTextSize(suggestionTextSize * 0.9f)
                        .build(),
                ).build()

        stylesBuilder.addStyle(style)
        val stylesBundle = stylesBuilder.build()

        val specs = mutableListOf<InlinePresentationSpec>()
        for (i in 0 until 4) {
            val minSize = Size((80 * density).toInt(), (40 * density).toInt())
            val maxSize = Size((400 * density).toInt(), (40 * density).toInt())

            val spec =
                InlinePresentationSpec
                    .Builder(minSize, maxSize)
                    .setStyle(stylesBundle)
                    .build()
            specs.add(spec)
        }

        val iconMinSize = Size((32 * density).toInt(), (32 * density).toInt())
        val iconMaxSize = Size((48 * density).toInt(), (40 * density).toInt())
        specs.add(
            InlinePresentationSpec
                .Builder(iconMinSize, iconMaxSize)
                .setStyle(stylesBundle)
                .build(),
        )

        return InlineSuggestionsRequest
            .Builder(specs)
            .setMaxSuggestionCount(5)
            .build()
    }

    /**
     * Receives inline autofill suggestions from password managers (Android 11+).
     *
     * Called when autofill service has suggestions ready. Inflates suggestion views
     * and displays them in suggestion bar, replacing spell check suggestions.
     *
     * @return true if handled, false otherwise
     */
    private val autofillStateTracker = AutofillStateTracker()

    @Suppress("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val suggestions = response.inlineSuggestions

        if (suggestions.isEmpty()) {
            serviceScope.launch(Dispatchers.Main) {
                swipeKeyboardView?.forceClearAllSuggestions()
            }
            return false
        }

        if (autofillStateTracker.isDismissed()) {
            return true
        }

        if (swipeKeyboardView == null) {
            autofillStateTracker.bufferResponse(response)
            return true
        }

        inflateAndDisplaySuggestions(suggestions)
        return true
    }

    @Suppress("NewApi")
    private fun inflateAndDisplaySuggestions(
        suggestions: List<InlineSuggestion>,
    ) {
        val density = resources.displayMetrics.density
        val size = Size((150 * density).toInt(), (40 * density).toInt())

        serviceScope.launch(Dispatchers.Main) {
            val views = mutableListOf<View>()
            for (suggestion in suggestions.take(5)) {
                val view = inflateSuggestionView(suggestion, size)
                if (view != null) views.add(view)
            }
            if (views.isNotEmpty()) {
                swipeKeyboardView?.updateInlineAutofillSuggestions(views, true)
            }
        }
    }

    @Suppress("NewApi")
    private suspend fun inflateSuggestionView(
        suggestion: InlineSuggestion,
        size: Size,
    ): View? = try {
        suspendCancellableCoroutine { continuation ->
            suggestion.inflate(this@UrikInputMethodService, size, mainExecutor) { view ->
                if (continuation.isActive) {
                    continuation.resume(view)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    override fun onDestroy() {
        wordFrequencyRepository.clearCache()
        autofillStateTracker.cleanup()

        serviceJob.cancel()
        serviceJob = SupervisorJob()
        serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        postureDetector?.stop()
        postureDetector = null

        coordinateStateClear()
        dismissClipboardPanel()
        clipboardPanel = null
        swipeKeyboardView = null
        adaptiveContainer = null

        if (::layoutManager.isInitialized) {
            layoutManager.cleanup()
        }

        cacheMemoryManager.cleanup()

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
