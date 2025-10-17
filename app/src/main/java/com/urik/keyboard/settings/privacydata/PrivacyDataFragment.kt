package com.urik.keyboard.settings.privacydata

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.urik.keyboard.R
import com.urik.keyboard.settings.SettingsEventHandler
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings fragment for privacy and data management.
 *
 * Provides options to clear learned words, clear all data, reset settings to defaults,
 * and export error logs for debugging.
 */
@AndroidEntryPoint
class PrivacyDataFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: PrivacyDataViewModel
    private lateinit var eventHandler: SettingsEventHandler
    private var exportErrorLogPref: Preference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PrivacyDataViewModel::class.java]
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        val clearLearnedPref =
            Preference(context).apply {
                key = "clear_learned_words"
                title = resources.getString(R.string.privacy_settings_clear_learned_words)
                summary = resources.getString(R.string.privacy_settings_clear_learned_words_summary)
                setOnPreferenceClickListener {
                    showConfirmDialog(
                        resources.getString(R.string.privacy_settings_clear_learned_words),
                        resources.getString(R.string.privacy_settings_clear_learned_words_confirm),
                    ) {
                        viewModel.clearLearnedWords()
                    }
                    true
                }
            }
        screen.addPreference(clearLearnedPref)

        val clearAllPref =
            Preference(context).apply {
                key = "clear_all_data"
                title = resources.getString(R.string.privacy_settings_clear_learned_words)
                summary = resources.getString(R.string.privacy_settings_clear_learned_words_summary)
                setOnPreferenceClickListener {
                    showConfirmDialog(
                        resources.getString(R.string.privacy_settings_clear_learned_words),
                        resources.getString(R.string.privacy_settings_clear_learned_words_confirm),
                    ) {
                        viewModel.clearAllData()
                    }
                    true
                }
            }
        screen.addPreference(clearAllPref)

        val resetPref =
            Preference(context).apply {
                key = "reset_defaults"
                title = resources.getString(R.string.privacy_settings_reset_to_defaults)
                summary = resources.getString(R.string.privacy_settings_reset_to_defaults_summary)
                setOnPreferenceClickListener {
                    showConfirmDialog(
                        resources.getString(R.string.privacy_settings_reset_to_defaults),
                        resources.getString(R.string.privacy_settings_reset_to_defaults_confirm),
                    ) {
                        viewModel.resetToDefaults()
                    }
                    true
                }
            }
        screen.addPreference(resetPref)

        exportErrorLogPref =
            Preference(context).apply {
                key = "export_error_log"
                title = resources.getString(R.string.privacy_settings_export_error_log)
                summary = resources.getString(R.string.privacy_settings_export_error_log_summary)
                setOnPreferenceClickListener {
                    exportErrorLog()
                    true
                }
            }
        screen.addPreference(exportErrorLogPref!!)

        preferenceScreen = screen
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        updateErrorLogVisibility()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    eventHandler.handle(event)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateErrorLogVisibility()
    }

    private fun updateErrorLogVisibility() {
        exportErrorLogPref?.isVisible = ErrorLogger.getErrorCount() > 0
    }

    private fun exportErrorLog() {
        try {
            val logFile = ErrorLogger.exportLog()
            val uri =
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    logFile,
                )

            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Urik Keyboard Error Log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            startActivity(Intent.createChooser(shareIntent, resources.getString(R.string.privacy_settings_export_error_log)))
        } catch (e: Exception) {
            AlertDialog
                .Builder(requireContext())
                .setTitle(resources.getString(R.string.error_title))
                .setMessage(resources.getString(R.string.privacy_settings_export_error_log_failed))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        onConfirm: () -> Unit,
    ) {
        AlertDialog
            .Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
