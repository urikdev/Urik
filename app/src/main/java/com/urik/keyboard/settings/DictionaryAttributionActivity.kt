package com.urik.keyboard.settings

import android.os.Bundle
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DictionaryAttributionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Dictionary Data"
        }

        val scrollView =
            ScrollView(this).apply {
                fitsSystemWindows = true
            }

        val textView =
            TextView(this).apply {
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                textSize = 14f
                setTextIsSelectable(true)
            }

        scrollView.addView(textView)
        setContentView(scrollView)

        textView.text = buildAttributionText()
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
}
