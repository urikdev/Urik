package com.urik.keyboard.settings.soundfeedback

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
import com.urik.keyboard.settings.VibrationStrength
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings fragment for sound and haptic feedback configuration.
 *
 * Manages haptic feedback toggle and vibration strength preferences.
 */
@AndroidEntryPoint
class SoundFeedbackFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: SoundFeedbackViewModel
    private lateinit var eventHandler: SettingsEventHandler

    private lateinit var hapticPref: SwitchPreferenceCompat
    private lateinit var vibrationPref: ListPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[SoundFeedbackViewModel::class.java]
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        hapticPref =
            SwitchPreferenceCompat(context).apply {
                key = "haptic_feedback"
                title = resources.getString(R.string.feedback_settings_haptic_feedback)
                summaryOn = resources.getString(R.string.feedback_settings_haptic_on)
                summaryOff = resources.getString(R.string.feedback_settings_haptic_off)
            }
        screen.addPreference(hapticPref)

        vibrationPref =
            ListPreference(context).apply {
                key = "vibration_strength"
                title = resources.getString(R.string.feedback_settings_vibration_strength)
                entries = VibrationStrength.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = VibrationStrength.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        screen.addPreference(vibrationPref)

        preferenceScreen = screen
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        hapticPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHapticFeedback(newValue as Boolean)
            true
        }

        vibrationPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateVibrationStrength(VibrationStrength.valueOf(newValue as String))
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        hapticPref.isChecked = state.hapticFeedback
                        vibrationPref.value = state.vibrationStrength.name
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
