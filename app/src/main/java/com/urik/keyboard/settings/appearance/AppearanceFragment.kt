package com.urik.keyboard.settings.appearance

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.urik.keyboard.R
import com.urik.keyboard.settings.KeyLabelSize
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.settings.SettingsEventHandler
import com.urik.keyboard.settings.Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings fragment for appearance customization.
 *
 * Manages theme, key sizing, label sizing, and repeat key timing preferences.
 */
@AndroidEntryPoint
class AppearanceFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: AppearanceViewModel
    private lateinit var eventHandler: SettingsEventHandler

    private lateinit var themePref: ListPreference
    private lateinit var keySizePref: ListPreference
    private lateinit var labelSizePref: ListPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[AppearanceViewModel::class.java]
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        themePref =
            ListPreference(context).apply {
                key = "theme"
                title = resources.getString(R.string.appearance_settings_theme)
                entries = Theme.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = Theme.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }

        keySizePref =
            ListPreference(context).apply {
                key = "key_size"
                title = resources.getString(R.string.appearance_settings_key_size)
                entries = KeySize.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = KeySize.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }

        labelSizePref =
            ListPreference(context).apply {
                key = "key_label_size"
                title = resources.getString(R.string.appearance_settings_key_label_size)
                entries = KeyLabelSize.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = KeyLabelSize.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }

        screen.addPreference(themePref)
        screen.addPreference(keySizePref)
        screen.addPreference(labelSizePref)

        preferenceScreen = screen
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        themePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateTheme(Theme.valueOf(newValue as String))
            true
        }

        keySizePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateKeySize(KeySize.valueOf(newValue as String))
            true
        }

        labelSizePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateKeyLabelSize(KeyLabelSize.valueOf(newValue as String))
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        themePref.value = state.theme.name
                        keySizePref.value = state.keySize.name
                        labelSizePref.value = state.keyLabelSize.name
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
