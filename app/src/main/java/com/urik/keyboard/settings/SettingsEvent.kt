package com.urik.keyboard.settings

/**
 * Events emitted by settings ViewModels for UI feedback.
 */
sealed interface SettingsEvent {
    /**
     * Error events requiring user notification.
     */
    sealed interface Error : SettingsEvent {
        data object KeySizeUpdateFailed : Error

        data object KeyLabelSizeUpdateFailed : Error

        data object SpellCheckToggleFailed : Error

        data object SuggestionToggleFailed : Error

        data object SuggestionCountUpdateFailed : Error

        data object WordLearningToggleFailed : Error

        data object ClipboardToggleFailed : Error

        data object LanguageUpdateFailed : Error

        data object HapticFeedbackToggleFailed : Error

        data object VibrationStrengthUpdateFailed : Error

        data object DoubleSpacePeriodToggleFailed : Error

        data object AutoCapitalizationToggleFailed : Error

        data object SwipeToggleFailed : Error

        data object SpacebarCursorToggleFailed : Error

        data object CursorSpeedUpdateFailed : Error

        data object BackspaceSwipeToggleFailed : Error

        data object LongPressPunctuationUpdateFailed : Error

        data object LongPressDurationUpdateFailed : Error

        data object NumberRowToggleFailed : Error

        data object SpaceBarSizeUpdateFailed : Error

        data object AlternativeLayoutUpdateFailed : Error

        data object ClearLearnedWordsFailed : Error

        data object ResetToDefaultsFailed : Error

        data object DictionaryExportFailed : Error

        data object DictionaryImportFailed : Error
    }

    /**
     * Success events requiring user confirmation.
     */
    sealed interface Success : SettingsEvent {
        data object LearnedWordsCleared : Success

        data object SettingsReset : Success

        data class DictionaryExported(
            val wordCount: Int,
        ) : Success

        data class DictionaryImported(
            val newWords: Int,
            val updatedWords: Int,
        ) : Success
    }
}
