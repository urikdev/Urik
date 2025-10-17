package com.urik.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.urik.keyboard.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point activity for keyboard configuration and system integration.
 *
 * @see SettingsActivity for keyboard configuration options
 * @see UrikInputMethodService for actual keyboard implementation
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createCenteredLayout())
    }

    /**
     * Creates programmatic layout with centered buttons for keyboard setup.
     *
     * @return LinearLayout containing setup buttons
     */
    private fun createCenteredLayout(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_background))

            val padding = dpToPx(48)
            setPadding(padding, padding, padding, padding)

            val enableButton =
                MaterialButton(context).apply {
                    text = context.getString(R.string.enable_keyboard_title)
                    val buttonPadding = dpToPx(16)
                    setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)

                    setOnClickListener {
                        try {
                            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast
                                .makeText(
                                    context,
                                    R.string.error_opening_settings,
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                }

            val settingsButton =
                MaterialButton(context).apply {
                    text = context.getString(R.string.open_settings)
                    val buttonPadding = dpToPx(16)
                    setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)

                    setOnClickListener {
                        val intent = SettingsActivity.createIntent(context)
                        startActivity(intent)
                    }
                }

            val enableButtonParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        val margin = dpToPx(16)
                        setMargins(0, 0, 0, margin)
                    }

            val settingsButtonParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        val margin = dpToPx(16)
                        setMargins(0, 0, 0, margin)
                    }

            addView(enableButton, enableButtonParams)
            addView(settingsButton, settingsButtonParams)
        }

    /**
     * Converts density-independent pixels to actual pixels.
     *
     * @param dp Value in dp
     * @return Value in pixels for current display density
     */
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
