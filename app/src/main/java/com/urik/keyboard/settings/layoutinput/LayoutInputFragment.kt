package com.urik.keyboard.settings.layoutinput

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
import com.urik.keyboard.settings.SpaceBarSize
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings fragment for keyboard layout and input configuration.
 *
 * Manages number row visibility and space bar sizing preferences.
 */
@AndroidEntryPoint
class LayoutInputFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: LayoutInputViewModel
    private lateinit var eventHandler: SettingsEventHandler

    private lateinit var numberRowPref: SwitchPreferenceCompat
    private lateinit var spaceBarPref: ListPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[LayoutInputViewModel::class.java]
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        numberRowPref =
            SwitchPreferenceCompat(context).apply {
                key = "show_number_row"
                title = resources.getString(R.string.layout_settings_show_number_row)
                summaryOn = resources.getString(R.string.layout_settings_number_row_on)
                summaryOff = resources.getString(R.string.layout_settings_number_row_off)
            }
        screen.addPreference(numberRowPref)

        spaceBarPref =
            ListPreference(context).apply {
                key = "space_bar_size"
                title = resources.getString(R.string.layout_settings_space_bar_size)
                entries = SpaceBarSize.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = SpaceBarSize.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        screen.addPreference(spaceBarPref)

        preferenceScreen = screen
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        numberRowPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateShowNumberRow(newValue as Boolean)
            true
        }

        spaceBarPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSpaceBarSize(SpaceBarSize.valueOf(newValue as String))
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        numberRowPref.isChecked = state.showNumberRow
                        spaceBarPref.value = state.spaceBarSize.name
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
