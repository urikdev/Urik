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
        val message =
            when (event) {
                is SettingsEvent.Error.KeySizeUpdateFailed -> {
                    context.getString(R.string.error_update_key_size)
                }

                is SettingsEvent.Error.KeyLabelSizeUpdateFailed -> {
                    context.getString(R.string.error_update_key_label_size)
                }

                is SettingsEvent.Error.SpellCheckToggleFailed -> {
                    context.getString(R.string.error_update_spell_check)
                }

                is SettingsEvent.Error.SuggestionToggleFailed -> {
                    context.getString(R.string.error_update_suggestions)
                }

                is SettingsEvent.Error.SuggestionCountUpdateFailed -> {
                    context.getString(R.string.error_update_suggestion_count)
                }

                is SettingsEvent.Error.WordLearningToggleFailed -> {
                    context.getString(R.string.error_update_word_learning)
                }

                is SettingsEvent.Error.ClipboardToggleFailed -> {
                    context.getString(R.string.error_update_clipboard)
                }

                is SettingsEvent.Error.LanguageUpdateFailed -> {
                    context.getString(R.string.error_update_language)
                }

                is SettingsEvent.Error.HapticFeedbackToggleFailed -> {
                    context.getString(R.string.error_update_haptic_feedback)
                }

                is SettingsEvent.Error.VibrationStrengthUpdateFailed -> {
                    context.getString(R.string.error_update_vibration_strength)
                }

                is SettingsEvent.Error.DoubleSpacePeriodToggleFailed -> {
                    context.getString(R.string.error_update_double_space_period)
                }

                is SettingsEvent.Error.AutoCapitalizationToggleFailed -> {
                    context.getString(R.string.error_update_auto_capitalization)
                }

                is SettingsEvent.Error.SwipeToggleFailed -> {
                    context.getString(R.string.error_update_swipe)
                }

                is SettingsEvent.Error.SpacebarCursorToggleFailed -> {
                    context.getString(R.string.error_update_spacebar_cursor)
                }

                is SettingsEvent.Error.CursorSpeedUpdateFailed -> {
                    context.getString(R.string.error_update_cursor_speed)
                }

                is SettingsEvent.Error.BackspaceSwipeToggleFailed -> {
                    context.getString(R.string.error_update_backspace_swipe)
                }

                is SettingsEvent.Error.LongPressPunctuationUpdateFailed -> {
                    context.getString(
                        R.string.error_update_long_press_punctuation,
                    )
                }

                is SettingsEvent.Error.LongPressDurationUpdateFailed -> {
                    context.getString(R.string.error_update_long_press_duration)
                }

                is SettingsEvent.Error.NumberRowToggleFailed -> {
                    context.getString(R.string.error_update_number_row)
                }

                is SettingsEvent.Error.SpaceBarSizeUpdateFailed -> {
                    context.getString(R.string.error_update_space_bar_size)
                }

                is SettingsEvent.Error.AlternativeLayoutUpdateFailed -> {
                    context.getString(R.string.error_update_alternative_layout)
                }

                is SettingsEvent.Error.ClearLearnedWordsFailed -> {
                    context.getString(R.string.error_clear_learned_words)
                }

                is SettingsEvent.Error.ResetToDefaultsFailed -> {
                    context.getString(R.string.error_reset_to_defaults)
                }

                is SettingsEvent.Error.DictionaryExportFailed -> {
                    context.getString(R.string.error_dictionary_export)
                }

                is SettingsEvent.Error.DictionaryImportFailed -> {
                    context.getString(R.string.error_dictionary_import)
                }

                is SettingsEvent.Success.LearnedWordsCleared -> {
                    context.getString(R.string.success_learned_words_cleared)
                }

                is SettingsEvent.Success.SettingsReset -> {
                    context.getString(R.string.success_settings_reset)
                }

                is SettingsEvent.Success.DictionaryExported -> {
                    context.getString(R.string.success_dictionary_exported, event.wordCount)
                }

                is SettingsEvent.Success.DictionaryImported -> {
                    context.getString(
                        R.string.success_dictionary_imported,
                        event.newWords,
                        event.updatedWords,
                    )
                }
            }

        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
