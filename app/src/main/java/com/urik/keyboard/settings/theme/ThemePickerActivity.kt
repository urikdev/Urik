package com.urik.keyboard.settings.theme

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.ibm.icu.util.ULocale
import com.urik.keyboard.R
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ThemePickerActivity : AppCompatActivity() {
    @Inject
    lateinit var themeManager: ThemeManager

    @Inject
    lateinit var repository: KeyboardRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private lateinit var adapter: ThemePickerAdapter
    private var favoriteThemeIds: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val rootLayout = createLayout()
        setContentView(rootLayout)

        val toolbar = rootLayout.findViewById<MaterialToolbar>(R.id.theme_picker_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.theme_picker_title)
        }

        val recyclerView = rootLayout.findViewById<RecyclerView>(R.id.theme_picker_recycler)

        lifecycleScope.launch {
            favoriteThemeIds = settingsRepository.settings.first().favoriteThemes
            setupRecyclerView(recyclerView)

            val previewLayout =
                repository
                    .getLayoutForMode(
                        KeyboardMode.LETTERS,
                        ULocale.forLanguageTag("en"),
                        KeyboardKey.ActionType.ENTER,
                    ).getOrNull()

            if (previewLayout != null) {
                adapter.setPreviewLayout(previewLayout)
            }
        }

        applyWindowInsets(rootLayout)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.settings.collect { settings ->
                    favoriteThemeIds = settings.favoriteThemes
                    if (::adapter.isInitialized) {
                        adapter.updateFavoriteThemes(favoriteThemeIds)
                    }
                }
            }
        }
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
                    id = R.id.theme_picker_toolbar
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            resources.getDimensionPixelSize(
                                androidx.appcompat.R.dimen.abc_action_bar_default_height_material,
                            ),
                        )
                }
            addView(toolbar)

            val recyclerView =
                RecyclerView(context).apply {
                    id = R.id.theme_picker_recycler
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f,
                        )
                    layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                }
            addView(recyclerView)
        }

    private fun setupRecyclerView(recyclerView: RecyclerView) {
        val currentTheme = themeManager.currentTheme.value
        val allThemes = themeManager.getAllThemes()

        adapter =
            ThemePickerAdapter(
                allThemes,
                currentTheme.id,
                favoriteThemeIds,
                onThemeSelected = { selectedTheme -> onThemeSelected(selectedTheme) },
                onFavoriteToggled = { theme -> onFavoriteToggled(theme) },
            )

        recyclerView.adapter = adapter

        val currentIndex = adapter.getCurrentThemePosition(currentTheme.id)
        if (currentIndex != -1) {
            recyclerView.scrollToPosition(currentIndex)
        }
    }

    private fun onThemeSelected(theme: KeyboardTheme) {
        lifecycleScope.launch {
            themeManager.setTheme(theme)
            adapter.updateSelectedTheme(theme.id)
        }
    }

    private fun onFavoriteToggled(theme: KeyboardTheme) {
        lifecycleScope.launch {
            val updatedFavorites =
                if (favoriteThemeIds.contains(theme.id)) {
                    favoriteThemeIds - theme.id
                } else {
                    favoriteThemeIds + theme.id
                }
            settingsRepository.updateFavoriteThemes(updatedFavorites)
        }
    }

    private fun applyWindowInsets(rootLayout: LinearLayout) {
        val recyclerView = rootLayout.getChildAt(1) as RecyclerView

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePadding = (BASE_PADDING_DP * resources.displayMetrics.density).toInt()

            v.setPadding(insets.left, insets.top, insets.right, 0)
            recyclerView.setPadding(
                basePadding,
                basePadding,
                basePadding,
                basePadding + insets.bottom,
            )
            recyclerView.clipToPadding = false
            windowInsets
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    companion object {
        private const val BASE_PADDING_DP = 16
    }
}
