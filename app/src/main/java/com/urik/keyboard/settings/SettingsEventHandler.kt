package com.urik.keyboard.settings

import android.content.Context
import android.widget.Toast
import com.urik.keyboard.R

/**
 * Handles settings events by displaying localized user feedback.
 *
 * Should be instantiated per-Fragment to respect lifecycle.
 */
class SettingsEventHandler(
    private val context: Context,
) {
    fun handle(event: SettingsEvent) {
        val messageRes =
            when (event) {
                is SettingsEvent.Error.KeySizeUpdateFailed -> R.string.error_update_key_size
                is SettingsEvent.Error.KeyLabelSizeUpdateFailed -> R.string.error_update_key_label_size
                is SettingsEvent.Error.SpellCheckToggleFailed -> R.string.error_update_spell_check
                is SettingsEvent.Error.SuggestionToggleFailed -> R.string.error_update_suggestions
                is SettingsEvent.Error.SuggestionCountUpdateFailed -> R.string.error_update_suggestion_count
                is SettingsEvent.Error.WordLearningToggleFailed -> R.string.error_update_word_learning
                is SettingsEvent.Error.ClipboardToggleFailed -> R.string.error_update_clipboard
                is SettingsEvent.Error.LanguageUpdateFailed -> R.string.error_update_language
                is SettingsEvent.Error.HapticFeedbackToggleFailed -> R.string.error_update_haptic_feedback
                is SettingsEvent.Error.VibrationStrengthUpdateFailed -> R.string.error_update_vibration_strength
                is SettingsEvent.Error.DoubleSpacePeriodToggleFailed -> R.string.error_update_double_space_period
                is SettingsEvent.Error.AutoCapitalizationToggleFailed -> R.string.error_update_auto_capitalization
                is SettingsEvent.Error.SwipeToggleFailed -> R.string.error_update_swipe
                is SettingsEvent.Error.SpacebarCursorToggleFailed -> R.string.error_update_spacebar_cursor
                is SettingsEvent.Error.CursorSpeedUpdateFailed -> R.string.error_update_cursor_speed
                is SettingsEvent.Error.BackspaceSwipeToggleFailed -> R.string.error_update_backspace_swipe
                is SettingsEvent.Error.LongPressPunctuationModeUpdateFailed -> R.string.error_update_long_press_punctuation
                is SettingsEvent.Error.LongPressDurationUpdateFailed -> R.string.error_update_long_press_duration
                is SettingsEvent.Error.NumberRowToggleFailed -> R.string.error_update_number_row
                is SettingsEvent.Error.SpaceBarSizeUpdateFailed -> R.string.error_update_space_bar_size
                is SettingsEvent.Error.AlternativeLayoutUpdateFailed -> R.string.error_update_alternative_layout
                is SettingsEvent.Error.ClearLearnedWordsFailed -> R.string.error_clear_learned_words
                is SettingsEvent.Error.ResetToDefaultsFailed -> R.string.error_reset_to_defaults
                is SettingsEvent.Success.LearnedWordsCleared -> R.string.success_learned_words_cleared
                is SettingsEvent.Success.SettingsReset -> R.string.success_settings_reset
            }

        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }
}
