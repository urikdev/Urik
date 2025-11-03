package com.urik.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.urik.keyboard.settings.SettingsActivity
import com.urik.keyboard.ui.tips.TipsAdapter
import com.urik.keyboard.ui.tips.TipsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var tipsRepository: TipsRepository

    private var tipsViewPager: ViewPager2? = null
    private var autoSwipeJob: Job? = null

    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabLayoutMediator: TabLayoutMediator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createMainLayout())
    }

    override fun onResume() {
        super.onResume()
        startAutoSwipe()
    }

    override fun onPause() {
        super.onPause()
        stopAutoSwipe()
    }

    override fun onDestroy() {
        super.onDestroy()

        autoSwipeJob?.cancel()
        autoSwipeJob = null

        pageChangeCallback?.let { callback ->
            tipsViewPager?.unregisterOnPageChangeCallback(callback)
        }
        pageChangeCallback = null

        tabLayoutMediator?.detach()
        tabLayoutMediator = null

        tipsViewPager = null
    }

    private fun startAutoSwipe() {
        autoSwipeJob?.cancel()
        autoSwipeJob =
            lifecycleScope.launch {
                while (true) {
                    delay(4000)
                    tipsViewPager?.let { viewPager ->
                        val currentItem = viewPager.currentItem
                        val itemCount = viewPager.adapter?.itemCount ?: 0
                        if (itemCount > 0) {
                            val nextItem = (currentItem + 1) % itemCount
                            viewPager.setCurrentItem(nextItem, true)
                        }
                    }
                }
            }
    }

    private fun stopAutoSwipe() {
        autoSwipeJob?.cancel()
        autoSwipeJob = null
    }

    private fun createMainLayout(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_background))

            val padding = dpToPx(32)
            setPadding(padding, padding, padding, padding)

            addView(createAnimationSection())
            addView(createTipsSection())
            addView(createButtonsSection())
        }

    private fun createAnimationSection(): LinearLayout =
        LinearLayout(this).apply {
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        val topMargin = dpToPx(120)
                        val bottomMargin = dpToPx(48)
                        setMargins(0, topMargin, 0, bottomMargin)
                    }
        }

    private fun createTipsSection(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        val bottomMargin = dpToPx(48)
                        setMargins(0, 0, 0, bottomMargin)
                    }

            val viewPager =
                ViewPager2(context).apply {
                    id = View.generateViewId()
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(120),
                        )
                    adapter = TipsAdapter(tipsRepository.getShuffledTips())

                    val callback =
                        object : ViewPager2.OnPageChangeCallback() {
                            override fun onPageSelected(position: Int) {
                                super.onPageSelected(position)
                                stopAutoSwipe()
                                startAutoSwipe()
                            }
                        }
                    registerOnPageChangeCallback(callback)
                    pageChangeCallback = callback
                }

            tipsViewPager = viewPager

            val tabLayout =
                LayoutInflater
                    .from(context)
                    .inflate(R.layout.tips_tab_layout, this, false) as TabLayout

            tabLayout.layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        val margin = dpToPx(4)
                        setMargins(0, margin, 0, 0)
                    }

            addView(viewPager)
            addView(tabLayout)

            val mediator = TabLayoutMediator(tabLayout, viewPager) { _, _ -> }
            mediator.attach()
            tabLayoutMediator = mediator
        }

    private fun createButtonsSection(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

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
