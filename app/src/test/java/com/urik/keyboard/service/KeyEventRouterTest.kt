package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class KeyEventRouterTest {
    private lateinit var mockViewModel: KeyboardViewModel
    private lateinit var mockHandler: KeyEventHandler
    private lateinit var router: KeyEventRouter

    @Before
    fun setup() {
        mockViewModel = mock()
        mockHandler = mock()
        router = KeyEventRouter()
        router.configure(handler = mockHandler, searchInputHandler = { false }, viewModel = mockViewModel)
    }

    @Test
    fun `route Character LETTER type calls onLetterInput with resolved char`() {
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        ).thenReturn("a")
        router.route(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        verify(mockHandler).onLetterInput("a")
        verify(mockHandler, never()).onNonLetterInput("a")
    }

    @Test
    fun `route Character SYMBOL type calls onNonLetterInput`() {
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("!", KeyboardKey.KeyType.SYMBOL))
        ).thenReturn("!")
        router.route(KeyboardKey.Character("!", KeyboardKey.KeyType.SYMBOL))
        verify(mockHandler).onNonLetterInput("!")
        verify(mockHandler, never()).onLetterInput("!")
    }

    @Test
    fun `route Action BACKSPACE calls onBackspace`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE))
        verify(mockHandler).onBackspace()
    }

    @Test
    fun `route Action SPACE calls onSpace`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
        verify(mockHandler).onSpace()
    }

    @Test
    fun `route Action SHIFT calls onShift`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SHIFT))
        verify(mockHandler).onShift()
    }

    @Test
    fun `route Action CAPS_LOCK calls onCapsLock`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.CAPS_LOCK))
        verify(mockHandler).onCapsLock()
    }

    @Test
    fun `route Action MODE_SWITCH_LETTERS calls onModeSwitch with LETTERS`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_LETTERS))
        verify(mockHandler).onModeSwitch(KeyboardMode.LETTERS)
    }

    @Test
    fun `route Action MODE_SWITCH_NUMBERS calls onModeSwitch with NUMBERS`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_NUMBERS))
        verify(mockHandler).onModeSwitch(KeyboardMode.NUMBERS)
    }

    @Test
    fun `route Action DAKUTEN calls onDakuten`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.DAKUTEN))
        verify(mockHandler).onDakuten()
    }

    @Test
    fun `route Action SMALL_KANA calls onSmallKana`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SMALL_KANA))
        verify(mockHandler).onSmallKana()
    }

    @Test
    fun `route Action LANGUAGE_SWITCH calls onLanguageSwitch`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.LANGUAGE_SWITCH))
        verify(mockHandler).onLanguageSwitch()
    }

    @Test
    fun `route returns early when searchInputHandler intercepts`() {
        router.configure(handler = mockHandler, searchInputHandler = { true }, viewModel = mockViewModel)
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        ).thenReturn("a")
        router.route(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        verify(mockHandler, never()).onLetterInput("a")
        verify(mockHandler, never()).onNonLetterInput("a")
    }

    @Test
    fun `route Action ENTER calls onEnterAction`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.ENTER))
        verify(mockHandler).onEnterAction(android.view.inputmethod.EditorInfo.IME_ACTION_NONE)
    }
}
