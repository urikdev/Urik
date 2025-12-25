package com.urik.keyboard.settings.languages

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceFragmentCompat
import com.urik.keyboard.R
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings fragment for keyboard language selection.
 *
 * Displays supported languages as radio buttons for primary language selection.
 */
@AndroidEntryPoint
class LanguagesFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: LanguagesViewModel
    private lateinit var eventHandler: SettingsEventHandler
    private lateinit var languageRadioButtons: Map<String, CheckBoxPreference>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[LanguagesViewModel::class.java]
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        val radioButtons = mutableMapOf<String, CheckBoxPreference>()
        val languageDisplayNames = KeyboardSettings.getLanguageDisplayNames()

        KeyboardSettings.SUPPORTED_LANGUAGES.forEach { languageTag ->
            val radioButton =
                CheckBoxPreference(context).apply {
                    key = "language_$languageTag"
                    isPersistent = false
                    title = languageDisplayNames[languageTag]
                }
            radioButtons[languageTag] = radioButton
            screen.addPreference(radioButton)
        }

        languageRadioButtons = radioButtons
        preferenceScreen = screen
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        languageRadioButtons.forEach { (languageTag, checkbox) ->
            checkbox.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) {
                    val currentCheckedCount =
                        languageRadioButtons.values.count { it.isChecked }

                    if (currentCheckedCount >= KeyboardSettings.MAX_ACTIVE_LANGUAGES) {
                        Toast
                            .makeText(
                                requireContext(),
                                R.string.max_languages_reached,
                                Toast.LENGTH_SHORT,
                            ).show()
                        return@setOnPreferenceChangeListener false
                    }
                }

                viewModel.toggleLanguage(languageTag)
                true
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        languageRadioButtons.forEach { (languageTag, checkbox) ->
                            checkbox.isChecked = (languageTag in state.activeLanguages)

                            if (languageTag == state.primaryLayoutLanguage) {
                                checkbox.summary = getString(R.string.primary_layout_language)
                            } else {
                                checkbox.summary = null
                            }
                        }
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
