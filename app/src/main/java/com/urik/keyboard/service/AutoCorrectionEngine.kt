package com.urik.keyboard.service

import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.utils.UrlEmailDetector
import javax.inject.Inject
import javax.inject.Singleton

sealed class AutocorrectDecision {
    data object None : AutocorrectDecision()
    data class Correct(val suggestion: String) : AutocorrectDecision()
    data object Pause : AutocorrectDecision()
    data object ContractionBypass : AutocorrectDecision()
    data class Suggestions(val list: List<SpellingSuggestion>) : AutocorrectDecision()
}

@Singleton
class AutoCorrectionEngine
@Inject
constructor(private val textInputProcessor: TextInputProcessor) {
    private fun isSafeForAutocorrect(suggestion: SpellingSuggestion): Boolean =
        suggestion.word.all { it.isLetter() || it == '\'' || it == '-' }

    suspend fun decide(
        buffer: String,
        spellCheckEnabled: Boolean,
        autocorrectionEnabled: Boolean,
        pauseOnMisspelledWord: Boolean,
        lastAutocorrection: LastAutocorrection?,
        textBeforeCursor: String,
        nextChar: String
    ): AutocorrectDecision {
        if (!spellCheckEnabled || buffer.length < TextProcessingConstants.MIN_SPELL_CHECK_LENGTH) {
            return AutocorrectDecision.None
        }

        if (UrlEmailDetector.isUrlOrEmailContext(buffer, textBeforeCursor, nextChar)) {
            return AutocorrectDecision.None
        }

        val isValid = textInputProcessor.validateWord(buffer)

        val bypassForContraction = isValid &&
            autocorrectionEnabled &&
            lastAutocorrection == null &&
            textInputProcessor.hasDominantContractionForm(buffer)

        if (bypassForContraction) {
            return AutocorrectDecision.ContractionBypass
        }

        if (isValid) {
            if (!autocorrectionEnabled || lastAutocorrection != null) {
                return AutocorrectDecision.None
            }
            val typedFreq = textInputProcessor.getDictFrequency(buffer)
            val userFreq = textInputProcessor.getUserFrequency(buffer)
            val effectiveTypedFreq = typedFreq + userFreq * USER_FREQ_DICT_SCALE
            if (effectiveTypedFreq >= FREQUENCY_NOISE_THRESHOLD) {
                return AutocorrectDecision.None
            }
            val suggestions = textInputProcessor.getSuggestions(buffer)
            if (suggestions.isEmpty()) {
                return AutocorrectDecision.None
            }
            val topSuggestion = suggestions.first()
            if (topSuggestion.word.equals(buffer, ignoreCase = true)) {
                return AutocorrectDecision.None
            }
            val suggFreq = textInputProcessor.getDictFrequency(topSuggestion.word)
            if (effectiveTypedFreq > 0 &&
                suggFreq > effectiveTypedFreq * FREQUENCY_OVERRIDE_RATIO &&
                isSafeForAutocorrect(topSuggestion)
            ) {
                return AutocorrectDecision.Correct(topSuggestion.word)
            }
            return AutocorrectDecision.None
        }

        val suggestions = textInputProcessor.getSuggestions(buffer)

        if (pauseOnMisspelledWord) {
            return AutocorrectDecision.Pause
        }

        if (autocorrectionEnabled && suggestions.isNotEmpty() && lastAutocorrection == null) {
            val top = suggestions.first()
            if (isSafeForAutocorrect(top)) {
                return AutocorrectDecision.Correct(top.word)
            }
        }

        return AutocorrectDecision.Suggestions(suggestions)
    }

    private companion object {
        const val FREQUENCY_OVERRIDE_RATIO = 100L
        const val FREQUENCY_NOISE_THRESHOLD = 10_000L
        const val USER_FREQ_DICT_SCALE = 10L
    }
}
