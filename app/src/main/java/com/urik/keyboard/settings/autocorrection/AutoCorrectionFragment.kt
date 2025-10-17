package com.urik.keyboard.settings.autocorrection

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.urik.keyboard.R
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings fragment for auto-correction and suggestion configuration.
 *
 * Manages suggestion visibility, count, and word learning preferences.
 */
@AndroidEntryPoint
class AutoCorrectionFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: AutoCorrectionViewModel
    private lateinit var eventHandler: SettingsEventHandler

    private lateinit var suggestionsPref: SwitchPreferenceCompat
    private lateinit var countPref: ListPreference
    private lateinit var learnPref: SwitchPreferenceCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[AutoCorrectionViewModel::class.java]
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        suggestionsPref =
            SwitchPreferenceCompat(context).apply {
                key = "show_suggestions"
                title = resources.getString(R.string.autocorrect_settings_show_suggestions)
                summaryOn = resources.getString(R.string.autocorrect_settings_suggestions_on)
                summaryOff = resources.getString(R.string.autocorrect_settings_suggestions_off)
            }
        screen.addPreference(suggestionsPref)

        countPref =
            ListPreference(context).apply {
                key = "suggestion_count"
                title = resources.getString(R.string.autocorrect_settings_suggestion_count)
                entries = arrayOf("1", "2", "3")
                entryValues = arrayOf("1", "2", "3")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        screen.addPreference(countPref)

        learnPref =
            SwitchPreferenceCompat(context).apply {
                key = "learn_new_words"
                title = resources.getString(R.string.autocorrect_settings_learn_new_words)
                summaryOn = resources.getString(R.string.autocorrect_settings_learn_new_words_on)
                summaryOff = resources.getString(R.string.autocorrect_settings_learn_new_words_off)
            }
        screen.addPreference(learnPref)

        preferenceScreen = screen
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        suggestionsPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateShowSuggestions(newValue as Boolean)
            true
        }

        countPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSuggestionCount((newValue as String).toInt())
            true
        }

        learnPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateLearnNewWords(newValue as Boolean)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        suggestionsPref.isChecked = state.showSuggestions
                        countPref.value = state.suggestionCount.toString()
                        learnPref.isChecked = state.learnNewWords
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        eventHandler.handle(event)
                    }
                }
            }
        }
    }
}
