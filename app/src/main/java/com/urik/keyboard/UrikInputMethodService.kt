package com.urik.keyboard

import android.annotation.SuppressLint
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.lang.UScript
import android.icu.util.ULocale
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
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
import androidx.annotation.VisibleForTesting
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AutoCorrectionEngine
import com.urik.keyboard.service.AutocorrectDecision
import com.urik.keyboard.service.AutofillStateCoordinator
import com.urik.keyboard.service.AutofillStateTracker
import com.urik.keyboard.service.CandidateBarController
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.ClipboardActionCoordinator
import com.urik.keyboard.service.ClipboardMonitorService
import com.urik.keyboard.service.ClipboardPanelHost
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.EnglishPronounCorrection
import com.urik.keyboard.service.ImeStateCoordinator
import com.urik.keyboard.service.InputMethod
import com.urik.keyboard.service.InputStateManager
import com.urik.keyboard.service.KeyEventHandler
import com.urik.keyboard.service.KeyEventRouter
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.LastAutocorrection
import com.urik.keyboard.service.OutputBridge
import com.urik.keyboard.service.PostCommitReplacementState
import com.urik.keyboard.service.ProcessingResult
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.SpellConfirmationState
import com.urik.keyboard.service.SuggestionPipeline
import com.urik.keyboard.service.SuggestionPipelineHost
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.ViewCallback
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
import com.urik.keyboard.utils.KanaTransformUtils
import com.urik.keyboard.utils.KeyboardModeUtils
import com.urik.keyboard.utils.SecureFieldDetector
import com.urik.keyboard.utils.SelectionChangeResult
import com.urik.keyboard.utils.UrlEmailDetector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Main input method service for the Urik keyboard.
 *
 * Delegates composing state to [InputStateManager], InputConnection operations
 * to [OutputBridge], and suggestion processing to [SuggestionPipeline].
 */
