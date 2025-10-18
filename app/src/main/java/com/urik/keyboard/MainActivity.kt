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
import com.urik.keyboard.ui.animation.TypewriterAnimationView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createAnimationLayout())
    }

    private fun createAnimationLayout(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_background))

            val padding = dpToPx(32)
            setPadding(padding, padding, padding, padding)

            val animationView = TypewriterAnimationView(context)
            val animationHeight = dpToPx(240)
            val animationParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        animationHeight,
                    ).apply {
                        val margin = dpToPx(48)
                        setMargins(0, margin, 0, margin)
                    }
            addView(animationView, animationParams)

            val enableButton =
                MaterialButton(context).apply {
                    text = context.getString(R.string.enable_keyboard_title)
                    val buttonPadding = dpToPx(16)
                    setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)

                    setBackgroundColor(ContextCompat.getColor(context, R.color.key_background_action))
                    setTextColor(ContextCompat.getColor(context, R.color.content_primary))

                    setOnClickListener {
                        openKeyboardSettings()
                    }
                }

            val settingsButton =
                MaterialButton(context).apply {
                    text = context.getString(R.string.open_settings)
                    val buttonPadding = dpToPx(16)
                    setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)

                    setBackgroundColor(ContextCompat.getColor(context, R.color.key_background_character))
                    setTextColor(ContextCompat.getColor(context, R.color.content_primary))

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
                        val margin = dpToPx(8)
                        setMargins(0, 0, 0, margin)
                    }

            val settingsButtonParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )

            addView(enableButton, enableButtonParams)
            addView(settingsButton, settingsButtonParams)
        }

    private fun openKeyboardSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            Toast
                .makeText(
                    this,
                    R.string.error_opening_settings,
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
