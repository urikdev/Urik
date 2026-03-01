package com.urik.keyboard

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.os.Build
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
import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.service.AutofillStateTracker
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.ClipboardMonitorService
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.InputMethod
import com.urik.keyboard.service.InputStateManager
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.OutputBridge
import com.urik.keyboard.service.ProcessingResult
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.SpellConfirmationState
import com.urik.keyboard.service.SuggestionPipeline
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.ViewCallback
import com.urik.keyboard.service.EnglishPronounI
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
import com.urik.keyboard.utils.UrlEmailDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Main input method service for the Urik keyboard.
 *
 * Delegates composing state to [InputStateManager], InputConnection operations
 * to [OutputBridge], and suggestion processing to [SuggestionPipeline].
 */
@AndroidEntryPoint
class UrikInputMethodService :
    InputMethodService(),
    LifecycleOwner {
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

    private lateinit var inputState: InputStateManager
    private lateinit var outputBridge: OutputBridge
    private lateinit var suggestionPipeline: SuggestionPipeline

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

    private var doubleTapSpaceThreshold: Long = DOUBLE_TAP_SPACE_THRESHOLD_MS
    private var doubleShiftThreshold: Long = DOUBLE_SHIFT_THRESHOLD_MS

    private var currentSettings: KeyboardSettings = KeyboardSettings()

    fun setAcceleratedDeletion(active: Boolean) {
        inputState.isAcceleratedDeletion = active
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private fun clearSecureFieldState() {
        inputState.clearInternalStateOnly()
        outputBridge.finishComposingText()

        spellCheckManager.clearCaches()
        spellCheckManager.clearBlacklist()
        textInputProcessor.clearCaches()
        wordLearningEngine.clearCurrentLanguageCache()

        inputState.lastSpaceTime = 0
        inputState.lastShiftTime = 0
    }

    private fun isValidTextInput(text: String): Boolean = CursorEditingUtils.isValidTextInput(text)

    private fun isAlphaNumericInput(char: String): Boolean {
        if (char.isEmpty()) return false

        if (inputState.displayBuffer.isNotEmpty() && char.all { it.isDigit() }) {
            return true
        }

        return isValidTextInput(char)
    }

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

    private fun isSentenceEndingPunctuation(char: Char): Boolean = UCharacter.hasBinaryProperty(char.code, UProperty.S_TERM)

    private fun coordinateStateClear() {
        outputBridge.coordinateStateClear()
    }

    private fun invalidateComposingStateOnCursorJump() {
        outputBridge.invalidateComposingStateOnCursorJump()
    }

    private fun checkAutoCapitalization(textBefore: String) {
        viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
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

    private fun initializeCoreComponents() {
        try {
            viewModel = KeyboardViewModel(repository, languageManager, themeManager)

            inputState =
                InputStateManager(
                    viewCallback =
                        object : ViewCallback {
                            override fun clearSuggestions() {
                                swipeKeyboardView?.clearSuggestions()
                            }

                            override fun updateSuggestions(suggestions: List<String>) {
                                swipeKeyboardView?.updateSuggestions(suggestions)
                            }
                        },
                    onShiftStateChanged = { pressed ->
                        viewModel.onEvent(KeyboardEvent.ShiftStateChanged(pressed))
                    },
                    isCapsLockOn = { viewModel.state.value.isCapsLockOn },
                    cancelDebounceJob = {
                        if (::suggestionPipeline.isInitialized) {
                            suggestionPipeline.cancelDebounceJob()
                        }
                    },
                )

            outputBridge =
                OutputBridge(
                    state = inputState,
                    swipeDetector = swipeDetector,
                    swipeSpaceManager = swipeSpaceManager,
                    icProvider = { currentInputConnection },
                )

            suggestionPipeline =
                SuggestionPipeline(
                    state = inputState,
                    outputBridge = outputBridge,
                    textInputProcessor = textInputProcessor,
                    spellCheckManager = spellCheckManager,
                    wordLearningEngine = wordLearningEngine,
                    wordFrequencyRepository = wordFrequencyRepository,
                    languageManager = languageManager,
                    caseTransformer = caseTransformer,
                    serviceScope = serviceScope,
                    showSuggestions = { currentSettings.showSuggestions },
                    effectiveSuggestionCount = { currentSettings.effectiveSuggestionCount },
                    getKeyboardState = { viewModel.state.value },
                    shouldAutoCapitalize = { text -> viewModel.shouldAutoCapitalize(text) },
                    currentLanguageProvider = { languageManager.currentLanguage.value },
                )

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

    private fun handleEmojiSelected(emoji: String) {
        serviceScope.launch {
            try {
                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                if (!inputState.requiresDirectCommit && inputState.displayBuffer.isNotEmpty()) {
                    suggestionPipeline.coordinateWordCompletion()
                }

                outputBridge.commitText(emoji, 1)
            } catch (_: Exception) {
            }
        }
    }

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
                outputBridge.commitText(content, 1)
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

                    val shiftChanged =
                        state.isShiftPressed != prevShift ||
                            state.isCapsLockOn != prevCapsLock ||
                            state.isAutoShift != prevAutoShift
                    prevShift = state.isShiftPressed
                    prevCapsLock = state.isCapsLockOn
                    prevAutoShift = state.isAutoShift

                    if (shiftChanged && inputState.currentRawSuggestions.isNotEmpty()) {
                        var effectiveState = state
                        if (inputState.isCurrentWordManualShifted && !state.isShiftPressed && !state.isCapsLockOn) {
                            effectiveState = state.copy(isShiftPressed = true, isAutoShift = false)
                        }
                        val recased =
                            caseTransformer.applyCasingToSuggestions(
                                inputState.currentRawSuggestions,
                                effectiveState,
                                inputState.isCurrentWordAtSentenceStart,
                            )
                        inputState.pendingSuggestions = recased
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
                    swipeDetector.updateCurrentLanguage(detectedLanguage.split("-").first())
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.activeLanguages.collect { languages ->
                    layoutManager.updateActiveLanguages(languages)
                    updateSwipeKeyboard()

                    languages.forEach { lang ->
                        wordFrequencyRepository.preloadTopBigrams(lang)
                    }
                }
            },
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.effectiveDictionaryLanguages.collect { languages ->
                    swipeDetector.updateActiveLanguages(languages)
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
                    outputBridge.beginBatchEdit()
                    try {
                        val postureInfo = postureDetector?.postureInfo?.value
                        adaptiveContainer?.setModeConfig(config, postureInfo?.hingeBounds)
                        layoutManager.updateSplitGapPx(config.splitGapPx)
                        updateSwipeEnabledState(config.mode)
                        updateSwipeKeyboard()
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                }
            },
        )

        observeSettings()
    }

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

        inputState.isSecureField = SecureFieldDetector.isSecure(info)
        inputState.isDirectCommitField = SecureFieldDetector.isDirectCommit(info)
        inputState.currentInputAction = ActionDetector.detectAction(info)

        val inputType = info?.inputType ?: 0
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        inputState.isUrlOrEmailField = variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        val targetMode = KeyboardModeUtils.determineTargetMode(info, viewModel.state.value.currentMode)
        if (targetMode != viewModel.state.value.currentMode) {
            viewModel.onEvent(KeyboardEvent.ModeChanged(targetMode))
        }

        if (inputState.isSecureField) {
            clearSecureFieldState()
        } else if (!inputState.isUrlOrEmailField) {
            if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
                coordinateStateClear()
            }

            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        } else {
            if (inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            try {
                outputBridge.finishComposingText()
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

    private fun updateKeyboardForCurrentAction() {
        viewModel.updateActionType(inputState.currentInputAction)
    }

    private fun handleKeyPress(key: KeyboardKey) {
        try {
            inputState.clearBigramPredictions()

            if (swipeKeyboardView?.handleSearchInput(key) == true) {
                return
            }

            when (key) {
                is KeyboardKey.Character -> {
                    if (swipeKeyboardView?.clearAutofillIfShowing() == true) {
                        autofillStateTracker.dismiss()
                    }

                    val char = viewModel.getCharacterForInput(key)
                    if (key.type == KeyboardKey.KeyType.LETTER && inputState.displayBuffer.isEmpty()) {
                        val state = viewModel.state.value
                        inputState.isCurrentWordAtSentenceStart = state.isAutoShift
                        inputState.isCurrentWordManualShifted = state.isShiftPressed && !state.isAutoShift && !state.isCapsLockOn
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

    private fun handleLetterInput(char: String) {
        try {
            inputState.lastSpaceTime = 0

            if (inputState.requiresDirectCommit) {
                outputBridge.commitText(char, 1)
                return
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.wordState.isFromSwipe) {
                outputBridge.beginBatchEdit()
                try {
                    outputBridge.finishComposingText()
                    outputBridge.commitText(" ", 1)

                    coordinateStateClear()
                    swipeSpaceManager.clearAutoSpaceFlag()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            }

            if (inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                outputBridge.clearSpellConfirmationState()
            }

            inputState.isActivelyEditing = true

            if (inputState.composingRegionStart != -1 && inputState.displayBuffer.isNotEmpty()) {
                val absoluteCursorPos = outputBridge.safeGetCursorPosition()
                val cursorOffsetInWord = (absoluteCursorPos - inputState.composingRegionStart).coerceIn(0, inputState.displayBuffer.length)
                val charsAfterCursorInWord = inputState.displayBuffer.length - cursorOffsetInWord

                val textBeforePart = outputBridge.safeGetTextBeforeCursor(cursorOffsetInWord).takeLast(cursorOffsetInWord)
                val textAfterPart = outputBridge.safeGetTextAfterCursor(charsAfterCursorInWord).take(charsAfterCursorInWord)
                val actualComposingText = textBeforePart + textAfterPart

                if (actualComposingText != inputState.displayBuffer) {
                    inputState.composingRegionStart = -1
                }
            }

            val cursorPosInWord =
                if (inputState.composingRegionStart != -1 && inputState.displayBuffer.isNotEmpty()) {
                    val absoluteCursorPos = outputBridge.safeGetCursorPosition()
                    CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, inputState.composingRegionStart, inputState.displayBuffer.length)
                } else {
                    inputState.displayBuffer.length
                }

            val isStartingNewWord = inputState.displayBuffer.isEmpty()

            inputState.displayBuffer =
                if (isStartingNewWord) {
                    char
                } else {
                    StringBuilder(inputState.displayBuffer)
                        .insert(cursorPosInWord, char)
                        .toString()
                }

            val newCursorPositionInText = cursorPosInWord + char.length

            if (isStartingNewWord) {
                inputState.composingRegionStart = outputBridge.safeGetCursorPosition()
            }

            val needsCursorRepositioning =
                inputState.composingRegionStart != -1 &&
                    newCursorPositionInText != inputState.displayBuffer.length

            if (needsCursorRepositioning) {
                outputBridge.beginBatchEdit()
                try {
                    outputBridge.setComposingText(inputState.displayBuffer, 1)
                    inputState.composingReassertionCount = 0
                    val absoluteCursorPosition = inputState.composingRegionStart + newCursorPositionInText
                    outputBridge.setSelection(absoluteCursorPosition, absoluteCursorPosition)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } else {
                outputBridge.setComposingText(inputState.displayBuffer, 1)
                inputState.composingReassertionCount = 0
            }

            inputState.wordState =
                inputState.wordState.copy(
                    buffer = inputState.displayBuffer,
                    graphemeCount = inputState.displayBuffer.length,
                )

            if (inputState.isUrlOrEmailField) {
                return
            }

            suggestionPipeline.requestSuggestions(
                buffer = inputState.displayBuffer,
                inputMethod = InputMethod.TYPED,
                isCharacterInput = true,
                char = char,
            )
        } catch (_: Exception) {
            outputBridge.commitText(char, 1)
        }
    }

    private fun handleNonLetterInput(char: String) {
        if (inputState.requiresDirectCommit) {
            outputBridge.commitText(char, 1)
            return
        }
        serviceScope.launch {
            try {
                inputState.lastSpaceTime = 0

                if (char.length == 1) {
                    val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(1)
                    if (swipeSpaceManager.shouldRemoveSpaceForPunctuation(char.single(), textBeforeCursor)) {
                        outputBridge.deleteSurroundingText(1, 0)
                    }
                }

                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    outputBridge.beginBatchEdit()
                    try {
                        suggestionPipeline.confirmAndLearnWord(::checkAutoCapitalization)
                        outputBridge.commitText(char, 1)

                        if (char.length == 1) {
                            val singleChar = char.single()
                            if (isSentenceEndingPunctuation(singleChar) && !inputState.requiresDirectCommit) {
                                viewModel.disableCapsLockAfterPunctuation()
                                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                checkAutoCapitalization(textBefore)
                            }
                        }
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return@launch
                }

                if (inputState.displayBuffer.isNotEmpty() && currentSettings.spellCheckEnabled) {
                    if (inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH) {
                        val textBefore = outputBridge.safeGetTextBeforeCursor(100)
                        val isUrlOrEmail =
                            UrlEmailDetector.isUrlOrEmailContext(
                                currentWord = inputState.displayBuffer,
                                textBeforeCursor = textBefore,
                                nextChar = char,
                            )

                        if (!isUrlOrEmail) {
                            suggestionPipeline.cancelDebounceJob()
                            val isValid = textInputProcessor.validateWord(inputState.displayBuffer)
                            if (!isValid) {
                                val isPunctuation =
                                    char.length == 1 && CursorEditingUtils.isPunctuation(char.single())

                                if (isPunctuation) {
                                    outputBridge.beginBatchEdit()
                                    try {
                                        outputBridge.autoCapitalizePronounI { languageManager.currentLanguage.value }
                                        suggestionPipeline.learnWordAndInvalidateCache(
                                            inputState.displayBuffer,
                                            InputMethod.TYPED,
                                        )
                                        outputBridge.finishComposingText()
                                        outputBridge.commitText(char, 1)

                                        val singleChar = char.single()
                                        if (isSentenceEndingPunctuation(singleChar) && !inputState.requiresDirectCommit) {
                                            viewModel.disableCapsLockAfterPunctuation()
                                            val textAfter = outputBridge.safeGetTextBeforeCursor(50)
                                            checkAutoCapitalization(textAfter)
                                        }

                                        coordinateStateClear()
                                        suggestionPipeline.showBigramPredictions()
                                    } finally {
                                        outputBridge.endBatchEdit()
                                    }
                                    return@launch
                                } else {
                                    inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                    inputState.pendingWordForLearning = inputState.displayBuffer
                                    outputBridge.highlightCurrentWord()

                                    val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                    val displaySuggestions = suggestionPipeline.storeAndCapitalizeSuggestions(suggestions, inputState.isCurrentWordAtSentenceStart)
                                    inputState.pendingSuggestions = displaySuggestions
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

                outputBridge.beginBatchEdit()
                try {
                    outputBridge.autoCapitalizePronounI { languageManager.currentLanguage.value }
                    suggestionPipeline.coordinateWordCompletion()
                    suggestionPipeline.showBigramPredictions()
                    outputBridge.commitText(char, 1)

                    if (char.length == 1) {
                        val singleChar = char.single()
                        if (isSentenceEndingPunctuation(singleChar) && !inputState.requiresDirectCommit) {
                            viewModel.disableCapsLockAfterPunctuation()
                            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                            checkAutoCapitalization(textBefore)
                        }
                    }
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleSwipeWord(validatedWord: String) {
        try {
            inputState.clearBigramPredictions()

            if (inputState.requiresDirectCommit) {
                outputBridge.commitText(validatedWord, 1)
                return
            }

            if (inputState.displayBuffer.isNotEmpty()) {
                outputBridge.beginBatchEdit()
                try {
                    outputBridge.autoCapitalizePronounI { languageManager.currentLanguage.value }
                    swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                    outputBridge.commitText("${inputState.displayBuffer} ", 1)

                    coordinateStateClear()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                outputBridge.clearSpellConfirmationState()
            }

            if (validatedWord.isEmpty()) return

            val keyboardState = viewModel.state.value
            val isSentenceStart = keyboardState.isAutoShift
            val isManualShifted = keyboardState.isShiftPressed && !keyboardState.isAutoShift && !keyboardState.isCapsLockOn
            inputState.isCurrentWordAtSentenceStart = isSentenceStart
            inputState.isCurrentWordManualShifted = isManualShifted

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
                                inputState.isActivelyEditing = true
                                outputBridge.commitPreviousSwipeAndInsertSpace()
                                outputBridge.setComposingText(displayWord, 1)
                                inputState.composingRegionStart = outputBridge.safeGetCursorPosition() - displayWord.length
                                inputState.displayBuffer = displayWord
                                suggestionPipeline.coordinateStateTransition(result.wordState)

                                if (result.shouldHighlight) {
                                    inputState.spellConfirmationState =
                                        SpellConfirmationState.AWAITING_CONFIRMATION
                                    inputState.pendingWordForLearning = result.wordState.buffer
                                    outputBridge.highlightCurrentWord()
                                }
                            }
                        }

                        is ProcessingResult.Error -> {
                            withContext(Dispatchers.Main) {
                                inputState.isActivelyEditing = true
                                outputBridge.commitPreviousSwipeAndInsertSpace()
                                outputBridge.setComposingText(displayWord, 1)
                                inputState.composingRegionStart = outputBridge.safeGetCursorPosition() - displayWord.length
                                inputState.displayBuffer = displayWord
                                inputState.wordState =
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
                        inputState.isActivelyEditing = true
                        outputBridge.commitPreviousSwipeAndInsertSpace()
                        outputBridge.setComposingText(fallbackDisplay, 1)
                        inputState.composingRegionStart = outputBridge.safeGetCursorPosition() - fallbackDisplay.length
                        inputState.displayBuffer = fallbackDisplay
                        inputState.wordState =
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
            outputBridge.setComposingText(validatedWord, 1)
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
        EnglishPronounI.capitalize(normalizedWord)

    private fun handleSuggestionSelected(suggestion: String) {
        serviceScope.launch {
            if (inputState.requiresDirectCommit) {
                return@launch
            }

            suggestionPipeline.coordinateSuggestionSelection(suggestion, ::checkAutoCapitalization)
        }
    }

    private fun handleSuggestionRemoval(suggestion: String) {
        serviceScope.launch {
            try {
                textInputProcessor.removeSuggestion(suggestion)

                withContext(Dispatchers.Main) {
                    val currentSuggestions = inputState.pendingSuggestions.filter { it != suggestion }
                    inputState.pendingSuggestions = currentSuggestions
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
                val timeSinceLastShift = currentTime - inputState.lastShiftTime
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
                        inputState.lastShiftTime = 0
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
                        inputState.lastShiftTime = currentTime
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

    private suspend fun performInputAction(imeAction: Int) {
        try {
            if (!inputState.requiresDirectCommit && inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                } else {
                    suggestionPipeline.coordinateWordCompletion()
                }
            }

            inputState.isActivelyEditing = true

            when (imeAction) {
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_PREVIOUS,
                -> {
                    outputBridge.performEditorAction(imeAction)
                }

                else -> {
                    outputBridge.commitText("\n", 1)
                }
            }

            coordinateStateClear()

            if (KeyboardModeUtils.shouldResetToLettersOnEnter(
                    viewModel.state.value.currentMode,
                    currentInputEditorInfo,
                )
            ) {
                viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))
            }

            if (imeAction == EditorInfo.IME_ACTION_NONE) {
                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                checkAutoCapitalization(textBefore)
            }
        } catch (_: Exception) {
            coordinateStateClear()
        }
    }

    private fun handleBackspace() {
        try {
            val actualCursorPos = outputBridge.safeGetCursorPosition()

            if (inputState.displayBuffer.isNotEmpty() && inputState.composingRegionStart != -1) {
                val expectedCursorRange = inputState.composingRegionStart..(inputState.composingRegionStart + inputState.displayBuffer.length)
                if (actualCursorPos !in expectedCursorRange) {
                    invalidateComposingStateOnCursorJump()
                }
            }

            if (inputState.lastKnownCursorPosition != -1 && actualCursorPos != -1) {
                val cursorDrift = kotlin.math.abs(actualCursorPos - inputState.lastKnownCursorPosition)
                if (cursorDrift > NON_SEQUENTIAL_JUMP_THRESHOLD) {
                    invalidateComposingStateOnCursorJump()
                }
            }

            val selectedText = outputBridge.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                outputBridge.commitText("", 1)
                coordinateStateClear()
                return
            }

            if (inputState.isDirectCommitField) {
                val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(1)
                if (textBeforeCursor.isNotEmpty()) {
                    outputBridge.deleteSurroundingText(
                        BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor),
                        0,
                    )
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                return
            }

            if (inputState.displayBuffer.isEmpty()) {
                val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(1)
                if (textBeforeCursor.isEmpty()) {
                    if (inputState.isAcceleratedDeletion) {
                        layoutManager.forceStopAcceleratedBackspace()
                    }
                    return
                }
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.wordState.isFromSwipe) {
                outputBridge.setComposingText("", 1)
                coordinateStateClear()
                return
            }

            val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(WORD_BOUNDARY_CONTEXT_LENGTH)

            if (inputState.isSecureField) {
                if (textBeforeCursor.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                    outputBridge.deleteSurroundingText(graphemeLength, 0)
                }
                return
            }

            if (inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                    return
                }
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                inputState.clearSpellConfirmationFields()
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.composingRegionStart != -1) {
                val absoluteCursorPos = outputBridge.safeGetCursorPosition()
                val cursorOffsetInWord = (absoluteCursorPos - inputState.composingRegionStart).coerceIn(0, inputState.displayBuffer.length)
                val charsAfterCursorInWord = inputState.displayBuffer.length - cursorOffsetInWord

                val textBeforePart = outputBridge.safeGetTextBeforeCursor(cursorOffsetInWord).takeLast(cursorOffsetInWord)
                val textAfterPart = outputBridge.safeGetTextAfterCursor(charsAfterCursorInWord).take(charsAfterCursorInWord)
                val actualComposingText = textBeforePart + textAfterPart

                if (actualComposingText != inputState.displayBuffer) {
                    coordinateStateClear()
                    handleCommittedTextBackspace()
                    return
                }
            } else if (inputState.displayBuffer.isNotEmpty()) {
                val currentText = outputBridge.safeGetTextBeforeCursor(inputState.displayBuffer.length + 10)
                val expectedComposingText =
                    if (currentText.length >= inputState.displayBuffer.length) {
                        currentText.substring(maxOf(0, currentText.length - inputState.displayBuffer.length))
                    } else {
                        ""
                    }

                if (expectedComposingText != inputState.displayBuffer) {
                    coordinateStateClear()
                    handleCommittedTextBackspace()
                    return
                }
            }

            inputState.isActivelyEditing = true

            if (inputState.displayBuffer.isNotEmpty()) {
                val absoluteCursorPos = outputBridge.safeGetCursorPosition()

                val cursorPosInWord =
                    if (inputState.composingRegionStart != -1) {
                        CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, inputState.composingRegionStart, inputState.displayBuffer.length)
                    } else {
                        val potentialStart = absoluteCursorPos - inputState.displayBuffer.length
                        if (potentialStart >= 0) {
                            inputState.composingRegionStart = potentialStart
                            CursorEditingUtils.calculateCursorPositionInWord(absoluteCursorPos, inputState.composingRegionStart, inputState.displayBuffer.length)
                        } else {
                            inputState.displayBuffer.length
                        }
                    }

                if (cursorPosInWord == 0) {
                    outputBridge.beginBatchEdit()
                    try {
                        coordinateStateClear()
                        val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                        if (textBefore.isNotEmpty()) {
                            val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBefore)
                            outputBridge.deleteSurroundingText(graphemeLength, 0)
                        }
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return
                }

                if (cursorPosInWord > 0) {
                    val deletedChar = inputState.displayBuffer.getOrNull(cursorPosInWord - 1)
                    val shouldResetShift =
                        deletedChar != null &&
                            isSentenceEndingPunctuation(deletedChar) &&
                            viewModel.state.value.isShiftPressed &&
                            !viewModel.state.value.isCapsLockOn

                    val previousLength = inputState.displayBuffer.length
                    inputState.displayBuffer = BackspaceUtils.deleteGraphemeClusterBeforePosition(inputState.displayBuffer, cursorPosInWord)
                    val graphemeDeleted = previousLength - inputState.displayBuffer.length
                    val newCursorPositionInText = cursorPosInWord - graphemeDeleted

                    if (shouldResetShift) {
                        viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                    }

                    if (inputState.displayBuffer.isNotEmpty()) {
                        val needsCursorRepositioning =
                            inputState.composingRegionStart != -1 &&
                                newCursorPositionInText != inputState.displayBuffer.length

                        if (needsCursorRepositioning) {
                            outputBridge.beginBatchEdit()
                            try {
                                outputBridge.setComposingText(inputState.displayBuffer, 1)
                                val absoluteCursorPosition = inputState.composingRegionStart + newCursorPositionInText
                                outputBridge.setSelection(absoluteCursorPosition, absoluteCursorPosition)
                            } finally {
                                outputBridge.endBatchEdit()
                            }
                        } else {
                            outputBridge.setComposingText(inputState.displayBuffer, 1)
                        }

                        if (!inputState.isAcceleratedDeletion && !inputState.isUrlOrEmailField) {
                            suggestionPipeline.requestSuggestions(
                                buffer = inputState.displayBuffer,
                                inputMethod = InputMethod.TYPED,
                                isCharacterInput = false,
                            )
                        }
                    } else {
                        outputBridge.setComposingText("", 1)
                        coordinateStateClear()
                    }
                }
            } else {
                handleCommittedTextBackspace()
            }
        } catch (_: Exception) {
            try {
                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                if (textBefore.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBefore)
                    outputBridge.deleteSurroundingText(graphemeLength, 0)
                }
            } catch (_: Exception) {
            }
            coordinateStateClear()
        }
    }

    private fun handleCommittedTextBackspace() {
        try {
            val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(WORD_BOUNDARY_CONTEXT_LENGTH)

            if (textBeforeCursor.isNotEmpty()) {
                val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                val deletedChar = textBeforeCursor.lastOrNull()
                val cursorPositionBeforeDeletion = outputBridge.safeGetCursorPosition()

                if (deletedChar == '\n') {
                    outputBridge.beginBatchEdit()
                    try {
                        inputState.isActivelyEditing = true
                        outputBridge.finishComposingText()
                        outputBridge.deleteSurroundingText(graphemeLength, 0)
                        coordinateStateClear()
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return
                }

                outputBridge.beginBatchEdit()
                try {
                    inputState.isActivelyEditing = true
                    outputBridge.finishComposingText()
                    outputBridge.deleteSurroundingText(graphemeLength, 0)

                    val expectedNewPosition = cursorPositionBeforeDeletion - graphemeLength
                    inputState.selectionStateTracker.setExpectedPositionAfterOperation(expectedNewPosition)
                    inputState.lastKnownCursorPosition = expectedNewPosition

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

                    if (!inputState.isAcceleratedDeletion && !inputState.isUrlOrEmailField) {
                        val remainingText = textBeforeCursor.dropLast(graphemeLength)

                        if (remainingText.isNotEmpty() && remainingText.last() == '\n') {
                            coordinateStateClear()
                            return
                        }

                        val composingRegion =
                            outputBridge.calculateParagraphBoundedComposingRegion(
                                remainingText,
                                expectedNewPosition,
                            )

                        if (composingRegion != null) {
                            val (wordStart, wordEnd, word) = composingRegion

                            val actualCursorPos = outputBridge.safeGetCursorPosition()
                            if (CursorEditingUtils.shouldAbortRecomposition(
                                    expectedCursorPosition = expectedNewPosition,
                                    actualCursorPosition = actualCursorPos,
                                    expectedComposingStart = wordStart,
                                    actualComposingStart = inputState.composingRegionStart,
                                )
                            ) {
                                coordinateStateClear()
                                return
                            }

                            outputBridge.setComposingRegion(wordStart, wordEnd)

                            inputState.displayBuffer = word
                            inputState.composingRegionStart = wordStart

                            suggestionPipeline.requestSuggestions(
                                buffer = word,
                                inputMethod = InputMethod.TYPED,
                                isCharacterInput = false,
                            )
                        } else {
                            coordinateStateClear()
                        }
                    }
                } finally {
                    outputBridge.endBatchEdit()
                }
            }
        } catch (_: Exception) {
            coordinateStateClear()
        }
    }

    private fun handleSpace() {
        serviceScope.launch {
            try {
                if (inputState.requiresDirectCommit) {
                    outputBridge.commitText(" ", 1)
                    return@launch
                }

                swipeSpaceManager.clearAutoSpaceFlag()

                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        inputState.clearInternalStateOnly()
                        outputBridge.finishComposingText()
                    }
                }

                val currentTime = System.currentTimeMillis()
                val timeSinceLastSpace = currentTime - inputState.lastSpaceTime

                if (timeSinceLastSpace <= doubleTapSpaceThreshold &&
                    inputState.spellConfirmationState == SpellConfirmationState.NORMAL &&
                    currentSettings.doubleSpacePeriod
                ) {
                    outputBridge.beginBatchEdit()
                    try {
                        if (inputState.wordState.hasContent && !inputState.requiresDirectCommit) {
                            inputState.clearInternalStateOnly()
                            outputBridge.finishComposingText()
                        }
                        outputBridge.deleteSurroundingText(1, 0)
                        outputBridge.commitText(". ", 1)

                        val textBefore =
                            outputBridge.safeGetTextBeforeCursor(50)
                        checkAutoCapitalization(textBefore)
                    } finally {
                        outputBridge.endBatchEdit()
                    }

                    inputState.lastSpaceTime = 0
                    return@launch
                }

                inputState.lastSpaceTime = currentTime

                if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    suggestionPipeline.confirmAndLearnWord(::checkAutoCapitalization)
                    return@launch
                }

                if (inputState.displayBuffer.isNotEmpty() && currentSettings.spellCheckEnabled) {
                    if (inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH) {
                        val textBeforeForUrlCheck = outputBridge.safeGetTextBeforeCursor(100)
                        val isUrlOrEmail =
                            UrlEmailDetector.isUrlOrEmailContext(
                                currentWord = inputState.displayBuffer,
                                textBeforeCursor = textBeforeForUrlCheck,
                                nextChar = " ",
                            )

                        if (!isUrlOrEmail) {
                            suggestionPipeline.cancelDebounceJob()
                            val isValid = textInputProcessor.validateWord(inputState.displayBuffer)
                            if (isValid) {
                                inputState.isActivelyEditing = true
                                suggestionPipeline.recordWordUsage(inputState.displayBuffer)
                                outputBridge.beginBatchEdit()
                                try {
                                    outputBridge.autoCapitalizePronounI { languageManager.currentLanguage.value }
                                    swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()
                                    suggestionPipeline.showBigramPredictions()

                                    val textBefore =
                                        outputBridge.safeGetTextBeforeCursor(50)
                                    checkAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }

                                return@launch
                            }

                            val suggestions =
                                textInputProcessor.getSuggestions(inputState.displayBuffer)

                            inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                            inputState.pendingWordForLearning = inputState.displayBuffer

                            outputBridge.highlightCurrentWord()
                            val displaySuggestions = suggestionPipeline.storeAndCapitalizeSuggestions(suggestions, inputState.isCurrentWordAtSentenceStart)
                            inputState.pendingSuggestions = displaySuggestions
                            if (displaySuggestions.isNotEmpty()) {
                                swipeKeyboardView?.updateSuggestions(displaySuggestions)
                            } else {
                                swipeKeyboardView?.clearSuggestions()
                            }

                            return@launch
                        }
                    }
                }

                outputBridge.beginBatchEdit()
                try {
                    outputBridge.autoCapitalizePronounI { languageManager.currentLanguage.value }
                    if (inputState.displayBuffer.isNotEmpty()) swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                    outputBridge.finishComposingText()
                    outputBridge.commitText(" ", 1)
                    inputState.clearInternalStateOnly()
                    suggestionPipeline.showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (_: Exception) {
                outputBridge.finishComposingText()
                outputBridge.commitText(" ", 1)
                inputState.clearInternalStateOnly()
            }
        }
    }

    private fun handleSpacebarCursorMove(distance: Int) {
        if (inputState.requiresDirectCommit || !currentSettings.spacebarCursorControl) {
            return
        }

        try {
            val currentSelection = outputBridge.safeGetCursorPosition()
            val newPosition = (currentSelection + distance).coerceAtLeast(0)
            outputBridge.setSelection(newPosition, newPosition)
        } catch (_: Exception) {
        }
    }

    private fun handleBackspaceSwipeDelete() {
        if (inputState.requiresDirectCommit || !currentSettings.backspaceSwipeDelete) {
            return
        }

        try {
            inputState.isActivelyEditing = true

            if (inputState.displayBuffer.isNotEmpty()) {
                val currentText = outputBridge.safeGetTextBeforeCursor(inputState.displayBuffer.length + 10)
                val expectedComposingText =
                    if (currentText.length >= inputState.displayBuffer.length) {
                        currentText.substring(maxOf(0, currentText.length - inputState.displayBuffer.length))
                    } else {
                        ""
                    }

                if (expectedComposingText == inputState.displayBuffer) {
                    outputBridge.beginBatchEdit()
                    try {
                        outputBridge.setComposingText("", 1)
                        coordinateStateClear()
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return
                } else {
                    coordinateStateClear()
                }
            }

            val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(50)
            if (textBeforeCursor.isEmpty()) {
                return
            }

            val lastChar = textBeforeCursor.last()

            if (Character.isWhitespace(lastChar)) {
                outputBridge.deleteSurroundingText(1, 0)
                coordinateStateClear()
                return
            }

            if (Character.isLetterOrDigit(lastChar)) {
                val wordInfo = BackspaceUtils.extractWordBeforeCursor(textBeforeCursor)
                if (wordInfo != null) {
                    val (word, _) = wordInfo
                    val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(textBeforeCursor, word.length)
                    val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)
                    outputBridge.deleteSurroundingText(deleteLength, 0)
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
                    outputBridge.deleteSurroundingText(deleteLength + trailingPunctuationCount, 0)
                    coordinateStateClear()
                    return
                }
            }

            outputBridge.deleteSurroundingText(trailingPunctuationCount, 0)
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

        suggestionPipeline.cancelDebounceJob()
        swipeKeyboardView?.hideEmojiPicker()
        dismissClipboardPanel()

        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        if (inputState.displayBuffer.isNotEmpty() && !inputState.requiresDirectCommit) {
            val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
            val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

            if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                coordinateStateClear()
            } else {
                try {
                    inputState.isActivelyEditing = true
                    val wordToCommit = inputState.displayBuffer.ifEmpty { inputState.wordState.buffer }
                    if (wordToCommit.isNotEmpty()) {
                        outputBridge.finishComposingText()
                    }
                    coordinateStateClear()
                } catch (_: Exception) {
                    coordinateStateClear()
                }
            }
        }

        try {
            outputBridge.finishComposingText()
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
        inputState.selectionStateTracker.reset()
        coordinateStateClear()

        inputState.lastSpaceTime = 0
        inputState.lastShiftTime = 0
        inputState.lastCommittedWord = ""
        inputState.lastKnownCursorPosition = -1

        inputState.isSecureField = SecureFieldDetector.isSecure(attribute)
        inputState.isDirectCommitField = SecureFieldDetector.isDirectCommit(attribute)
        inputState.currentInputAction = ActionDetector.detectAction(attribute)

        val inputType = attribute?.inputType ?: 0
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        inputState.isUrlOrEmailField = variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        if (inputState.isSecureField) {
            clearSecureFieldState()
        } else if (!inputState.isUrlOrEmailField) {
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()

        if (::layoutManager.isInitialized) {
            layoutManager.forceStopAcceleratedBackspace()
        }

        suggestionPipeline.cancelDebounceJob()
        swipeKeyboardView?.hideEmojiPicker()
        dismissClipboardPanel()

        if (inputState.displayBuffer.isNotEmpty() && !inputState.requiresDirectCommit) {
            val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
            val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

            if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                coordinateStateClear()
            } else {
                try {
                    inputState.isActivelyEditing = true
                    val wordToCommit = inputState.displayBuffer.ifEmpty { inputState.wordState.buffer }
                    if (wordToCommit.isNotEmpty()) {
                        outputBridge.finishComposingText()
                    }
                    coordinateStateClear()
                } catch (_: Exception) {
                    coordinateStateClear()
                }
            }
        }

        try {
            outputBridge.finishComposingText()
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

        if (inputState.requiresDirectCommit) return

        val selectionResult =
            inputState.selectionStateTracker.updateSelection(
                newSelStart = newSelStart,
                newSelEnd = newSelEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd,
            )

        if (newSelStart == 0 && newSelEnd == 0) {
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            val textAfter = outputBridge.safeGetTextAfterCursor(50)

            if (CursorEditingUtils.shouldClearStateOnEmptyField(
                    newSelStart,
                    newSelEnd,
                    textBefore,
                    textAfter,
                    inputState.displayBuffer,
                    inputState.wordState.hasContent,
                )
            ) {
                invalidateComposingStateOnCursorJump()
            }

            if (!inputState.isUrlOrEmailField) {
                checkAutoCapitalization(textBefore)
            }
        }

        if (selectionResult is SelectionChangeResult.AppSelectionExtended) {
            if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
                inputState.clearInternalStateOnly()
                inputState.isActivelyEditing = false
            }
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        if (inputState.isUrlOrEmailField) return

        if (selectionResult is SelectionChangeResult.NonSequentialJump) {
            if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
                invalidateComposingStateOnCursorJump()
            }
            inputState.lastKnownCursorPosition = newSelStart
            if (newSelStart == newSelEnd) {
                outputBridge.attemptRecompositionAtCursor(newSelStart)
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

        if (inputState.isActivelyEditing) {
            inputState.isActivelyEditing = false
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        if (selectionResult.requiresStateInvalidation()) {
            if (inputState.displayBuffer.isNotEmpty() && outputBridge.reassertComposingRegion(newSelStart)) {
                inputState.lastKnownCursorPosition = newSelStart
                return
            }
            invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        if (hasComposingText && !cursorInComposingRegion) {
            invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        if (!hasComposingText && (inputState.wordState.hasContent || inputState.displayBuffer.isNotEmpty())) {
            if (inputState.displayBuffer.isNotEmpty() && outputBridge.reassertComposingRegion(newSelStart)) {
                inputState.lastKnownCursorPosition = newSelStart
                return
            }
            invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return
        }

        inputState.lastKnownCursorPosition = newSelStart

        if (!hasComposingText && !inputState.isActivelyEditing && newSelStart == newSelEnd) {
            outputBridge.attemptRecompositionAtCursor(newSelStart)
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
    private fun inflateAndDisplaySuggestions(suggestions: List<InlineSuggestion>) {
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
    ): View? =
        try {
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

    private companion object {
        const val DOUBLE_TAP_SPACE_THRESHOLD_MS = 250L
        const val DOUBLE_SHIFT_THRESHOLD_MS = 400L
        const val NON_SEQUENTIAL_JUMP_THRESHOLD = 5
        const val WORD_BOUNDARY_CONTEXT_LENGTH = 64
    }
}
