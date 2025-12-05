package com.urik.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ibm.icu.util.ULocale
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.settings.SettingsActivity
import com.urik.keyboard.settings.theme.KeyboardPreviewRenderer
import com.urik.keyboard.settings.theme.ThemePickerActivity
import com.urik.keyboard.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var spellCheckManager: com.urik.keyboard.service.SpellCheckManager

    @Inject
    lateinit var themeManager: ThemeManager

    @Inject
    lateinit var keyboardRepository: KeyboardRepository

    private var previewContainer: MaterialCardView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val mainLayout = createMainLayout()
        setContentView(mainLayout)
        applyWindowInsets(mainLayout)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                themeManager.currentTheme.collect { theme ->
                    updatePreview()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewContainer = null
    }

    private fun createMainLayout(): android.widget.FrameLayout =
        android.widget.FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_background))

            addView(createCenteredContent())
            addView(createButtonsSection())
            addView(createFeaturesButton())
        }

    private fun createCenteredContent(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val isPortrait =
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

            layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        val topMargin = if (isPortrait) dpToPx(120) else dpToPx(16)
                        setMargins(0, topMargin, 0, 0)
                    }

            val label =
                TextView(context).apply {
                    text = context.getString(R.string.ime_label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setTextColor(ContextCompat.getColor(context, R.color.content_primary))
                    gravity = Gravity.CENTER
                }

            val logo =
                ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher_round)
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                dpToPx(80),
                                dpToPx(80),
                            ).apply {
                                val margin = dpToPx(16)
                                setMargins(0, margin, 0, margin)
                                gravity = Gravity.CENTER_HORIZONTAL
                            }
                }

            addView(label)
            addView(logo)

            if (isPortrait) {
                val preview = createKeyboardPreviewCard()
                addView(preview)
            }
        }

    private fun createKeyboardPreviewCard(): MaterialCardView {
        val card =
            MaterialCardView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )

                cardElevation = dpToPx(4).toFloat()
                radius = dpToPx(12).toFloat()

                setOnClickListener {
                    val intent = Intent(context, ThemePickerActivity::class.java)
                    startActivity(intent)
                }
            }

        previewContainer = card
        return card
    }

    private fun updatePreview() {
        val container = previewContainer ?: return

        lifecycleScope.launch {
            val layout =
                keyboardRepository
                    .getLayoutForMode(
                        KeyboardMode.LETTERS,
                        ULocale.forLanguageTag("en"),
                        KeyboardKey.ActionType.ENTER,
                    ).getOrNull()

            if (layout != null) {
                val theme = themeManager.currentTheme.value
                val renderer = KeyboardPreviewRenderer(this@MainActivity)
                val preview = renderer.createPreviewView(layout, theme, 200)

                container.removeAllViews()
                container.addView(preview)
            }
        }
    }

    private fun createFeaturesButton(): com.google.android.material.floatingactionbutton.FloatingActionButton =
        com.google.android.material.floatingactionbutton.FloatingActionButton(this).apply {
            size = com.google.android.material.floatingactionbutton.FloatingActionButton.SIZE_MINI
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_info))
            imageTintList = ContextCompat.getColorStateList(context, R.color.content_primary)
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.key_background_character)

            layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        val margin = dpToPx(16)
                        setMargins(margin, margin, margin, margin)
                    }

            setOnClickListener {
                showFeaturesBottomSheet()
            }
        }

    private fun createButtonsSection(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM
                    }

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
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        val margin = dpToPx(8)
                        setMargins(0, 0, 0, margin)
                    }

            val settingsButtonParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )

            addView(enableButton, enableButtonParams)
            addView(settingsButton, settingsButtonParams)
        }

    private fun showFeaturesBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_features, null)
        bottomSheet.setContentView(view)
        bottomSheet.show()
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

    private fun applyWindowInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePadding = dpToPx(BASE_PADDING_DP)
            v.setPadding(
                basePadding + insets.left,
                basePadding + insets.top,
                basePadding + insets.right,
                basePadding + insets.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE_PADDING_DP = 32
    }
}
