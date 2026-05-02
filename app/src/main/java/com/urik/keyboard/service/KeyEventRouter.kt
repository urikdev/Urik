package com.urik.keyboard.service

import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import javax.inject.Inject
import javax.inject.Singleton

interface KeyEventHandler {
    fun onLetterInput(char: String)
    fun onNonLetterInput(char: String)
    fun onBackspace()
    fun onSpace()
    fun onEnterAction(imeAction: Int)
    fun onShift()
    fun onCapsLock()
    fun onModeSwitch(mode: KeyboardMode)
    fun onDakuten()
    fun onSmallKana()
    fun onLanguageSwitch()
}

@Singleton
class KeyEventRouter
@Inject
constructor() {
    private var handler: KeyEventHandler? = null
    private var searchInputHandler: (KeyboardKey) -> Boolean = { false }
    private var viewModel: KeyboardViewModel? = null

    fun configure(
        handler: KeyEventHandler,
        searchInputHandler: (KeyboardKey) -> Boolean,
        viewModel: KeyboardViewModel
    ) {
        this.handler = handler
        this.searchInputHandler = searchInputHandler
        this.viewModel = viewModel
    }

    fun route(key: KeyboardKey) {
        if (searchInputHandler(key)) return

        when (key) {
            is KeyboardKey.Character -> {
                val char = viewModel?.getCharacterForInput(key) ?: key.value
                viewModel?.clearShiftAfterCharacter(key)
                if (key.type == KeyboardKey.KeyType.LETTER) {
                    handler?.onLetterInput(char)
                } else {
                    handler?.onNonLetterInput(char)
                }
            }
            is KeyboardKey.Action -> routeAction(key)
            KeyboardKey.Spacer -> {}
            is KeyboardKey.FlickKey -> {}
        }
    }

    fun routeAction(action: KeyboardKey.Action) {
        when (action.action) {
            KeyboardKey.ActionType.BACKSPACE -> handler?.onBackspace()
            KeyboardKey.ActionType.SPACE -> handler?.onSpace()
            KeyboardKey.ActionType.ENTER,
            KeyboardKey.ActionType.SEARCH,
            KeyboardKey.ActionType.SEND,
            KeyboardKey.ActionType.DONE,
            KeyboardKey.ActionType.GO,
            KeyboardKey.ActionType.NEXT,
            KeyboardKey.ActionType.PREVIOUS -> handler?.onEnterAction(
                when (action.action) {
                    KeyboardKey.ActionType.SEARCH -> EditorInfo.IME_ACTION_SEARCH
                    KeyboardKey.ActionType.SEND -> EditorInfo.IME_ACTION_SEND
                    KeyboardKey.ActionType.DONE -> EditorInfo.IME_ACTION_DONE
                    KeyboardKey.ActionType.GO -> EditorInfo.IME_ACTION_GO
                    KeyboardKey.ActionType.NEXT -> EditorInfo.IME_ACTION_NEXT
                    KeyboardKey.ActionType.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
                    else -> EditorInfo.IME_ACTION_NONE
                }
            )
            KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> handler?.onModeSwitch(KeyboardMode.LETTERS)
            KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> handler?.onModeSwitch(KeyboardMode.NUMBERS)
            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> handler?.onModeSwitch(KeyboardMode.SYMBOLS)
            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY -> handler?.onModeSwitch(
                KeyboardMode.SYMBOLS_SECONDARY
            )
            KeyboardKey.ActionType.SHIFT -> handler?.onShift()
            KeyboardKey.ActionType.CAPS_LOCK -> handler?.onCapsLock()
            KeyboardKey.ActionType.LANGUAGE_SWITCH -> handler?.onLanguageSwitch()
            KeyboardKey.ActionType.DAKUTEN -> handler?.onDakuten()
            KeyboardKey.ActionType.SMALL_KANA -> handler?.onSmallKana()
        }
    }
}
