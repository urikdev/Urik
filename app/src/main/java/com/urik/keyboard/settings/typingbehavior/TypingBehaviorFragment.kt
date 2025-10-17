package com.urik.keyboard.settings.typingbehavior

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
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings fragment for typing behavior configuration.
 *
 * Manages double-space period shortcut and long press duration preferences.
 */
@AndroidEntryPoint
class TypingBehaviorFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: TypingBehaviorViewModel
    private lateinit var eventHandler: SettingsEventHandler

    private lateinit var doubleSpacePref: SwitchPreferenceCompat
    private lateinit var longPressPref: ListPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[TypingBehaviorViewModel::class.java]
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        doubleSpacePref =
            SwitchPreferenceCompat(context).apply {
                key = "double_space_period"
                title = resources.getString(R.string.typing_settings_double_space_period)
                summaryOn = resources.getString(R.string.typing_settings_double_space_on)
                summaryOff = resources.getString(R.string.typing_settings_double_space_off)
            }
        screen.addPreference(doubleSpacePref)

        longPressPref =
            ListPreference(context).apply {
                key = "long_press_duration"
                title = resources.getString(R.string.typing_settings_long_press_duration)
                entries = LongPressDuration.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = LongPressDuration.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        screen.addPreference(longPressPref)

        preferenceScreen = screen
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        doubleSpacePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateDoubleSpacePeriod(newValue as Boolean)
            true
        }

        longPressPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateLongPressDuration(LongPressDuration.valueOf(newValue as String))
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        doubleSpacePref.isChecked = state.doubleSpacePeriod
                        longPressPref.value = state.longPressDuration.name
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
