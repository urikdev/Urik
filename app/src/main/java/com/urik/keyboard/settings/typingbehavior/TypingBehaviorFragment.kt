package com.urik.keyboard.settings.typingbehavior

import android.os.Bundle
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
    private lateinit var swipePref: SwitchPreferenceCompat
    private lateinit var longPressPref: ListPreference
    private var testField: EditText? = null

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
                title = resources.getString(R.string.typing_settings_double_space_period)
                summaryOn = resources.getString(R.string.typing_settings_double_space_on)
                summaryOff = resources.getString(R.string.typing_settings_double_space_off)
            }
        screen.addPreference(doubleSpacePref)

        swipePref =
            SwitchPreferenceCompat(context).apply {
                key = "swipe_enabled"
                title = resources.getString(R.string.typing_settings_swipe_enabled)
                summaryOn = resources.getString(R.string.typing_settings_swipe_on)
                summaryOff = resources.getString(R.string.typing_settings_swipe_off)
            }
        screen.addPreference(swipePref)

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

        swipePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSwipeEnabled(newValue as Boolean)
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
                        swipePref.isChecked = state.swipeEnabled
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

    override fun onDestroyView() {
        super.onDestroyView()
        testField = null
    }
}
