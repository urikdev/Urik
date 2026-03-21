package com.urik.keyboard.settings.privacydata

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.urik.keyboard.R
import com.urik.keyboard.settings.SettingsEventHandler
import com.urik.keyboard.settings.learnedwords.LearnedWordsFragment
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.AndroidEntryPoint
import java.security.KeyStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.launch

/**
 * Settings fragment for privacy and data management.
 *
 * Provides options to configure clipboard history, clear learned words,
 * reset settings to defaults, and export error logs for debugging.
 */
@AndroidEntryPoint
class PrivacyDataFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: PrivacyDataViewModel
    private lateinit var eventHandler: SettingsEventHandler
    private lateinit var clipboardPref: SwitchPreferenceCompat
    private var exportErrorLogPref: Preference? = null

    private val exportLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    viewModel.exportDictionary(uri)
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    viewModel.importDictionary(uri)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PrivacyDataViewModel::class.java]
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        clipboardPref =
            SwitchPreferenceCompat(context).apply {
                key = "clipboard_enabled"
                isPersistent = false
                title = resources.getString(R.string.privacy_settings_clipboard_history)
                summaryOn = resources.getString(R.string.privacy_settings_clipboard_history_on)
                summaryOff = resources.getString(R.string.privacy_settings_clipboard_history_off)
            }
        screen.addPreference(clipboardPref)

        val clearLearnedPref =
            Preference(context).apply {
                key = "clear_learned_words"
                title = resources.getString(R.string.privacy_settings_clear_learned_words)
                summary = resources.getString(R.string.privacy_settings_clear_learned_words_summary)
                setOnPreferenceClickListener {
                    showConfirmDialog(
                        resources.getString(R.string.privacy_settings_clear_learned_words),
                        resources.getString(R.string.privacy_settings_clear_learned_words_confirm)
                    ) {
                        viewModel.clearLearnedWords()
                    }
                    true
                }
            }
        screen.addPreference(clearLearnedPref)

        val manageLearnedWordsPref =
            Preference(context).apply {
                key = "manage_learned_words"
                title = resources.getString(R.string.learned_words_manage)
                summary = resources.getString(R.string.learned_words_manage_summary)
                setOnPreferenceClickListener {
                    handleManageLearnedWordsTap()
                    true
                }
            }
        screen.addPreference(manageLearnedWordsPref)

        val exportDictionaryPref =
            Preference(context).apply {
                key = "export_dictionary"
                title = resources.getString(R.string.privacy_settings_export_dictionary)
                summary = resources.getString(R.string.privacy_settings_export_dictionary_summary)
                setOnPreferenceClickListener {
                    launchExportPicker()
                    true
                }
            }
        screen.addPreference(exportDictionaryPref)

        val importDictionaryPref =
            Preference(context).apply {
                key = "import_dictionary"
                title = resources.getString(R.string.privacy_settings_import_dictionary)
                summary = resources.getString(R.string.privacy_settings_import_dictionary_summary)
                setOnPreferenceClickListener {
                    launchImportPicker()
                    true
                }
            }
        screen.addPreference(importDictionaryPref)

        val resetPref =
            Preference(context).apply {
                key = "reset_defaults"
                title = resources.getString(R.string.privacy_settings_reset_to_defaults)
                summary = ""
                setOnPreferenceClickListener {
                    showConfirmDialog(
                        resources.getString(R.string.privacy_settings_reset_to_defaults),
                        resources.getString(R.string.privacy_settings_reset_to_defaults_confirm)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        clipboardPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateClipboardEnabled(newValue as Boolean)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        clipboardPref.isChecked = state.clipboardEnabled
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

    private fun exportErrorLog() {
        try {
            val logFile = ErrorLogger.exportLog()
            val uri =
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    logFile
                )

            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Urik Keyboard Error Log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            startActivity(
                Intent.createChooser(shareIntent, resources.getString(R.string.privacy_settings_export_error_log))
            )
        } catch (_: Exception) {
            AlertDialog
                .Builder(requireContext())
                .setTitle(resources.getString(R.string.error_title))
                .setMessage(resources.getString(R.string.privacy_settings_export_error_log_failed))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog
            .Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchExportPicker() {
        val dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fileName = "urik_dictionary_$dateStr.json"

        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
        exportLauncher.launch(intent)
    }

    private fun launchImportPicker() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
        importLauncher.launch(intent)
    }

    private fun handleManageLearnedWordsTap() {
        val biometricManager = BiometricManager.from(requireContext())
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            showBiometricPrompt()
        } else {
            navigateToLearnedWords()
        }
    }

    private fun showBiometricPrompt() {
        val activity = requireActivity() as FragmentActivity
        val executor = ContextCompat.getMainExecutor(requireContext())

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let { cipher ->
                    try {
                        cipher.doFinal(BIOMETRIC_CHALLENGE)
                    } catch (_: Exception) {
                        return
                    }
                }
                navigateToLearnedWords()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val biometricManager = BiometricManager.from(requireContext())
        val hasStrongBiometric = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        if (hasStrongBiometric) {
            val cryptoObject = createCryptoObject()
            if (cryptoObject != null) {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(resources.getString(R.string.learned_words_auth_title))
                    .setSubtitle(resources.getString(R.string.learned_words_auth_subtitle))
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                    )
                    .setNegativeButtonText(resources.getString(android.R.string.cancel))
                    .build()
                biometricPrompt.authenticate(promptInfo, cryptoObject)
                return
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(resources.getString(R.string.learned_words_auth_title))
            .setSubtitle(resources.getString(R.string.learned_words_auth_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    private fun createCryptoObject(): BiometricPrompt.CryptoObject? = try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (!keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
            generateBiometricKey()
        }

        val key = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        BiometricPrompt.CryptoObject(cipher)
    } catch (_: KeyPermanentlyInvalidatedException) {
        regenerateKeyAndCreateCryptoObject()
    } catch (_: Exception) {
        null
    }

    private fun regenerateKeyAndCreateCryptoObject(): BiometricPrompt.CryptoObject? = try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        generateBiometricKey()

        val key = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        BiometricPrompt.CryptoObject(cipher)
    } catch (_: Exception) {
        null
    }

    private fun generateBiometricKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        keyGenerator.generateKey()
    }

    private fun navigateToLearnedWords() {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, LearnedWordsFragment())
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val BIOMETRIC_KEY_ALIAS = "urik_learned_words_biometric_key"
        private const val CIPHER_TRANSFORMATION = "AES/CBC/PKCS7Padding"
        private val BIOMETRIC_CHALLENGE = byteArrayOf(0)
    }
}
