package com.urik.keyboard.ui.animation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

class AnimationSequencer(
    private val timing: AnimationTiming = AnimationTiming(),
) {
    private val isPausedFlow = MutableStateFlow(false)

    fun pause() {
        isPausedFlow.value = true
    }

    fun resume() {
        isPausedFlow.value = false
    }

    private suspend fun checkPause() {
        isPausedFlow.first { !it }
    }

    suspend fun animateSequence(
        fullSentence: String,
        typos: List<TypoConfig>,
        onStateChange: (TypewriterState) -> Unit,
    ) {
        checkPause()
        delay(timing.initialDelay)

        var currentText = ""
        var currentIndex = 0

        val sortedTypos = typos.sortedBy { it.startIndex }
        var typoIndex = 0

        while (currentIndex < fullSentence.length) {
            checkPause()

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

                checkPause()
                delay(timing.preErrorPause)

                onStateChange(
                    TypewriterState.ShowingError(
                        text = currentText,
                        errorStart = currentTypo.startIndex,
                        errorEnd = currentIndex,
                        cursorVisible = true,
                    ),
                )

                checkPause()
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

                checkPause()
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

                checkPause()
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

                checkPause()
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

                checkPause()
                delay(charDelay)
            }
        }

        onStateChange(TypewriterState.Complete(text = currentText))
        checkPause()
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
            checkPause()
            delay(timing.preTypoPause)
        }

        word.forEachIndexed { index, char ->
            checkPause()

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
                    checkPause()
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
            checkPause()
            delay(charDelay)
        }

        return text
    }
}
