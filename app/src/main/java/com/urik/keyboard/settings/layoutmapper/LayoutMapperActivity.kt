package com.urik.keyboard.settings.layoutmapper

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ibm.icu.util.ULocale
import com.urik.keyboard.R
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LayoutMapperActivity : AppCompatActivity() {
    @Inject
    lateinit var repository: KeyboardRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var themeManager: ThemeManager

    private lateinit var viewModel: LayoutMapperViewModel
    private lateinit var keyboardRenderer: LayoutMapperKeyboardRenderer

    private var keyboardContainer: FrameLayout? = null
    private var currentLayout: KeyboardLayout? = null
    private var showNumberRow: Boolean = true
    private var activeBottomSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[LayoutMapperViewModel::class.java]
        keyboardRenderer = LayoutMapperKeyboardRenderer(this)

        val rootLayout = createLayout()
        setContentView(rootLayout)

        val toolbar = rootLayout.findViewById<MaterialToolbar>(R.id.layout_mapper_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.layout_mapper_title)
        }

        applyWindowInsets(rootLayout)
        observeState()
    }

    private fun createLayout(): LinearLayout =
        LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )

            val toolbar =
                MaterialToolbar(context).apply {
                    id = R.id.layout_mapper_toolbar
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            resources.getDimensionPixelSize(
                                androidx.appcompat.R.dimen.abc_action_bar_default_height_material,
                            ),
                        )
                }
            addView(toolbar)

            val scrollView =
                ScrollView(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f,
                        )

                    val contentLayout =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                )
                            val padding = (16 * resources.displayMetrics.density).toInt()
                            setPadding(padding, padding, padding, padding)

                            val instructionText =
                                TextView(context).apply {
                                    text = getString(R.string.layout_mapper_instructions)
                                    textSize = 14f
                                    layoutParams =
                                        LinearLayout
                                            .LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                            ).apply {
                                                bottomMargin = (16 * resources.displayMetrics.density).toInt()
                                            }
                                }
                            addView(instructionText)

                            keyboardContainer =
                                FrameLayout(context).apply {
                                    layoutParams =
                                        LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                        )
                                }
                            addView(keyboardContainer)

                            val resetButton =
                                MaterialButton(context).apply {
                                    text = getString(R.string.layout_mapper_reset_all)
                                    setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
                                    layoutParams =
                                        LinearLayout
                                            .LayoutParams(
                                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                            ).apply {
                                                topMargin = (24 * resources.displayMetrics.density).toInt()
                                                gravity = Gravity.CENTER_HORIZONTAL
                                            }
                                    setOnClickListener { showResetConfirmation() }
                                }
                            addView(resetButton)
                        }
                    addView(contentLayout)
                }
            addView(scrollView)
        }

    private suspend fun loadKeyboardLayout() {
        showNumberRow = settingsRepository.settings.first().showNumberRow

        val layout =
            repository
                .getLayoutForMode(
                    KeyboardMode.LETTERS,
                    ULocale.forLanguageTag("en"),
                    KeyboardKey.ActionType.ENTER,
                ).getOrNull()

        if (layout != null) {
            currentLayout = layout
            renderKeyboard(getFilteredLayout(layout))
        }
    }

    private fun getFilteredLayout(layout: KeyboardLayout): KeyboardLayout =
        if (!showNumberRow && layout.rows.isNotEmpty()) {
            layout.copy(rows = layout.rows.drop(1))
        } else {
            layout
        }

    private fun renderKeyboard(layout: KeyboardLayout) {
        keyboardContainer?.removeAllViews()

        val keyboardView =
            keyboardRenderer.createKeyboardView(
                layout = layout,
                theme = themeManager.currentTheme.value,
                mappings = viewModel.mappings.value,
                onKeyClick = { keyValue ->
                    viewModel.selectKey(keyValue)
                    showMappingBottomSheet(keyValue)
                },
            )

        keyboardContainer?.addView(keyboardView)
    }

    private fun updateKeyStates() {
        keyboardRenderer.updateMappings(
            mappings = viewModel.mappings.value,
            theme = themeManager.currentTheme.value,
        )
    }

    private fun showMappingBottomSheet(keyValue: String) {
        activeBottomSheet?.dismiss()
        val bottomSheet = BottomSheetDialog(this)
        activeBottomSheet = bottomSheet

        val density = resources.displayMetrics.density
        val theme = themeManager.currentTheme.value
        val currentMapping = viewModel.getMappingForKey(keyValue)

        val contentLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * density).toInt()
                setPadding(padding, padding, padding, padding)

                val titleText =
                    TextView(context).apply {
                        text = getString(R.string.layout_mapper_edit_key, keyValue.uppercase())
                        textSize = 20f
                        setTextColor(theme.colors.keyTextCharacter)
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    bottomMargin = (16 * density).toInt()
                                }
                    }
                addView(titleText)

                val inputLayout =
                    TextInputLayout(context).apply {
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    bottomMargin = (16 * density).toInt()
                                }
                        hint = getString(R.string.layout_mapper_symbol_hint)
                    }

                val symbolInput =
                    TextInputEditText(context).apply {
                        setText(currentMapping ?: "")
                        maxLines = 1
                        filters = arrayOf(SingleGraphemeFilter())
                    }
                inputLayout.addView(symbolInput)
                addView(inputLayout)

                symbolInput.post {
                    symbolInput.requestFocus()
                    symbolInput.setSelection(symbolInput.text?.length ?: 0)
                }

                val buttonContainer =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )

                        if (currentMapping != null) {
                            val removeButton =
                                MaterialButton(context).apply {
                                    text = getString(R.string.layout_mapper_remove)
                                    setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
                                    setOnClickListener {
                                        viewModel.removeMapping(keyValue)
                                        bottomSheet.dismiss()
                                    }
                                }
                            addView(removeButton)
                        }

                        val saveButton =
                            MaterialButton(context).apply {
                                text = getString(R.string.layout_mapper_save)
                                setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
                                layoutParams =
                                    LinearLayout
                                        .LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                        ).apply {
                                            marginStart = (8 * density).toInt()
                                        }
                                setOnClickListener {
                                    val symbol = symbolInput.text?.toString() ?: ""
                                    viewModel.saveMapping(keyValue, symbol)
                                    bottomSheet.dismiss()
                                }
                            }
                        addView(saveButton)
                    }
                addView(buttonContainer)
            }

        bottomSheet.setContentView(contentLayout)
        bottomSheet.setOnDismissListener {
            viewModel.clearSelection()
            if (activeBottomSheet == bottomSheet) {
                activeBottomSheet = null
            }
        }
        bottomSheet.show()
    }

    private fun showResetConfirmation() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.layout_mapper_reset_title)
            .setMessage(R.string.layout_mapper_reset_message)
            .setPositiveButton(R.string.layout_mapper_reset_confirm) { _, _ ->
                viewModel.clearAllMappings()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    loadKeyboardLayout()
                }

                launch {
                    viewModel.mappings.collect {
                        if (currentLayout != null) {
                            updateKeyStates()
                        }
                    }
                }

                launch {
                    themeManager.currentTheme.collect {
                        currentLayout?.let { layout -> renderKeyboard(getFilteredLayout(layout)) }
                    }
                }

                launch {
                    settingsRepository.settings.collect { settings ->
                        if (showNumberRow != settings.showNumberRow) {
                            showNumberRow = settings.showNumberRow
                            currentLayout?.let { layout -> renderKeyboard(getFilteredLayout(layout)) }
                        }
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is LayoutMapperEvent.MappingSaved -> {
                                Toast.makeText(this@LayoutMapperActivity, R.string.layout_mapper_saved, Toast.LENGTH_SHORT).show()
                            }

                            is LayoutMapperEvent.MappingRemoved -> {
                                Toast.makeText(this@LayoutMapperActivity, R.string.layout_mapper_removed, Toast.LENGTH_SHORT).show()
                            }

                            is LayoutMapperEvent.AllMappingsCleared -> {
                                Toast.makeText(this@LayoutMapperActivity, R.string.layout_mapper_cleared, Toast.LENGTH_SHORT).show()
                            }

                            is LayoutMapperEvent.SaveFailed -> {
                                Toast.makeText(this@LayoutMapperActivity, R.string.layout_mapper_save_failed, Toast.LENGTH_SHORT).show()
                            }

                            is LayoutMapperEvent.RemoveFailed -> {
                                Toast.makeText(this@LayoutMapperActivity, R.string.layout_mapper_remove_failed, Toast.LENGTH_SHORT).show()
                            }

                            is LayoutMapperEvent.ClearFailed -> {
                                Toast.makeText(this@LayoutMapperActivity, R.string.layout_mapper_clear_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyWindowInsets(rootLayout: LinearLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            else -> {
                super.onOptionsItemSelected(item)
            }
        }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private class SingleGraphemeFilter : android.text.InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: android.text.Spanned,
            dstart: Int,
            dend: Int,
        ): CharSequence? {
            val resultText = dest.substring(0, dstart) + source.subSequence(start, end) + dest.substring(dend)
            val graphemeCount = countGraphemes(resultText)
            return if (graphemeCount > 1) "" else null
        }

        private fun countGraphemes(text: String): Int {
            if (text.isEmpty()) return 0
            val iterator = java.text.BreakIterator.getCharacterInstance()
            iterator.setText(text)
            var count = 0
            while (iterator.next() != java.text.BreakIterator.DONE) {
                count++
            }
            return count
        }
    }

    override fun onDestroy() {
        activeBottomSheet?.dismiss()
        activeBottomSheet = null
        super.onDestroy()
    }
}