@Suppress("LargeClass")
@AndroidEntryPoint
open class UrikInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    KeyEventHandler,
    ClipboardPanelHost,
    SuggestionPipelineHost {
    @Inject
    lateinit var repository: KeyboardRepository

    @Inject
    lateinit var swipeDetector: SwipeDetector

    @Inject
    lateinit var streamingScoringEngine: com.urik.keyboard.ui.keyboard.components.StreamingScoringEngine

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

    @Inject
    lateinit var scriptConverterRegistry: com.urik.keyboard.service.ScriptConverterRegistry

    @Inject
    lateinit var autoCorrectionEngine: AutoCorrectionEngine

    @Inject
    lateinit var keyEventRouter: KeyEventRouter

    private lateinit var viewModel: KeyboardViewModel
    private lateinit var layoutManager: KeyboardLayoutManager
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var postureDetector: com.urik.keyboard.service.PostureDetector? = null

    @VisibleForTesting
    internal lateinit var inputState: InputStateManager

    @VisibleForTesting
    internal lateinit var outputBridge: OutputBridge

    @VisibleForTesting
    internal lateinit var suggestionPipeline: SuggestionPipeline

    private val inputMethodManager: android.view.inputmethod.InputMethodManager by lazy {
        getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    }

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val observerJobs = mutableListOf<Job>()

    private var swipeKeyboardView: SwipeKeyboardView? = null
    private var filteredLayoutCache: KeyboardLayout? = null
    private var filteredLayoutCacheKey: Pair<KeyboardLayout?, Boolean>? = null
    private var adaptiveContainer: com.urik.keyboard.ui.keyboard.components.AdaptiveKeyboardContainer? = null
    private var keyboardRootContainer: LinearLayout? = null
    private var clipboardPanel: ClipboardPanel? = null
    private var lastDisplayDensity: Float = 0f
    private var lastKeyboardConfig: Int = android.content.res.Configuration.KEYBOARD_UNDEFINED

    private var doubleTapSpaceThreshold: Long = DOUBLE_TAP_SPACE_THRESHOLD_MS
    private var doubleShiftThreshold: Long = DOUBLE_SHIFT_THRESHOLD_MS

    private var currentSettings: KeyboardSettings = KeyboardSettings()

    private lateinit var autofillCoordinator: AutofillStateCoordinator
    private lateinit var clipboardCoordinator: ClipboardActionCoordinator

    private lateinit var candidateBarController: CandidateBarController
    private lateinit var imeStateCoordinator: ImeStateCoordinator

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun showSuggestions(): Boolean = currentSettings.showSuggestions
    override fun effectiveSuggestionCount(): Int = currentSettings.effectiveSuggestionCount
    override fun getKeyboardState(): KeyboardState = viewModel.state.value
    override fun shouldAutoCapitalize(text: String): Boolean = viewModel.shouldAutoCapitalize(text)
    override fun currentLanguage(): String = languageManager.currentLanguage.value

    private fun setAcceleratedDeletion(active: Boolean) {
        inputState.isAcceleratedDeletion = active
    }

    private fun clearSecureFieldState() = imeStateCoordinator.clearSecureFieldState()

    private fun isValidTextInput(text: String): Boolean = CursorEditingUtils.isValidTextInput(text)

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

    private fun isSentenceEndingPunctuation(char: Char): Boolean =
        UCharacter.hasBinaryProperty(char.code, UProperty.S_TERM)

    private fun coordinateStateClear() = imeStateCoordinator.coordinateStateClear()

    private fun invalidateComposingStateOnCursorJump() = imeStateCoordinator.invalidateComposingStateOnCursorJump()

    private fun checkAutoCapitalization(textBefore: String) {
        viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
    }

    private fun sendCharacterAsKeyEvents(char: String) {
        val ic = currentInputConnection ?: return
        val events = android.view.KeyCharacterMap.load(
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
        ).getEvents(char.toCharArray())
        if (events != null) {
            for (event in events) {
                ic.sendKeyEvent(event)
            }
        } else {
            ic.commitText(char, 1)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            lifecycleRegistry = LifecycleRegistry(this)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED

            lastDisplayDensity = resources.displayMetrics.density
            swipeDetector.updateDisplayMetrics(lastDisplayDensity)

            postureDetector = com.urik.keyboard.service.PostureDetector(this, serviceScope).also {
                it.start()
                keyboardModeManager.initialize(serviceScope, it)
            }

            initializeCoreComponents()

            serviceScope.launch {
                initializeServices()
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "onCreate")
            )
            throw e
        }
    }

    private fun initializeCoreComponents() {
        try {
            viewModel = KeyboardViewModel(repository, languageManager)

            inputState =
                InputStateManager(
                    viewCallback =
                        object : ViewCallback {
                            override fun clearSuggestions() {
                                candidateBarController.clearSuggestions()
                            }

                            override fun updateSuggestions(suggestions: List<String>) {
                                candidateBarController.updateSuggestions(suggestions)
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
                    }
                )

            outputBridge =
                OutputBridge(
                    state = inputState,
                    swipeDetector = swipeDetector,
                    swipeSpaceManager = swipeSpaceManager,
                    icProvider = { currentInputConnection },
                    keyEventSender = { keyCode ->
                        val ic = currentInputConnection
                        if (ic != null) {
                            val now = SystemClock.uptimeMillis()
                            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
                        }
                    },
                    keyCharEventSender = { char -> sendCharacterAsKeyEvents(char) }
                )

            candidateBarController = CandidateBarController(viewProvider = { swipeKeyboardView })

            imeStateCoordinator = ImeStateCoordinator(
                outputBridge = outputBridge,
                streamingScoringEngine = streamingScoringEngine,
                inputState = inputState,
                spellCheckManager = spellCheckManager,
                textInputProcessor = textInputProcessor,
                wordLearningEngine = wordLearningEngine
            )

            autofillCoordinator = AutofillStateCoordinator(
                tracker = AutofillStateTracker(),
                candidateBarController = candidateBarController,
                serviceScope = serviceScope,
                displaySuggestions = { suggestions -> inflateAndDisplaySuggestions(suggestions) }
            )

            clipboardCoordinator = ClipboardActionCoordinator(
                clipboardRepository = clipboardRepository,
                outputBridge = outputBridge,
                serviceScope = serviceScope,
                panelHost = this
            )

            keyEventRouter.configure(
                handler = this,
                searchInputHandler = { key -> candidateBarController.handleSearchInput(key) },
                viewModel = viewModel
            )

            suggestionPipeline =
                SuggestionPipeline(
                    host = this,
                    state = inputState,
                    outputBridge = outputBridge,
                    textInputProcessor = textInputProcessor,
                    spellCheckManager = spellCheckManager,
                    wordLearningEngine = wordLearningEngine,
                    wordFrequencyRepository = wordFrequencyRepository,
                    languageManager = languageManager,
                    caseTransformer = caseTransformer,
                    scriptConverterRegistry = scriptConverterRegistry,
                    serviceScope = serviceScope
                )

            layoutManager =
                KeyboardLayoutManager(
                    context = this,
                    onKeyClick = { key ->
                        inputState.clearBigramPredictions()
                        keyEventRouter.route(key)
                    },
                    onAcceleratedDeletionChanged = { active -> setAcceleratedDeletion(active) },
                    onSymbolsLongPress = { handleClipboardButtonClick() },
                    onLanguageSwitch = { languageCode -> handleLanguageSwitch(languageCode) },
                    onShowInputMethodPicker = { showInputMethodPicker() },
                    characterVariationService = characterVariationService,
                    languageManager = languageManager,
                    themeManager = themeManager,
                    cacheMemoryManager = cacheMemoryManager
                )
            layoutManager.onDeleteWord = { handleBackspaceSwipeDelete() }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "core_init")
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
                    context = mapOf("phase" to "language_init")
                )
                return
            }

            try {
                spellCheckManager.clearCaches()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "clearCaches_spellCheck")
                )
            }

            try {
                textInputProcessor.clearCaches()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "clearCaches_textInputProcessor")
                )
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
                            "language" to currentLanguage
                        )
                )
            }

            val currentLayoutLanguage = languageManager.currentLayoutLanguage.value
            val locale = ULocale.forLanguageTag(currentLayoutLanguage)
            updateScriptContext(locale)

            try {
                wordLearningEngine.getLearningStats()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "getLearningStats")
                )
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "services_init")
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

            val initialMode = keyboardModeManager.currentMode.value
            val initialDims = initialMode.adaptiveDimensions
            if (initialDims != null) {
                layoutManager.updateAdaptiveDimensions(initialDims)
            } else {
                layoutManager.updateSplitGapPx(initialMode.splitGapPx)
            }

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
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                }
            clipboardPanel = panel

            val rootContainer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )

                    setBackgroundColor(themeManager.currentTheme.value.colors.keyboardBackground)

                    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.setPadding(
                            systemBars.left,
                            0,
                            systemBars.right,
                            systemBars.bottom
                        )
                        WindowInsetsCompat.CONSUMED
                    }

                    addView(
                        panel,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )

                    addView(
                        adaptive,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
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
                context = mapOf("phase" to "onCreateInputView")
            )
            return null
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun createSwipeKeyboardView(): View? = try {
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
                    recentEmojiProvider
                )
                setOnKeyClickListener { key ->
                    inputState.clearBigramPredictions()
                    keyEventRouter.route(key)
                }
                setOnSwipeWordListener { validatedWord -> handleSwipeWord(validatedWord) }
                setOnSuggestionClickListener { suggestion -> handleSuggestionSelected(suggestion) }
                setOnSuggestionLongPressListener { suggestion ->
                    handleSuggestionRemoval(
                        suggestion
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
        keyboardModeManager.currentMode.value.adaptiveDimensions?.let {
            swipeView.updateAdaptiveDimensions(it)
        }
        layoutManager.setSwipeKeyboardView(swipeView)
        updateSwipeKeyboard()
        observeViewModel()

        swipeView
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "UrikInputMethodService",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("phase" to "create_keyboard_view")
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
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleEmojiSelected")
                )
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
                        keyboardHeight
                    )

                if (!settings.clipboardConsentShown) {
                    panel.showConsentScreen {
                        serviceScope.launch {
                            settingsRepository.updateClipboardConsentShown(true)
                            clipboardMonitorService.startMonitoring()
                            clipboardCoordinator.loadAndDisplayContent()
                        }
                    }
                } else {
                    clipboardCoordinator.loadAndDisplayContent()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleClipboardButtonClick")
                )
            }
        }
    }

    private fun dismissClipboardPanel() {
        clipboardPanel?.hide()
        adaptiveContainer?.visibility = View.VISIBLE
    }

    override suspend fun onClipboardDataLoaded(
        pinnedItems: List<com.urik.keyboard.data.database.ClipboardItem>,
        recentItems: List<com.urik.keyboard.data.database.ClipboardItem>
    ) {
        val panel = clipboardPanel ?: return
        withContext(Dispatchers.Main) {
            if (panel.isShowing) {
                panel.refreshContent(pinnedItems, recentItems)
            } else {
                panel.showClipboardContent(
                    pinnedItems = pinnedItems,
                    recentItems = recentItems,
                    onItemClick = { content ->
                        clipboardCoordinator.pasteContent(content)
                        dismissClipboardPanel()
                    },
                    onPinToggle = { item -> clipboardCoordinator.togglePin(item) },
                    onDelete = { item -> clipboardCoordinator.deleteItem(item) },
                    onDeleteAll = { clipboardCoordinator.deleteAllUnpinned() },
                    onClose = { dismissClipboardPanel() }
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
                    context = mapOf("operation" to "handleLanguageSwitch")
                )
            }
        }
    }

    private fun showInputMethodPicker() {
        try {
            inputMethodManager.showInputMethodPicker()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "showInputMethodPicker")
            )
        }
    }

    private fun observeSettings() {
        observerJobs.add(
            serviceScope.launch {
                settingsRepository.settings
                    .distinctUntilChanged()
                    .collect { newSettings ->
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
                            newSettings.vibrationStrength
                        )

                        layoutManager.updateClipboardEnabled(newSettings.clipboardEnabled)
                        layoutManager.updateShowLanguageSwitchKey(newSettings.showLanguageSwitchKey)
                        layoutManager.updateNumberHints(newSettings.showNumberHints)
                        layoutManager.updatePressHighlight(newSettings.keyPressHighlightEnabled)

                        if (layoutChanged) {
                            repository.cleanup()
                            viewModel.reloadLayout()
                        }

                        withContext(Dispatchers.Main) {
                            updateSwipeKeyboard()
                        }
                    }
            }
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
                                inputState.isCurrentWordAtSentenceStart
                            )
                        inputState.pendingSuggestions = recased
                        candidateBarController.updateSuggestions(recased)
                    }
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                viewModel.layout.collect { layout ->
                    if (layout != null) {
                        updateSwipeKeyboard()
                        val locale = ULocale.forLanguageTag(languageManager.currentLayoutLanguage.value)
                        updateScriptContext(locale)
                    }
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.currentLayoutLanguage.collect { detectedLanguage ->
                    swipeDetector.updateCurrentLanguage(detectedLanguage.split("-").first())
                    val isJa = detectedLanguage.split("-").first() == "ja"
                    textInputProcessor.setJapaneseLayout(isJa)
                    suggestionPipeline.setJapaneseLayout(isJa)
                }
            }
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
            }
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.effectiveDictionaryLanguages.collect { languages ->
                    swipeDetector.updateActiveLanguages(languages)
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                themeManager.currentTheme.collect { theme ->
                    keyboardRootContainer?.setBackgroundColor(theme.colors.keyboardBackground)
                    window?.window?.navigationBarColor = theme.colors.keyboardBackground
                    updateSwipeKeyboard()
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                customKeyMappingService.mappings.collect { mappings ->
                    layoutManager.updateCustomKeyMappings(mappings)
                    updateSwipeKeyboard()
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                keyboardModeManager.currentMode.collect { config ->
                    outputBridge.beginBatchEdit()
                    try {
                        val postureInfo = postureDetector?.postureInfo?.value
                        adaptiveContainer?.setModeConfig(config, postureInfo?.hingeBounds)

                        val dims = config.adaptiveDimensions
                        if (dims != null) {
                            layoutManager.updateAdaptiveDimensions(dims)
                            swipeKeyboardView?.updateAdaptiveDimensions(dims)
                            swipeDetector.updateAdaptiveDimensions(dims)
                        } else {
                            layoutManager.updateSplitGapPx(config.splitGapPx)
                        }

                        updateSwipeEnabledState(config.mode)
                        updateSwipeKeyboard()
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                }
            }
        )

        observeSettings()
    }

    private fun computeFilteredLayout(layout: KeyboardLayout): KeyboardLayout = when {
        !currentSettings.showNumberRow &&
            layout.mode == KeyboardMode.LETTERS &&
            layout.rows.isNotEmpty() -> {
            layout.copy(rows = layout.rows.drop(1))
        }

        layout.mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_SECONDARY) &&
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
                    KeyboardKey.Character("0", KeyboardKey.KeyType.NUMBER)
                )
            layout.copy(rows = listOf(numberRow) + layout.rows)
        }

        else -> layout
    }

    private fun updateSwipeKeyboard() {
        try {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return
            }

            val state = viewModel.state.value
            val layout = viewModel.layout.value

            if (layout != null && swipeKeyboardView != null) {
                val cacheKey = layout to currentSettings.showNumberRow
                val filteredLayout =
                    if (filteredLayoutCacheKey == cacheKey) {
                        filteredLayoutCache ?: computeFilteredLayout(layout)
                    } else {
                        val computed = computeFilteredLayout(layout)
                        filteredLayoutCache = computed
                        filteredLayoutCacheKey = cacheKey
                        computed
                    }

                swipeKeyboardView?.updateKeyboard(filteredLayout, state)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "updateSwipeKeyboard")
            )
        }
    }

    private fun updateSwipeEnabledState(mode: KeyboardDisplayMode) {
        if (!::swipeDetector.isInitialized) return

        val userSettingEnabled = currentSettings.swipeEnabled
        val isSplitMode = mode == KeyboardDisplayMode.SPLIT

        val shouldEnableSwipe = userSettingEnabled && !isSplitMode

        swipeDetector.setSwipeEnabled(shouldEnableSwipe)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        layoutManager.updateLongPressDuration(currentSettings.longPressDuration)
        layoutManager.updateLongPressPunctuationMode(currentSettings.longPressPunctuationMode)
        layoutManager.updateKeySize(currentSettings.keySize)
        layoutManager.updateSpaceBarSize(currentSettings.spaceBarSize)
        layoutManager.updateKeyLabelSize(currentSettings.keyLabelSize)

        layoutManager.updateHapticSettings(
            currentSettings.hapticFeedback,
            currentSettings.vibrationStrength
        )

        layoutManager.updateClipboardEnabled(currentSettings.clipboardEnabled)
        layoutManager.updateShowLanguageSwitchKey(currentSettings.showLanguageSwitchKey)
        layoutManager.updateNumberHints(currentSettings.showNumberHints)
        layoutManager.updatePressHighlight(currentSettings.keyPressHighlightEnabled)

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

        applyFieldTypeFromEditorInfo(info)

        val targetMode = KeyboardModeUtils.determineTargetMode(info, viewModel.state.value.currentMode)
        if (targetMode != viewModel.state.value.currentMode) {
            viewModel.onEvent(KeyboardEvent.ModeChanged(targetMode))
        }

        if (inputState.isSecureField) {
            clearSecureFieldState()
        } else if (!inputState.isUrlOrEmailField && !inputState.isTerminalField) {
            if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
                coordinateStateClear()
            }

            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        } else if (!inputState.isTerminalField) {
            if (inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            try {
                outputBridge.finishComposingText()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "onStartInputView_finishComposing")
                )
            }
        }

        updateKeyboardForCurrentAction()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            autofillCoordinator.onInputViewStarted(swipeKeyboardView != null)
        }
    }

    private fun updateKeyboardForCurrentAction() {
        viewModel.updateActionType(inputState.currentInputAction)
    }

    override fun onLetterInput(char: String) {
        autofillCoordinator.onKeyInput()
        if (inputState.displayBuffer.isEmpty()) {
            val state = viewModel.state.value
            inputState.isCurrentWordAtSentenceStart = state.isAutoShift
            inputState.isCurrentWordManualShifted =
                state.isShiftPressed &&
                !state.isAutoShift &&
                !state.isCapsLockOn
        }
        handleLetterInput(char)
    }

    override fun onNonLetterInput(char: String) {
        autofillCoordinator.onKeyInput()
        handleNonLetterInput(char)
    }

    override fun onBackspace() = handleBackspace()

    override fun onSpace() = handleSpace()

    override fun onEnterAction(imeAction: Int) {
        serviceScope.launch { performInputAction(imeAction) }
    }

    override fun onShift() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastShift = currentTime - inputState.lastShiftTime
        val currentState = viewModel.state.value

        when {
            timeSinceLastShift <= doubleShiftThreshold -> {
                viewModel.onEvent(KeyboardEvent.CapsLockToggled)
                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                inputState.lastShiftTime = 0
            }

            else -> {
                when {
                    currentState.isCapsLockOn -> viewModel.onEvent(KeyboardEvent.CapsLockToggled)
                    !currentState.isShiftPressed -> viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
                    currentState.isShiftPressed -> viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                }
                inputState.lastShiftTime = currentTime
            }
        }
    }

    override fun onCapsLock() {
        viewModel.onEvent(KeyboardEvent.CapsLockToggled)
    }

    override fun onModeSwitch(mode: KeyboardMode) {
        viewModel.onEvent(KeyboardEvent.ModeChanged(mode))
    }

    override fun onDakuten() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        val transformed = KanaTransformUtils.cycleDakutenOnLast(before)
        if (transformed != before) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(transformed, 1)
            ic.endBatchEdit()
        }
    }

    override fun onSmallKana() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        val transformed = KanaTransformUtils.toggleSmallKanaOnLast(before)
        if (transformed != before) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(transformed, 1)
            ic.endBatchEdit()
        }
    }

    override fun onLanguageSwitch() {}

    private fun handleLetterInput(char: String) {
        try {
            inputState.lastSpaceTime = 0

            if (inputState.requiresDirectCommit) {
                outputBridge.sendCharacter(char)
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

            val fastPath = inputState.isComposingCursorAtExpectedEnd()

            if (!fastPath && inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                outputBridge.clearSpellConfirmationState()
            }

            inputState.postCommitReplacementState = null
            inputState.lastAutocorrection = null

            var cachedAbsoluteCursorPos: Int? = null

            if (!fastPath && inputState.composingRegionStart != -1 && inputState.displayBuffer.isNotEmpty()) {
                val absoluteCursorPos = outputBridge.safeGetCursorPosition()
                cachedAbsoluteCursorPos = absoluteCursorPos
                val cursorOffsetInWord = (absoluteCursorPos - inputState.composingRegionStart).coerceIn(
                    0,
                    inputState.displayBuffer.length
                )
                val charsAfterCursorInWord = inputState.displayBuffer.length - cursorOffsetInWord

                val textBeforePart = outputBridge.safeGetTextBeforeCursor(
                    cursorOffsetInWord
                ).takeLast(cursorOffsetInWord)
                val textAfterPart = if (charsAfterCursorInWord > 0) {
                    outputBridge.safeGetTextAfterCursor(
                        charsAfterCursorInWord
                    ).take(charsAfterCursorInWord)
                } else {
                    ""
                }
                val actualComposingText = textBeforePart + textAfterPart

                if (actualComposingText != inputState.displayBuffer) {
                    inputState.composingRegionStart = -1
                    inputState.clearPendingTypingOus()
                }
            }

            val cursorPosInWord =
                if (fastPath) {
                    inputState.displayBuffer.length
                } else if (inputState.composingRegionStart != -1 && inputState.displayBuffer.isNotEmpty()) {
                    val absoluteCursorPos = cachedAbsoluteCursorPos ?: outputBridge.safeGetCursorPosition()
                    CursorEditingUtils.calculateCursorPositionInWord(
                        absoluteCursorPos,
                        inputState.composingRegionStart,
                        inputState.displayBuffer.length
                    )
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
                inputState.composingRegionStart = if (inputState.isKnownCursorTrustworthy()) {
                    inputState.lastKnownCursorPosition
                } else {
                    outputBridge.safeGetCursorPosition()
                }
            }

            val needsCursorRepositioning =
                inputState.composingRegionStart != -1 &&
                    newCursorPositionInText != inputState.displayBuffer.length

            if (inputState.composingRegionStart != -1) {
                val expectedComposingEnd = inputState.composingRegionStart + inputState.displayBuffer.length
                inputState.enqueueTypingOus(
                    InputStateManager.ExpectedTypingOus(
                        composingStart = inputState.composingRegionStart,
                        composingEnd = expectedComposingEnd,
                        cursorPosition = if (needsCursorRepositioning) {
                            inputState.composingRegionStart + newCursorPositionInText
                        } else {
                            expectedComposingEnd
                        }
                    )
                )
            } else {
                inputState.isActivelyEditing = true
            }

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
                    graphemeCount = inputState.displayBuffer.length
                )

            if (inputState.isUrlOrEmailField) {
                return
            }

            suggestionPipeline.requestSuggestions(
                buffer = inputState.displayBuffer,
                inputMethod = InputMethod.TYPED
            )
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleLetterInput", "char" to char)
            )
            coordinateStateClear()
            outputBridge.commitText(char, 1)
        }
    }

    private fun handleNonLetterInput(char: String) {
        if (inputState.requiresDirectCommit) {
            outputBridge.sendCharacter(char)
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

                if (inputState.postCommitReplacementState != null) {
                    inputState.postCommitReplacementState = null
                    candidateBarController.clearSuggestions()
                }
                inputState.lastAutocorrection = null

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

                if (inputState.displayBuffer.isNotEmpty() &&
                    currentSettings.spellCheckEnabled &&
                    inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                ) {
                    val textBefore = outputBridge.safeGetTextBeforeCursor(100)
                    val isUrlOrEmail =
                        UrlEmailDetector.isUrlOrEmailContext(
                            currentWord = inputState.displayBuffer,
                            textBeforeCursor = textBefore,
                            nextChar = char
                        )

                    if (!isUrlOrEmail) {
                        val isPunctuation =
                            char.length == 1 && CursorEditingUtils.isPunctuation(char.single())

                        if (isPunctuation) {
                            suggestionPipeline.cancelDebounceJob()
                            val isValid = textInputProcessor.validateWord(inputState.displayBuffer)
                            if (!isValid) {
                                outputBridge.beginBatchEdit()
                                try {
                                    val pronounLang = languageManager.currentLanguage.value.split("-").first()
                                    if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
                                        val corrected = EnglishPronounCorrection.capitalize(
                                            inputState.displayBuffer.lowercase()
                                        )
                                        if (corrected != null && corrected != inputState.displayBuffer) {
                                            inputState.onPronounCapitalized(corrected)
                                            outputBridge.setComposingText(corrected, 1)
                                        }
                                    }
                                    suggestionPipeline.learnWordAndInvalidateCache(
                                        inputState.displayBuffer,
                                        InputMethod.TYPED
                                    )
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(char, 1)

                                    val singleChar = char.single()
                                    if (isSentenceEndingPunctuation(singleChar) &&
                                        !inputState.requiresDirectCommit
                                    ) {
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
                            }
                        } else {
                            suggestionPipeline.cancelDebounceJob()
                            val decision = autoCorrectionEngine.decide(
                                buffer = inputState.displayBuffer,
                                spellCheckEnabled = currentSettings.spellCheckEnabled,
                                autocorrectionEnabled = currentSettings.autocorrectionEnabled,
                                pauseOnMisspelledWord = currentSettings.pauseOnMisspelledWord,
                                lastAutocorrection = inputState.lastAutocorrection,
                                textBeforeCursor = textBefore,
                                nextChar = char
                            )
                            when (decision) {
                                is AutocorrectDecision.Pause -> {
                                    inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                    inputState.pendingWordForLearning = inputState.displayBuffer
                                    outputBridge.highlightCurrentWord()
                                    val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                    val displaySuggestions =
                                        suggestionPipeline.storeAndCapitalizeSuggestions(
                                            suggestions,
                                            inputState.isCurrentWordAtSentenceStart
                                        )
                                    inputState.pendingSuggestions = displaySuggestions
                                    if (displaySuggestions.isNotEmpty()) {
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        candidateBarController.clearSuggestions()
                                    }
                                    return@launch
                                }

                                else -> { /* fall through to commit char */ }
                            }
                        }
                    }
                }

                outputBridge.beginBatchEdit()
                try {
                    val pronounLang = languageManager.currentLanguage.value.split("-").first()
                    if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
                        val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
                        if (corrected != null && corrected != inputState.displayBuffer) {
                            inputState.onPronounCapitalized(corrected)
                            outputBridge.setComposingText(corrected, 1)
                        }
                    }
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
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleNonLetterInput")
                )
            }
        }
    }

    private fun handleSwipeWord(validatedWord: String) {
        try {
            inputState.clearBigramPredictions()

            if (inputState.requiresDirectCommit) {
                if (!inputState.isSecureField && !inputState.isRawKeyEventField) {
                    val textBefore = outputBridge.safeGetTextBeforeCursor(1)
                    if (textBefore.isNotEmpty() && !swipeSpaceManager.isWhitespace(textBefore)) {
                        outputBridge.commitText(" ", 1)
                        swipeSpaceManager.markAutoSpaceInserted()
                    }
                }
                outputBridge.commitText(validatedWord, 1)
                return
            }

            if (inputState.displayBuffer.isNotEmpty()) {
                outputBridge.beginBatchEdit()
                try {
                    val pronounLang = languageManager.currentLanguage.value.split("-").first()
                    if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
                        val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
                        if (corrected != null && corrected != inputState.displayBuffer) {
                            inputState.onPronounCapitalized(corrected)
                            outputBridge.setComposingText(corrected, 1)
                        }
                    }
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
            val isManualShifted =
                keyboardState.isShiftPressed && !keyboardState.isAutoShift && !keyboardState.isCapsLockOn
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
                val swipeScriptCode = textInputProcessor.currentScriptCode
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
                            isSentenceStart = isSentenceStart
                        )

                    val result =
                        textInputProcessor.processWordInput(validatedWord, InputMethod.SWIPED)

                    when (result) {
                        is ProcessingResult.Success -> {
                            withContext(Dispatchers.Main) {
                                inputState.isActivelyEditing = true
                                outputBridge.commitPreviousSwipeAndInsertSpace()
                                outputBridge.setComposingText(displayWord, 1)
                                inputState.composingRegionStart =
                                    outputBridge.safeGetCursorPosition() - displayWord.length
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
                                inputState.composingRegionStart =
                                    outputBridge.safeGetCursorPosition() - displayWord.length
                                inputState.displayBuffer = displayWord
                                inputState.wordState =
                                    WordState(
                                        buffer = displayWord,
                                        normalizedBuffer = validatedWord.lowercase(),
                                        isFromSwipe = true,
                                        graphemeCount = displayWord.length,
                                        scriptCode = swipeScriptCode
                                    )
                            }
                        }
                    }
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "handleSwipeWord_commitWord")
                    )
                    withContext(Dispatchers.Main) {
                        val fallbackSuggestion =
                            com.urik.keyboard.service.SpellingSuggestion(
                                word = validatedWord,
                                confidence = 1.0,
                                ranking = 0,
                                source = "swipe",
                                preserveCase = false
                            )
                        val fallbackDisplay = caseTransformer.applyCasing(
                            fallbackSuggestion,
                            effectiveState,
                            isSentenceStart
                        )
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
                                scriptCode = swipeScriptCode
                            )
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleSwipeWord")
            )
            outputBridge.setComposingText(validatedWord, 1)
        }
    }

    private fun computeSwipeDisplayWord(
        validatedWord: String,
        learnedOriginalCase: String?,
        currentLanguage: String,
        keyboardState: com.urik.keyboard.model.KeyboardState,
        isSentenceStart: Boolean = false
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
                preserveCase = preserveCase
            )

        return caseTransformer.applyCasing(suggestion, keyboardState, isSentenceStart)
    }

    private fun getEnglishPronounIForm(normalizedWord: String): String? =
        EnglishPronounCorrection.capitalize(normalizedWord)

    private fun handleSuggestionSelected(suggestion: String) {
        serviceScope.launch {
            if (inputState.requiresDirectCommit) {
                return@launch
            }

            val replacementState = inputState.postCommitReplacementState
            if (replacementState != null) {
                suggestionPipeline.coordinatePostCommitReplacement(
                    suggestion,
                    replacementState,
                    ::checkAutoCapitalization
                )
                return@launch
            }

            if (suggestionPipeline.isJapaneseLayout) {
                val reading = inputState.displayBuffer
                val rawSource = inputState.currentRawSuggestions
                    .firstOrNull { it.word.equals(suggestion, ignoreCase = true) }?.source
                if (rawSource == "learned" || rawSource == "dictionary") {
                    scriptConverterRegistry.forLanguage(languageManager.currentLanguage.value)
                        ?.recordSelection(reading, suggestion)
                }
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
                        candidateBarController.updateSuggestions(currentSuggestions)
                    } else {
                        candidateBarController.clearSuggestions()
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "handleSuggestionRemoval")
                )
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
                EditorInfo.IME_ACTION_PREVIOUS
                -> {
                    outputBridge.performEditorAction(imeAction)
                }

                else -> {
                    outputBridge.sendEnter()
                }
            }

            coordinateStateClear()

            if (KeyboardModeUtils.shouldResetToLettersOnEnter(
                    viewModel.state.value.currentMode,
                    currentInputEditorInfo
                )
            ) {
                viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))
            }

            if (imeAction == EditorInfo.IME_ACTION_NONE && !inputState.isTerminalField) {
                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                checkAutoCapitalization(textBefore)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "performInputAction")
            )
            coordinateStateClear()
        }
    }

    private fun handleBackspace() {
        try {
            if (inputState.isTerminalField) {
                outputBridge.sendBackspace()
                return
            }

            val actualCursorPos = outputBridge.safeGetCursorPosition()

            if (inputState.displayBuffer.isNotEmpty() && inputState.composingRegionStart != -1) {
                @Suppress("UnnecessaryParentheses")
                val expectedCursorRange =
                    inputState.composingRegionStart..(inputState.composingRegionStart + inputState.displayBuffer.length)
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
                        0
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
                        layoutManager.stopAcceleratedBackspace()
                    }
                    return
                }
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.wordState.isFromSwipe) {
                outputBridge.setComposingText("", 1)
                coordinateStateClear()
                return
            }

            if (inputState.isSecureField) {
                val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(WORD_BOUNDARY_CONTEXT_LENGTH)
                if (textBeforeCursor.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBeforeCursor)
                    outputBridge.deleteSurroundingText(graphemeLength, 0)
                }
                return
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                inputState.clearSpellConfirmationFields()
            }

            if (inputState.postCommitReplacementState != null) {
                inputState.postCommitReplacementState = null
                candidateBarController.clearSuggestions()
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.composingRegionStart != -1) {
                val cursorOffsetInWord = (actualCursorPos - inputState.composingRegionStart).coerceIn(
                    0,
                    inputState.displayBuffer.length
                )
                val charsAfterCursorInWord = inputState.displayBuffer.length - cursorOffsetInWord

                val textBeforePart = if (cursorOffsetInWord > 0) {
                    outputBridge.safeGetTextBeforeCursor(cursorOffsetInWord).takeLast(cursorOffsetInWord)
                } else {
                    ""
                }
                val textAfterPart = if (charsAfterCursorInWord > 0) {
                    outputBridge.safeGetTextAfterCursor(charsAfterCursorInWord).take(charsAfterCursorInWord)
                } else {
                    ""
                }

                if (textBeforePart + textAfterPart != inputState.displayBuffer) {
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
                val cursorPosInWord =
                    if (inputState.composingRegionStart != -1) {
                        CursorEditingUtils.calculateCursorPositionInWord(
                            actualCursorPos,
                            inputState.composingRegionStart,
                            inputState.displayBuffer.length
                        )
                    } else {
                        val potentialStart = actualCursorPos - inputState.displayBuffer.length
                        if (potentialStart >= 0) {
                            inputState.composingRegionStart = potentialStart
                            CursorEditingUtils.calculateCursorPositionInWord(
                                actualCursorPos,
                                inputState.composingRegionStart,
                                inputState.displayBuffer.length
                            )
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
                    inputState.displayBuffer =
                        BackspaceUtils.deleteGraphemeClusterBeforePosition(inputState.displayBuffer, cursorPosInWord)
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
                                inputMethod = InputMethod.TYPED
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleBackspace")
            )
            try {
                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                if (textBefore.isNotEmpty()) {
                    val graphemeLength = BackspaceUtils.getLastGraphemeClusterLength(textBefore)
                    outputBridge.deleteSurroundingText(graphemeLength, 0)
                }
            } catch (e2: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e2,
                    context = mapOf("operation" to "handleBackspace_fallback")
                )
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
                                expectedNewPosition
                            )

                        if (composingRegion != null) {
                            val (wordStart, wordEnd, word) = composingRegion

                            val actualCursorPos = outputBridge.safeGetCursorPosition()
                            if (CursorEditingUtils.shouldAbortRecomposition(
                                    expectedCursorPosition = expectedNewPosition,
                                    actualCursorPosition = actualCursorPos,
                                    expectedComposingStart = wordStart,
                                    actualComposingStart = inputState.composingRegionStart
                                )
                            ) {
                                coordinateStateClear()
                                return
                            }

                            outputBridge.setComposingRegion(wordStart, wordEnd)

                            inputState.displayBuffer = word
                            inputState.composingRegionStart = wordStart

                            val autocorrection = inputState.lastAutocorrection
                            if (autocorrection != null &&
                                word.equals(autocorrection.correctedWord, ignoreCase = true)
                            ) {
                                outputBridge.setComposingText(autocorrection.originalTypedWord, 1)
                                inputState.displayBuffer = autocorrection.originalTypedWord
                                inputState.pendingSuggestions = emptyList()
                                candidateBarController.clearSuggestions()
                            } else {
                                inputState.lastAutocorrection = null
                                suggestionPipeline.requestSuggestions(
                                    buffer = word,
                                    inputMethod = InputMethod.TYPED
                                )
                            }
                        } else {
                            coordinateStateClear()
                        }
                    }
                } finally {
                    outputBridge.endBatchEdit()
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleLetterInput")
            )
            coordinateStateClear()
        }
    }

    private fun applyPronounCorrectionIfNeeded() {
        val pronounLang = languageManager.currentLanguage.value.split("-").first()
        if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
            val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
            if (corrected != null && corrected != inputState.displayBuffer) {
                inputState.onPronounCapitalized(corrected)
                outputBridge.setComposingText(corrected, 1)
            }
        }
    }

    private fun handleDoubleSpacePeriod(timeSinceLastSpace: Long): Boolean {
        if (timeSinceLastSpace > doubleTapSpaceThreshold ||
            inputState.spellConfirmationState != SpellConfirmationState.NORMAL ||
            !currentSettings.doubleSpacePeriod
        ) {
            return false
        }
        outputBridge.beginBatchEdit()
        try {
            if (inputState.wordState.hasContent && !inputState.requiresDirectCommit) {
                inputState.clearInternalStateOnly()
                outputBridge.finishComposingText()
            }
            outputBridge.deleteSurroundingText(1, 0)
            outputBridge.commitText(". ", 1)
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        } finally {
            outputBridge.endBatchEdit()
        }
        inputState.lastSpaceTime = 0
        return true
    }

    private fun handleSpace() {
        serviceScope.launch {
            try {
                if (inputState.requiresDirectCommit) {
                    outputBridge.sendSpace()
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

                if (handleDoubleSpacePeriod(timeSinceLastSpace)) return@launch

                inputState.lastSpaceTime = currentTime

                if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    suggestionPipeline.confirmAndLearnWord(::checkAutoCapitalization)
                    return@launch
                }

                if (inputState.displayBuffer.isNotEmpty() &&
                    currentSettings.spellCheckEnabled &&
                    inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                ) {
                    val textBeforeForUrlCheck = outputBridge.safeGetTextBeforeCursor(100)
                    val isUrlOrEmail =
                        UrlEmailDetector.isUrlOrEmailContext(
                            currentWord = inputState.displayBuffer,
                            textBeforeCursor = textBeforeForUrlCheck,
                            nextChar = " "
                        )

                    if (!isUrlOrEmail) {
                        suggestionPipeline.cancelDebounceJob()
                        val decision = autoCorrectionEngine.decide(
                            buffer = inputState.displayBuffer,
                            spellCheckEnabled = currentSettings.spellCheckEnabled,
                            autocorrectionEnabled = currentSettings.autocorrectionEnabled,
                            pauseOnMisspelledWord = currentSettings.pauseOnMisspelledWord,
                            lastAutocorrection = inputState.lastAutocorrection,
                            textBeforeCursor = textBeforeForUrlCheck,
                            nextChar = " "
                        )
                        when (decision) {
                            is AutocorrectDecision.None -> {
                                inputState.isActivelyEditing = true
                                suggestionPipeline.recordWordUsage(inputState.displayBuffer)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()
                                    suggestionPipeline.showBigramPredictions()

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    checkAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.ContractionBypass -> {
                                val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                val originalWord = inputState.displayBuffer
                                inputState.isActivelyEditing = true
                                suggestionPipeline.learnWordAndInvalidateCache(originalWord, InputMethod.TYPED)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(originalWord)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()

                                    if (displaySuggestions.isNotEmpty()) {
                                        inputState.postCommitReplacementState =
                                            PostCommitReplacementState(
                                                originalWord = originalWord,
                                                committedWord = originalWord
                                            )
                                        inputState.pendingSuggestions = displaySuggestions
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        suggestionPipeline.showBigramPredictions()
                                    }

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    checkAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Pause -> {
                                val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                inputState.pendingWordForLearning = inputState.displayBuffer
                                outputBridge.highlightCurrentWord()
                                inputState.pendingSuggestions = displaySuggestions
                                if (displaySuggestions.isNotEmpty()) {
                                    candidateBarController.updateSuggestions(displaySuggestions)
                                } else {
                                    candidateBarController.clearSuggestions()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Correct -> {
                                val originalWord = inputState.displayBuffer
                                val correctedWord = decision.suggestion
                                inputState.isActivelyEditing = true
                                suggestionPipeline.recordWordUsage(correctedWord)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    outputBridge.commitText("$correctedWord ", 1)
                                    swipeDetector.updateLastCommittedWord(correctedWord)
                                    inputState.clearInternalStateOnly()
                                    inputState.postCommitReplacementState =
                                        PostCommitReplacementState(
                                            originalWord = originalWord,
                                            committedWord = correctedWord
                                        )
                                    inputState.lastAutocorrection =
                                        LastAutocorrection(
                                            originalTypedWord = originalWord,
                                            correctedWord = correctedWord
                                        )
                                    inputState.pendingSuggestions = listOf(originalWord)
                                    candidateBarController.updateSuggestions(inputState.pendingSuggestions)

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    checkAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Suggestions -> {
                                val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                val originalWord = inputState.displayBuffer
                                inputState.isActivelyEditing = true
                                suggestionPipeline.learnWordAndInvalidateCache(originalWord, InputMethod.TYPED)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(originalWord)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()

                                    if (displaySuggestions.isNotEmpty()) {
                                        inputState.postCommitReplacementState =
                                            PostCommitReplacementState(
                                                originalWord = originalWord,
                                                committedWord = originalWord
                                            )
                                        inputState.pendingSuggestions = displaySuggestions
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        suggestionPipeline.showBigramPredictions()
                                    }

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    checkAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }
                        }
                    }
                }

                outputBridge.beginBatchEdit()
                try {
                    applyPronounCorrectionIfNeeded()
                    if (inputState.displayBuffer.isNotEmpty()) {
                        swipeDetector.updateLastCommittedWord(
                            inputState.displayBuffer
                        )
                    }
                    outputBridge.finishComposingText()
                    outputBridge.commitText(" ", 1)
                    inputState.clearInternalStateOnly()
                    suggestionPipeline.showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleSpace")
                )
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "handleSpacebarCursorMove")
            )
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
            while (idx > 0 &&
                !Character.isLetterOrDigit(textBeforeCursor[idx - 1]) &&
                !Character.isWhitespace(textBeforeCursor[idx - 1])
            ) {
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
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleBackspaceSwipeDelete")
            )
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)

        if (::layoutManager.isInitialized) {
            layoutManager.stopAcceleratedBackspace()
        }

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        suggestionPipeline.cancelDebounceJob()
        candidateBarController.hideEmojiPicker()
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
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "onFinishInputView_commitWord")
                    )
                    coordinateStateClear()
                }
            }
        }

        try {
            outputBridge.finishComposingText()
            coordinateStateClear()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "onFinishInputView_finishComposing")
            )
            coordinateStateClear()
        }

        if (currentSettings.resetToLettersOnDismiss) {
            viewModel.resetToLetters()
        }

        autofillCoordinator.onInputViewFinished()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)

        if (::layoutManager.isInitialized) {
            layoutManager.stopAcceleratedBackspace()
        }

        autofillCoordinator.onFieldChanged(
            inputType = attribute?.inputType ?: 0,
            imeOptions = attribute?.imeOptions ?: 0,
            fieldId = attribute?.fieldId ?: 0,
            packageHash = attribute?.packageName?.hashCode() ?: 0
        )
        inputState.selectionStateTracker.reset()
        coordinateStateClear()

        inputState.lastSpaceTime = 0
        inputState.lastShiftTime = 0
        inputState.lastCommittedWord = ""
        inputState.lastKnownCursorPosition = -1

        applyFieldTypeFromEditorInfo(attribute)

        if (inputState.isSecureField) {
            clearSecureFieldState()
        } else if (!inputState.isUrlOrEmailField && !inputState.isTerminalField) {
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        }
    }

    private fun applyFieldTypeFromEditorInfo(info: EditorInfo?) {
        inputState.isSecureField = SecureFieldDetector.isSecure(info)
        inputState.isDirectCommitField = SecureFieldDetector.isDirectCommit(info)
        inputState.isRawKeyEventField = SecureFieldDetector.isRawKeyEvent(info)
        inputState.isTerminalField = SecureFieldDetector.isTerminalField(info)
        if (inputState.isTerminalField) viewModel.disableAutoCapForTerminalField()
        inputState.currentInputAction = ActionDetector.detectAction(info)
        val inputType = info?.inputType ?: 0
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        inputState.isUrlOrEmailField = variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    override fun onFinishInput() {
        super.onFinishInput()

        if (::layoutManager.isInitialized) {
            layoutManager.stopAcceleratedBackspace()
        }

        suggestionPipeline.cancelDebounceJob()
        candidateBarController.hideEmojiPicker()
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
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "onFinishInput_commitWord")
                    )
                    coordinateStateClear()
                }
            }
        }

        try {
            outputBridge.finishComposingText()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "onFinishInput_finishComposing")
            )
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
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )

        if (handleDirectCommitInProgress()) return

        val selectionResult =
            inputState.selectionStateTracker.updateSelection(
                newSelStart = newSelStart,
                newSelEnd = newSelEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd
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
                    inputState.wordState.hasContent
                )
            ) {
                invalidateComposingStateOnCursorJump()
            }

            if (!inputState.isUrlOrEmailField) {
                checkAutoCapitalization(textBefore)
            }
        }

        if (handleAppSelectionExtended(selectionResult, newSelStart)) return

        if (handleUrlOrEmailField()) return

        if (handleNonSequentialJump(selectionResult, newSelStart, newSelEnd)) return

        val ousConsumed = inputState.tryConsumeTypingOus(newSelStart, candidatesStart, candidatesEnd)
        if (handleTypingOusConsumed(ousConsumed, newSelStart)) return

        val hasComposingText = candidatesStart != -1 && candidatesEnd != -1
        val cursorInComposingRegion =
            hasComposingText &&
                newSelStart >= candidatesStart &&
                newSelStart <= candidatesEnd &&
                newSelEnd >= candidatesStart &&
                newSelEnd <= candidatesEnd

        if (handleActivelyEditing(newSelStart)) return

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

    @VisibleForTesting
    internal fun handleDirectCommitInProgress(): Boolean = inputState.requiresDirectCommit

    @VisibleForTesting
    internal fun handleAppSelectionExtended(selectionResult: SelectionChangeResult, newSelStart: Int): Boolean {
        if (selectionResult !is SelectionChangeResult.AppSelectionExtended) return false
        if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
            inputState.clearInternalStateOnly()
            inputState.isActivelyEditing = false
        }
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    @VisibleForTesting
    internal fun handleUrlOrEmailField(): Boolean = inputState.isUrlOrEmailField

    @VisibleForTesting
    internal fun handleNonSequentialJump(
        selectionResult: SelectionChangeResult,
        newSelStart: Int,
        newSelEnd: Int
    ): Boolean {
        if (selectionResult !is SelectionChangeResult.NonSequentialJump) return false
        if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
            invalidateComposingStateOnCursorJump()
        }
        inputState.lastKnownCursorPosition = newSelStart
        if (newSelStart == newSelEnd) {
            outputBridge.attemptRecompositionAtCursor(newSelStart)
        }
        return true
    }

    @VisibleForTesting
    internal fun handleTypingOusConsumed(ousConsumed: Boolean, newSelStart: Int): Boolean {
        if (!ousConsumed) return false
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    @VisibleForTesting
    internal fun handleActivelyEditing(newSelStart: Int): Boolean {
        if (!inputState.isActivelyEditing) return false
        inputState.isActivelyEditing = false
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        streamingScoringEngine.cancelActiveGesture()

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

        postureDetector?.onConfigurationChanged()

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
                        .build()
                ).setChipStyle(
                    ViewStyle
                        .Builder()
                        .setBackgroundColor(theme.colors.suggestionBarBackground)
                        .setPadding(chipPadding, 0, chipPadding, 0)
                        .build()
                ).setTitleStyle(
                    TextViewStyle
                        .Builder()
                        .setTextColor(theme.colors.suggestionText)
                        .setTextSize(suggestionTextSize)
                        .build()
                ).setSubtitleStyle(
                    TextViewStyle
                        .Builder()
                        .setTextColor(theme.colors.suggestionText)
                        .setTextSize(suggestionTextSize * 0.9f)
                        .build()
                ).build()

        stylesBuilder.addStyle(style)
        val stylesBundle = stylesBuilder.build()

        val specs = mutableListOf<InlinePresentationSpec>()
        repeat(4) {
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
                .build()
        )

        return InlineSuggestionsRequest
            .Builder(specs)
            .setMaxSuggestionCount(5)
            .build()
    }

    @Suppress("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return autofillCoordinator.onInlineSuggestionsResponse(response, swipeKeyboardView != null)
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
                candidateBarController.updateInlineAutofillSuggestions(views, true)
            }
        }
    }

    @Suppress("NewApi")
    private suspend fun inflateSuggestionView(suggestion: InlineSuggestion, size: Size): View? = try {
        suspendCancellableCoroutine { continuation ->
            suggestion.inflate(this@UrikInputMethodService, size, mainExecutor) { view ->
                if (continuation.isActive) {
                    continuation.resume(view)
                }
            }
        }
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "UrikInputMethodService",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to "inflateSuggestionView")
        )
        null
    }

    override fun onDestroy() {
        streamingScoringEngine.cancelActiveGesture()
        wordFrequencyRepository.clearCache()
        autofillCoordinator.cleanup()

        serviceJob.cancel()

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
