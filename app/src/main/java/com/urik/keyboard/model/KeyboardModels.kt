package com.urik.keyboard.model

/**
 * Keyboard input modes determining available characters.
 */
enum class KeyboardMode {
    LETTERS,
    NUMBERS,
    SYMBOLS,
}

/**
 * Current keyboard UI state.
 *
 * Drives layout rendering and key behavior (e.g., shift affects character case).
 */
data class KeyboardState(
    val currentMode: KeyboardMode = KeyboardMode.LETTERS,
    val isShiftPressed: Boolean = false,
    val isCapsLockOn: Boolean = false,
    val isAutoShift: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Layout structure for a specific keyboard mode.
 *
 * Each row contains keys rendered left-to-right or right-to-left based on isRTL.
 */
data class KeyboardLayout(
    val mode: KeyboardMode,
    val rows: List<List<KeyboardKey>>,
    val isRTL: Boolean = false,
    val script: String = "Latn",
)

/**
 * Key types in the keyboard.
 *
 * Character keys insert text, Action keys trigger operations.
 */
sealed class KeyboardKey {
    /**
     * Text-inserting key.
     *
     * @property value Character(s) to insert (can be multi-char for ligatures/emoji)
     * @property type Visual/semantic classification for styling
     */
    data class Character(
        val value: String,
        val type: KeyType,
    ) : KeyboardKey()

    /**
     * Operation key (backspace, enter, mode switch, etc).
     *
     * @property action Operation to perform
     */
    data class Action(
        val action: ActionType,
    ) : KeyboardKey()

    /**
     * Empty spacer for layout alignment.
     */
    data object Spacer : KeyboardKey()

    enum class KeyType {
        LETTER,
        NUMBER,
        PUNCTUATION,
        SYMBOL,
    }

    enum class ActionType {
        SHIFT,
        BACKSPACE,
        SPACE,
        ENTER,
        SEARCH,
        SEND,
        DONE,
        GO,
        NEXT,
        PREVIOUS,
        MODE_SWITCH_LETTERS,
        MODE_SWITCH_NUMBERS,
        MODE_SWITCH_SYMBOLS,
        CAPS_LOCK,
        LANGUAGE_SWITCH,
    }
}

/**
 * Keyboard state change events.
 */
sealed class KeyboardEvent {
    data class KeyPressed(
        val key: KeyboardKey,
    ) : KeyboardEvent()

    data class ModeChanged(
        val mode: KeyboardMode,
    ) : KeyboardEvent()

    data class ShiftStateChanged(
        val isPressed: Boolean,
    ) : KeyboardEvent()

    data object CapsLockToggled : KeyboardEvent()
}
