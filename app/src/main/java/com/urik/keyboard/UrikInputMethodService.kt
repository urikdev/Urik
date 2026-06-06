package com.urik.keyboard

import android.annotation.SuppressLint
import android.icu.lang.UScript
import android.icu.util.ULocale
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.KeyCharacterMap
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
import com.urik.keyboard.KeyboardConstants.AutofillConstants.MAX_PASSWORD_INLINE_SUGGESTIONS
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AutoCorrectionEngine
import com.urik.keyboard.service.AutofillStateCoordinator
import com.urik.keyboard.service.AutofillStateTracker
import com.urik.keyboard.service.BackspaceHandler
import com.urik.keyboard.service.CandidateBarController
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.ClipboardActionCoordinator
import com.urik.keyboard.service.ClipboardMonitorService
import com.urik.keyboard.service.ClipboardPanelHost
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.ImeStateCoordinator
import com.urik.keyboard.service.InputFieldClassifier
import com.urik.keyboard.service.InputStateManager
import com.urik.keyboard.service.JapaneseCandidateHandler
import com.urik.keyboard.service.KeyEventHandler
import com.urik.keyboard.service.KeyEventRouter
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.LetterInputHandler
import com.urik.keyboard.service.NonLetterInputHandler
import com.urik.keyboard.service.OnUpdateSelectionHandler
import com.urik.keyboard.service.OutputBridge
import com.urik.keyboard.service.SpaceInputHandler
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.SuggestionPipeline
import com.urik.keyboard.service.SuggestionPipelineHost
import com.urik.keyboard.service.SwipeWordHandler
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.ViewCallback
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import com.urik.keyboard.ui.keyboard.components.ClipboardPanel
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.ui.keyboard.components.SwipeKeyboardView
import com.urik.keyboard.utils.BackspaceUtils
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.KanaTransformUtils
import com.urik.keyboard.utils.KeyboardModeUtils
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

    private var doubleShiftThreshold: Long = DOUBLE_SHIFT_THRESHOLD_MS

    private var currentSettings: KeyboardSettings = KeyboardSettings()

    private lateinit var autofillCoordinator: AutofillStateCoordinator
    private lateinit var clipboardCoordinator: ClipboardActionCoordinator

    private lateinit var candidateBarController: CandidateBarController
    private lateinit var imeStateCoordinator: ImeStateCoordinator
    private lateinit var onUpdateSelectionHandler: OnUpdateSelectionHandler
    private lateinit var japaneseCandidateHandler: JapaneseCandidateHandler
    private lateinit var letterInputHandler: LetterInputHandler
    private lateinit var nonLetterInputHandler: NonLetterInputHandler
    private lateinit var backspaceHandler: BackspaceHandler
    private lateinit var spaceInputHandler: SpaceInputHandler
    private lateinit var swipeWordHandler: SwipeWordHandler

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

    private fun coordinateStateClear() {
        if (::japaneseCandidateHandler.isInitialized) japaneseCandidateHandler.reset()
        imeStateCoordinator.coordinateStateClear()
    }

    private fun invalidateComposingStateOnCursorJump() = imeStateCoordinator.invalidateComposingStateOnCursorJump()

    private fun checkAutoCapitalization(textBefore: String) {
        viewModel.checkAndApplyAutoCapitalization(textBefore, currentSettings.autoCapitalizationEnabled)
    }

    private fun sendCharacterAsKeyEvents(char: String) {
        val ic = currentInputConnection ?: return
        val events = KeyCharacterMap.load(
            KeyCharacterMap.VIRTUAL_KEYBOARD
        ).getEvents(char.toCharArray())
        if (events != null) {
            val softKeyFlags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            for (event in events) {
                ic.sendKeyEvent(
                    KeyEvent(
                        event.downTime,
                        event.eventTime,
                        event.action,
                        event.keyCode,
                        event.repeatCount,
                        event.metaState,
                        event.deviceId,
                        event.scanCode,
                        event.flags or softKeyFlags
                    )
                )
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

                        override fun showDegradedIndicator(degraded: Boolean) {
                            candidateBarController.showDegradedIndicator(degraded)
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
                            val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
                            ic.sendKeyEvent(
                                KeyEvent(
                                    now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags
                                )
                            )
                            ic.sendKeyEvent(
                                KeyEvent(
                                    now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags
                                )
                            )
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

            onUpdateSelectionHandler = OnUpdateSelectionHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                imeStateCoordinator = imeStateCoordinator,
                onCheckAutoCapitalization = ::checkAutoCapitalization
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

            japaneseCandidateHandler = JapaneseCandidateHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                onCommit = { suggestion -> handleSuggestionSelected(suggestion) }
            )
            letterInputHandler = LetterInputHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                swipeSpaceManager = swipeSpaceManager,
                onCoordinateStateClear = ::coordinateStateClear,
                onCheckAutoCapitalization = ::checkAutoCapitalization
            )
            nonLetterInputHandler = NonLetterInputHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                autoCorrectionEngine = autoCorrectionEngine,
                textInputProcessor = textInputProcessor,
                swipeSpaceManager = swipeSpaceManager,
                languageManager = languageManager,
                candidateBarController = candidateBarController,
                serviceScope = serviceScope,
                onGetCurrentSettings = { currentSettings },
                onCoordinateStateClear = ::coordinateStateClear,
                onCheckAutoCapitalization = ::checkAutoCapitalization,
                onDisableCapsLockAfterPunctuation = { viewModel.disableCapsLockAfterPunctuation() }
            )
            backspaceHandler = BackspaceHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                candidateBarController = candidateBarController,
                layoutManager = layoutManager,
                serviceScope = serviceScope,
                onCoordinateStateClear = ::coordinateStateClear,
                onInvalidateComposingState = ::invalidateComposingStateOnCursorJump,
                onDisableShiftAfterBackspace = { viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false)) },
                onGetKeyboardState = { viewModel.state.value },
                onSendDownUpKeyEvents = ::sendDownUpKeyEvents
            )
            spaceInputHandler = SpaceInputHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                autoCorrectionEngine = autoCorrectionEngine,
                textInputProcessor = textInputProcessor,
                swipeSpaceManager = swipeSpaceManager,
                swipeDetector = swipeDetector,
                candidateBarController = candidateBarController,
                languageManager = languageManager,
                serviceScope = serviceScope,
                onGetCurrentSettings = { currentSettings },
                onCheckAutoCapitalization = ::checkAutoCapitalization,
                onJapaneseSpaceNextCandidate = { japaneseCandidateHandler.onNextCandidate() }
            )
            swipeWordHandler = SwipeWordHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                textInputProcessor = textInputProcessor,
                wordLearningEngine = wordLearningEngine,
                languageManager = languageManager,
                caseTransformer = caseTransformer,
                swipeSpaceManager = swipeSpaceManager,
                swipeDetector = swipeDetector,
                serviceScope = serviceScope,
                onGetKeyboardState = { viewModel.state.value },
                onCoordinateStateClear = ::coordinateStateClear,
                onCheckAutoCapitalization = ::checkAutoCapitalization,
                onDisableShiftAfterSwipe = { viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false)) }
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

    private fun computeFilteredLayout(layout: KeyboardLayout): KeyboardLayout =
        computeFilteredLayout(layout, currentSettings.showNumberRow)

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

    override fun onLetterInput(char: String, wasAutoShifted: Boolean) {
        autofillCoordinator.onKeyInput()
        if (inputState.displayBuffer.isEmpty()) {
            val state = viewModel.state.value
            inputState.isCurrentWordAtSentenceStart = wasAutoShifted
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
        val buffer = inputState.displayBuffer
        if (buffer.isNotEmpty()) {
            val cycled = KanaTransformUtils.cycleDakutenOnLast(buffer)
            if (cycled != buffer) {
                inputState.updateDisplayBuffer(cycled)
                outputBridge.setComposingText(cycled, 1)
            }
            return
        }
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
        val buffer = inputState.displayBuffer
        if (buffer.isNotEmpty()) {
            val toggled = KanaTransformUtils.toggleSmallKanaOnLast(buffer)
            if (toggled != buffer) {
                inputState.updateDisplayBuffer(toggled)
                outputBridge.setComposingText(toggled, 1)
            }
            return
        }
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

    override fun onNextCandidate() {
        japaneseCandidateHandler.onNextCandidate()
    }

    override fun onCommitCandidate() {
        japaneseCandidateHandler.onCommitCandidate()
    }

    override fun onHandakuten() {
        val buffer = inputState.displayBuffer
        if (buffer.isNotEmpty()) {
            val transformed = KanaTransformUtils.toHandakuten(buffer.last())?.let {
                buffer.dropLast(1) + it
            } ?: return
            inputState.updateDisplayBuffer(transformed)
            outputBridge.setComposingText(transformed, 1)
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        val last = before.lastOrNull() ?: return
        val handakuten = KanaTransformUtils.toHandakuten(last) ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(handakuten.toString(), 1)
        ic.endBatchEdit()
    }

    override fun onEmoji() {
        candidateBarController.showEmojiPicker()
    }

    override fun onLanguageSwitch() {}

    private fun handleLetterInput(char: String) {
        japaneseCandidateHandler.reset()
        letterInputHandler.handle(char)
    }

    private fun handleNonLetterInput(char: String) = nonLetterInputHandler.handle(char)

    private fun handleSwipeWord(validatedWord: String) = swipeWordHandler.handle(validatedWord)

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

    private fun handleBackspace() = backspaceHandler.handle()

    private fun handleSpace() = spaceInputHandler.handle()

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
        val c = InputFieldClassifier.classify(info)
        inputState.isSecureField = c.isSecureField
        inputState.isDirectCommitField = c.isDirectCommitField
        inputState.isRawKeyEventField = c.isRawKeyEventField
        inputState.isTerminalField = c.isTerminalField
        if (inputState.isTerminalField) viewModel.disableAutoCapForTerminalField()
        inputState.currentInputAction = c.currentInputAction
        inputState.isUrlOrEmailField = c.isUrlOrEmailField
        inputState.isSuggestionsDisabled = c.isSuggestionsDisabled
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
        onUpdateSelectionHandler.handle(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
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
        repeat(MAX_PASSWORD_INLINE_SUGGESTIONS) {
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
            .setMaxSuggestionCount(MAX_PASSWORD_INLINE_SUGGESTIONS + 1)
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
            for (suggestion in suggestions.take(MAX_PASSWORD_INLINE_SUGGESTIONS)) {
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

internal fun computeFilteredLayout(layout: KeyboardLayout, showNumberRow: Boolean): KeyboardLayout = when {
    !showNumberRow &&
        layout.mode == KeyboardMode.LETTERS &&
        layout.rows.isNotEmpty() &&
        isTopRowANumberRow(layout.rows[0]) -> {
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

internal fun isTopRowANumberRow(row: List<KeyboardKey>): Boolean {
    val charKeys = row.filterIsInstance<KeyboardKey.Character>()
    return charKeys.size == 10 && charKeys.all { it.type == KeyboardKey.KeyType.NUMBER }
}
