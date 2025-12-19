package com.urik.keyboard.settings.hapticfeedback

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.urik.keyboard.R
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HapticFeedbackFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: HapticFeedbackViewModel
    private lateinit var eventHandler: SettingsEventHandler

    private lateinit var hapticPref: SwitchPreferenceCompat
    private lateinit var vibrationPref: SeekBarPreference
    private var testField: EditText? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requireContext().getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[HapticFeedbackViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val preferenceView = super.onCreateView(inflater, container, savedInstanceState)
        val wrapper = inflater.inflate(R.layout.preference_fragment_with_test_field, container, false)
        val preferenceContainer = wrapper.findViewById<ViewGroup>(R.id.preference_container)
        preferenceContainer.addView(preferenceView)
        testField = wrapper.findViewById(R.id.test_field)
        return wrapper
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
            SeekBarPreference(context).apply {
                key = "vibration_strength"
                title = resources.getString(R.string.feedback_settings_vibration_strength)
                min = 1
                max = 255
                showSeekBarValue = true
                isAdjustable = true
                updatesContinuously = true
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
            val strength = newValue as Int
            viewModel.updateVibrationStrength(strength)
            previewHaptic(strength)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        hapticPref.isChecked = state.hapticFeedback
                        vibrationPref.value = state.vibrationStrength
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

    override fun onDestroyView() {
        super.onDestroyView()
        testField = null
    }

    private fun previewHaptic(amplitude: Int) {
        vibrator?.let {
            val effect = VibrationEffect.createOneShot(25L, amplitude.coerceIn(1, 255))
            it.vibrate(effect)
        }
    }
}
