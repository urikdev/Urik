package com.urik.keyboard.service

import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class ImeStateCoordinatorTest {
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockStreamingScoringEngine: com.urik.keyboard.ui.keyboard.components.StreamingScoringEngine
    private lateinit var mockInputState: InputStateManager
    private lateinit var mockSpellCheckManager: SpellCheckManager
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var mockWordLearningEngine: WordLearningEngine
    private lateinit var coordinator: ImeStateCoordinator

    @Before
    fun setup() {
        mockOutputBridge = mock()
        mockStreamingScoringEngine = mock()
        mockInputState = mock()
        mockSpellCheckManager = mock()
        mockTextInputProcessor = mock()
        mockWordLearningEngine = mock()

        coordinator = ImeStateCoordinator(
            outputBridge = mockOutputBridge,
            streamingScoringEngine = mockStreamingScoringEngine,
            inputState = mockInputState,
            spellCheckManager = mockSpellCheckManager,
            textInputProcessor = mockTextInputProcessor,
            wordLearningEngine = mockWordLearningEngine
        )
    }

    @Test
    fun `coordinateStateClear calls cancelActiveGesture then coordinateStateClear in order`() {
        coordinator.coordinateStateClear()
        inOrder(mockStreamingScoringEngine, mockOutputBridge) {
            verify(mockStreamingScoringEngine).cancelActiveGesture()
            verify(mockOutputBridge).coordinateStateClear()
        }
    }

    @Test
    fun `invalidateComposingStateOnCursorJump delegates to outputBridge`() {
        coordinator.invalidateComposingStateOnCursorJump()
        verify(mockOutputBridge).invalidateComposingStateOnCursorJump()
    }

    @Test
    fun `clearSecureFieldState calls clearInternalStateOnly on inputState`() {
        coordinator.clearSecureFieldState()
        verify(mockInputState).clearInternalStateOnly()
    }

    @Test
    fun `clearSecureFieldState calls finishComposingText on outputBridge`() {
        coordinator.clearSecureFieldState()
        verify(mockOutputBridge).finishComposingText()
    }

    @Test
    fun `clearSecureFieldState clears spellCheckManager caches`() {
        coordinator.clearSecureFieldState()
        verify(mockSpellCheckManager).clearCaches()
        verify(mockSpellCheckManager).clearBlacklist()
    }

    @Test
    fun `clearSecureFieldState clears textInputProcessor caches`() {
        coordinator.clearSecureFieldState()
        verify(mockTextInputProcessor).clearCaches()
    }

    @Test
    fun `clearSecureFieldState clears wordLearningEngine current language cache`() {
        coordinator.clearSecureFieldState()
        verify(mockWordLearningEngine).clearCurrentLanguageCache()
    }
}
