package com.urik.keyboard.settings

/**
 * Events emitted by settings ViewModels for UI feedback.
 */
sealed interface SettingsEvent {
    /**
     * Error events requiring user notification.
     */
    sealed interface Error : SettingsEvent {
        data object ThemeUpdateFailed : Error

        data object KeySizeUpdateFailed : Error

        data object KeyLabelSizeUpdateFailed : Error

        data object RepeatKeyDelayUpdateFailed : Error

        data object SuggestionToggleFailed : Error

        data object SuggestionCountUpdateFailed : Error

        data object WordLearningToggleFailed : Error

        data object LanguageUpdateFailed : Error

        data object KeyClickSoundToggleFailed : Error

        data object SoundVolumeUpdateFailed : Error

        data object HapticFeedbackToggleFailed : Error

        data object VibrationStrengthUpdateFailed : Error

        data object DoubleSpacePeriodToggleFailed : Error

        data object LongPressDurationUpdateFailed : Error

        data object NumberRowToggleFailed : Error

        data object SpaceBarSizeUpdateFailed : Error

        data object ClearLearnedWordsFailed : Error

        data object ResetToDefaultsFailed : Error
    }

    /**
     * Success events requiring user confirmation.
     */
    sealed interface Success : SettingsEvent {
        data object LearnedWordsCleared : Success

        data object SettingsReset : Success
    }
}
