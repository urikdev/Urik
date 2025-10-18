package com.urik.keyboard.ui.animation

/**
 * State machine for typewriter animation sequence.
 *
 * Flow:
 * Idle → Typing → ComposingWord → ShowingError → ShowingSuggestions → SelectingSuggestion → Typing → Complete → Idle
 */
sealed class TypewriterState {
    /**
     * Initial state before animation starts.
     */
    data object Idle : TypewriterState()

    /**
     * Currently typing characters normally.
     *
     * @property text Current text buffer
     * @property cursorVisible Whether cursor should be visible
     */
    data class Typing(
        val text: String,
        val cursorVisible: Boolean = true,
    ) : TypewriterState()

    /**
     * Word is being composed with underline (before error detection).
     *
     * @property text Full text including composing word
     * @property composingStart Start index of composing region
     * @property composingEnd End index of composing region
     * @property cursorVisible Cursor blink state
     */
    data class ComposingWord(
        val text: String,
        val composingStart: Int,
        val composingEnd: Int,
        val cursorVisible: Boolean = true,
    ) : TypewriterState()

    /**
     * Misspelled word highlighted with red background and white text.
     *
     * @property text Full text
     * @property errorStart Start index of error
     * @property errorEnd End index of error
     * @property cursorVisible Cursor blink state
     */
    data class ShowingError(
        val text: String,
        val errorStart: Int,
        val errorEnd: Int,
        val cursorVisible: Boolean = true,
    ) : TypewriterState()

    /**
     * Error visible with suggestion bar displayed.
     *
     * @property text Full text
     * @property errorStart Start index of error
     * @property errorEnd End index of error
     * @property suggestions List of suggestions to display
     * @property cursorVisible Cursor blink state
     */
    data class ShowingSuggestions(
        val text: String,
        val errorStart: Int,
        val errorEnd: Int,
        val suggestions: List<String>,
        val cursorVisible: Boolean = true,
    ) : TypewriterState()

    /**
     * User selecting suggestion (ripple effect + text replacement).
     *
     * @property text Text before replacement
     * @property errorStart Start of word being replaced
     * @property errorEnd End of word being replaced
     * @property selectedSuggestion Suggestion being applied
     * @property suggestions All suggestions (for bar visibility)
     */
    data class SelectingSuggestion(
        val text: String,
        val errorStart: Int,
        val errorEnd: Int,
        val selectedSuggestion: String,
        val suggestions: List<String>,
    ) : TypewriterState()

    /**
     * Animation sequence complete, showing final text.
     *
     * @property text Final complete sentence
     */
    data class Complete(
        val text: String,
    ) : TypewriterState()
}

/**
 * Typo configuration for animation sequence.
 *
 * @property correctWord The correct spelling
 * @property typoWord The misspelled version
 * @property suggestions List of suggestions to show
 * @property startIndex Character index where typo starts in full sentence
 * @property endIndex Character index where typo ends in full sentence
 */
data class TypoConfig(
    val correctWord: String,
    val typoWord: String,
    val suggestions: List<String>,
    val startIndex: Int,
    val endIndex: Int,
)

/**
 * Animation timing configuration (all values in milliseconds).
 */
data class AnimationTiming(
    val initialDelay: Long = 500L,
    val baseTypingSpeed: Long = 90L,
    val typingVariance: Long = 20L,
    val spaceDelay: Long = 150L,
    val punctuationDelay: Long = 130L,
    val preTypoPause: Long = 200L,
    val underlineFadeIn: Long = 150L,
    val preErrorPause: Long = 300L,
    val errorFadeIn: Long = 250L,
    val suggestionBarAppear: Long = 300L,
    val suggestionReadPause: Long = 800L,
    val tapRippleDuration: Long = 150L,
    val textReplaceDuration: Long = 100L,
    val suggestionBarDisappear: Long = 200L,
    val completionPause: Long = 10000L,
    val cursorBlinkCycle: Long = 530L,
)
