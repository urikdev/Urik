package com.urik.keyboard.settings

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R

class DictionaryAttributionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val rootLayout = createLayout()
        setContentView(rootLayout)

        val toolbar = rootLayout.findViewById<MaterialToolbar>(R.id.attribution_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.dictionary_attribution_title)
        }

        val textView = rootLayout.findViewById<TextView>(R.id.attribution_text)
        textView.text = buildAttributionText()

        applyWindowInsets(rootLayout)
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
                    id = R.id.attribution_toolbar
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

                    val textView =
                        TextView(context).apply {
                            id = R.id.attribution_text
                            textSize = 14f
                            setTextIsSelectable(true)
                        }
                    addView(textView)
                }
            addView(scrollView)
        }

    private fun applyWindowInsets(rootLayout: LinearLayout) {
        val scrollView = rootLayout.getChildAt(1) as ScrollView
        val textView = scrollView.getChildAt(0) as TextView

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePadding = (BASE_PADDING_DP * resources.displayMetrics.density).toInt()

            v.setPadding(insets.left, insets.top, insets.right, 0)
            textView.setPadding(
                basePadding,
                basePadding,
                basePadding,
                basePadding + insets.bottom,
            )
            windowInsets
        }
    }

    private fun buildAttributionText(): String {
        val builder = StringBuilder()

        builder.append("DICTIONARY DATA ATTRIBUTION\n")
        builder.append("FrequencyWords\n")
        builder.append("Word frequency lists derived from the FrequencyWords project.\n\n")
        builder.append("Author: hermitdave\n")
        builder.append("Source: https://github.com/hermitdave/FrequencyWords\n")
        builder.append("Original Data: OpenSubtitles corpus\n")
        builder.append("  http://opus.nlpl.eu/OpenSubtitles2018.php\n\n")
        builder.append("License: CC-BY-SA-4.0\n\n")
        builder.append("Modifications: Sorted by frequency, filtered for keyboard use\n\n")
        builder.append("\n\n")
        builder.append("Creative Commons Attribution-ShareAlike 4.0 International\n\n")
        builder.append("You are free to:\n")
        builder.append("  • Share — copy and redistribute the material in any medium or format\n")
        builder.append("  • Adapt — remix, transform, and build upon the material for any purpose\n\n")
        builder.append("Under the following terms:\n")
        builder.append("  • Attribution — You must give appropriate credit, provide a link to the\n")
        builder.append("    license, and indicate if changes were made\n")
        builder.append("  • ShareAlike — If you remix, transform, or build upon the material, you\n")
        builder.append("    must distribute your contributions under the same license\n")
        builder.append("  • No additional restrictions — You may not apply legal terms or\n")
        builder.append("    technological measures that legally restrict others from doing\n")
        builder.append("    anything the license permits\n\n")
        builder.append("Full license text:\n")
        builder.append("https://creativecommons.org/licenses/by-sa/4.0/legalcode\n")

        return builder.toString()
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
