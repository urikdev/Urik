package com.urik.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.urik.keyboard.R
import com.urik.keyboard.settings.appearance.AppearanceFragment
import com.urik.keyboard.settings.autocorrection.AutoCorrectionFragment
import com.urik.keyboard.settings.languages.LanguagesFragment
import com.urik.keyboard.settings.layoutinput.LayoutInputFragment
import com.urik.keyboard.settings.privacydata.PrivacyDataFragment
import com.urik.keyboard.settings.typingbehavior.TypingBehaviorFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = resources.getString(R.string.settings_title)
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, MainSettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    finish()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
}

@AndroidEntryPoint
class MainSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(
            Preference(context).apply {
                key = "auto_correction_category"
                title = resources.getString(R.string.autocorrect_settings_title)
                summary = resources.getString(R.string.autocorrect_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(AutoCorrectionFragment())
                    true
                }
            },
        )

        screen.addPreference(
            Preference(context).apply {
                key = "languages_category"
                title = resources.getString(R.string.language_settings_title)
                summary = resources.getString(R.string.language_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(LanguagesFragment())
                    true
                }
            },
        )

        screen.addPreference(
            Preference(context).apply {
                key = "typing_behavior_category"
                title = resources.getString(R.string.typing_settings_title)
                summary = resources.getString(R.string.typing_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(TypingBehaviorFragment())
                    true
                }
            },
        )

        screen.addPreference(
            Preference(context).apply {
                key = "layout_input_category"
                title = resources.getString(R.string.layout_settings_title)
                summary = resources.getString(R.string.layout_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(LayoutInputFragment())
                    true
                }
            },
        )

        screen.addPreference(
            Preference(context).apply {
                key = "appearance_category"
                title = resources.getString(R.string.appearance_settings_title)
                summary = resources.getString(R.string.appearance_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(AppearanceFragment())
                    true
                }
            },
        )

        screen.addPreference(
            Preference(context).apply {
                key = "privacy_data_category"
                title = resources.getString(R.string.privacy_settings_title)
                summary = resources.getString(R.string.privacy_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(PrivacyDataFragment())
                    true
                }
            },
        )

        screen.addPreference(
            Preference(context).apply {
                key = "open_source_licenses"
                title = resources.getString(R.string.licenses_title)
                summary = resources.getString(R.string.licenses_description)
                setOnPreferenceClickListener {
                    startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                    true
                }
            },
        )

        screen.addPreference(
            Preference(context).apply {
                key = "dictionary_attribution"
                title = resources.getString(R.string.dictionary_attribution_title)
                summary = resources.getString(R.string.dictionary_attribution_summary)
                setOnPreferenceClickListener {
                    startActivity(Intent(context, DictionaryAttributionActivity::class.java))
                    true
                }
            },
        )

        preferenceScreen = screen
    }

    private fun navigateToFragment(fragment: PreferenceFragmentCompat) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
