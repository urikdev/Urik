package com.urik.keyboard.ui.animation

import kotlinx.coroutines.delay
import kotlin.random.Random

class AnimationSequencer(
    private val timing: AnimationTiming = AnimationTiming(),
) {
    suspend fun animateSequence(
        fullSentence: String,
        typos: List<TypoConfig>,
        onStateChange: (TypewriterState) -> Unit,
    ) {
        delay(timing.initialDelay)

        var currentText = ""
        var currentIndex = 0

        val sortedTypos = typos.sortedBy { it.startIndex }
        var typoIndex = 0

        while (currentIndex < fullSentence.length) {
            val currentTypo = sortedTypos.getOrNull(typoIndex)

            if (currentTypo != null && currentIndex == currentTypo.startIndex) {
                currentText =
                    typeWord(
                        currentText,
                        currentTypo.typoWord,
                        currentIndex,
                        onStateChange,
                        isTypo = true,
                    )
                currentIndex = currentTypo.startIndex + currentTypo.typoWord.length

                delay(timing.preErrorPause)

                onStateChange(
                    TypewriterState.ShowingError(
                        text = currentText,
                        errorStart = currentTypo.startIndex,
                        errorEnd = currentIndex,
                        cursorVisible = true,
                    ),
                )

                delay(timing.errorFadeIn)

                onStateChange(
                    TypewriterState.ShowingSuggestions(
                        text = currentText,
                        errorStart = currentTypo.startIndex,
                        errorEnd = currentIndex,
                        suggestions = currentTypo.suggestions,
                        cursorVisible = true,
                    ),
                )

                delay(timing.suggestionReadPause)

                onStateChange(
                    TypewriterState.SelectingSuggestion(
                        text = currentText,
                        errorStart = currentTypo.startIndex,
                        errorEnd = currentIndex,
                        selectedSuggestion = currentTypo.correctWord,
                        suggestions = currentTypo.suggestions,
                    ),
                )

                delay(timing.tapRippleDuration)

                currentText =
                    currentText.substring(0, currentTypo.startIndex) +
                    currentTypo.correctWord +
                    currentText.substring(currentIndex)

                currentIndex = currentTypo.startIndex + currentTypo.correctWord.length

                onStateChange(
                    TypewriterState.Typing(
                        text = currentText,
                        cursorVisible = true,
                    ),
                )

                delay(timing.suggestionBarDisappear)

                typoIndex++
            } else {
                val char = fullSentence[currentIndex]
                currentText += char

                onStateChange(
                    TypewriterState.Typing(
                        text = currentText,
                        cursorVisible = true,
                    ),
                )

                currentIndex++

                val charDelay =
                    when {
                        char.isWhitespace() -> timing.spaceDelay
                        char in ".,!?;:" -> timing.punctuationDelay
                        else -> timing.baseTypingSpeed + Random.nextLong(-timing.typingVariance, timing.typingVariance)
                    }

                delay(charDelay)
            }
        }

        onStateChange(TypewriterState.Complete(text = currentText))
        delay(timing.completionPause)
    }

    private suspend fun typeWord(
        currentText: String,
        word: String,
        startIndex: Int,
        onStateChange: (TypewriterState) -> Unit,
        isTypo: Boolean,
    ): String {
        var text = currentText
        val wordStartIndex = startIndex

        if (isTypo) {
            delay(timing.preTypoPause)
        }

        word.forEachIndexed { index, char ->
            text += char

            if (isTypo && index >= 2) {
                onStateChange(
                    TypewriterState.ComposingWord(
                        text = text,
                        composingStart = wordStartIndex,
                        composingEnd = wordStartIndex + index + 1,
                        cursorVisible = true,
                    ),
                )

                if (index == 2) {
                    delay(timing.underlineFadeIn)
                }
            } else {
                onStateChange(
                    TypewriterState.Typing(
                        text = text,
                        cursorVisible = true,
                    ),
                )
            }

            val charDelay = timing.baseTypingSpeed + Random.nextLong(-timing.typingVariance, timing.typingVariance)
            delay(charDelay)
        }

        return text
    }
}
