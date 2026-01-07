package com.urik.keyboard.settings.typingbehavior

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
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.urik.keyboard.R
import com.urik.keyboard.settings.CursorSpeed
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.LongPressPunctuationMode
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
    private lateinit var autoCapitalizationPref: SwitchPreferenceCompat
    private lateinit var swipePref: SwitchPreferenceCompat
    private lateinit var spacebarCursorPref: SwitchPreferenceCompat
    private lateinit var cursorSpeedPref: ListPreference
    private lateinit var backspaceSwipePref: SwitchPreferenceCompat
    private lateinit var longPressPunctuationPref: ListPreference
    private lateinit var longPressPref: ListPreference
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
        viewModel = ViewModelProvider(this)[TypingBehaviorViewModel::class.java]
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

        doubleSpacePref =
            SwitchPreferenceCompat(context).apply {
                key = "double_space_period"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_double_space_period)
                summaryOn = resources.getString(R.string.typing_settings_double_space_on)
                summaryOff = resources.getString(R.string.typing_settings_double_space_off)
            }
        screen.addPreference(doubleSpacePref)

        autoCapitalizationPref =
            SwitchPreferenceCompat(context).apply {
                key = "auto_capitalization_enabled"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_auto_capitalization)
                summaryOn = resources.getString(R.string.typing_settings_auto_capitalization_on)
                summaryOff = resources.getString(R.string.typing_settings_auto_capitalization_off)
            }
        screen.addPreference(autoCapitalizationPref)

        swipePref =
            SwitchPreferenceCompat(context).apply {
                key = "swipe_enabled"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_swipe_enabled)
                summaryOn = resources.getString(R.string.typing_settings_swipe_on)
                summaryOff = resources.getString(R.string.typing_settings_swipe_off)
            }
        screen.addPreference(swipePref)

        spacebarCursorPref =
            SwitchPreferenceCompat(context).apply {
                key = "spacebar_cursor_control"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_spacebar_cursor)
                summaryOn = resources.getString(R.string.typing_settings_spacebar_cursor_on)
                summaryOff = resources.getString(R.string.typing_settings_spacebar_cursor_off)
            }
        screen.addPreference(spacebarCursorPref)

        cursorSpeedPref =
            ListPreference(context).apply {
                key = "cursor_speed"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_cursor_speed)
                entries = CursorSpeed.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = CursorSpeed.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        screen.addPreference(cursorSpeedPref)

        backspaceSwipePref =
            SwitchPreferenceCompat(context).apply {
                key = "backspace_swipe_delete"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_backspace_swipe)
                summaryOn = resources.getString(R.string.typing_settings_backspace_swipe_on)
                summaryOff = resources.getString(R.string.typing_settings_backspace_swipe_off)
            }
        screen.addPreference(backspaceSwipePref)

        longPressPunctuationPref =
            ListPreference(context).apply {
                key = "long_press_punctuation_mode"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_long_press_punctuation)
                entries = LongPressPunctuationMode.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = LongPressPunctuationMode.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        screen.addPreference(longPressPunctuationPref)

        longPressPref =
            ListPreference(context).apply {
                key = "long_press_duration"
                isPersistent = false
                title = resources.getString(R.string.typing_settings_long_press_duration)
                entries = LongPressDuration.entries.map { resources.getString(it.displayNameRes) }.toTypedArray()
                entryValues = LongPressDuration.entries.map { it.name }.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        screen.addPreference(longPressPref)

        hapticPref =
            SwitchPreferenceCompat(context).apply {
                key = "haptic_feedback"
                isPersistent = false
                title = resources.getString(R.string.feedback_settings_haptic_feedback)
                summaryOn = resources.getString(R.string.feedback_settings_haptic_on)
                summaryOff = resources.getString(R.string.feedback_settings_haptic_off)
            }
        screen.addPreference(hapticPref)

        vibrationPref =
            SeekBarPreference(context).apply {
                key = "vibration_strength"
                isPersistent = false
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

        doubleSpacePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateDoubleSpacePeriod(newValue as Boolean)
            true
        }

        autoCapitalizationPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateAutoCapitalizationEnabled(newValue as Boolean)
            true
        }

        swipePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSwipeEnabled(newValue as Boolean)
            true
        }

        spacebarCursorPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSpacebarCursorControl(newValue as Boolean)
            true
        }

        cursorSpeedPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateCursorSpeed(CursorSpeed.valueOf(newValue as String))
            true
        }

        backspaceSwipePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateBackspaceSwipeDelete(newValue as Boolean)
            true
        }

        longPressPunctuationPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateLongPressPunctuationMode(LongPressPunctuationMode.valueOf(newValue as String))
            true
        }

        longPressPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateLongPressDuration(LongPressDuration.valueOf(newValue as String))
            true
        }

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
                        doubleSpacePref.isChecked = state.doubleSpacePeriod
                        autoCapitalizationPref.isChecked = state.autoCapitalizationEnabled
                        swipePref.isChecked = state.swipeEnabled
                        spacebarCursorPref.isChecked = state.spacebarCursorControl
                        cursorSpeedPref.value = state.cursorSpeed.name
                        cursorSpeedPref.isVisible = state.spacebarCursorControl
                        backspaceSwipePref.isChecked = state.backspaceSwipeDelete
                        longPressPunctuationPref.value = state.longPressPunctuationMode.name
                        longPressPref.value = state.longPressDuration.name
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
