package com.urik.keyboard.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val rootLayout = createLayout()
        setContentView(rootLayout)

        val toolbar = rootLayout.findViewById<MaterialToolbar>(R.id.about_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.about_title)
        }

        val textView = rootLayout.findViewById<TextView>(R.id.about_text)
        textView.text = buildAboutText()
        textView.movementMethod = LinkMovementMethod.getInstance()

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
                    id = R.id.about_toolbar
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
                            id = R.id.about_text
                            textSize = 16f
                            setTextColor(
                                ContextCompat.getColor(
                                    context,
                                    R.color.content_primary,
                                ),
                            )
                            setLinkTextColor(
                                ContextCompat.getColor(
                                    context,
                                    R.color.light_orange,
                                ),
                            )
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

    private fun buildAboutText(): SpannableString {
        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        val versionName = packageInfo.versionName
        val versionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

        val text =
            """
            ${getString(R.string.about_version)}: $versionName ($versionCode)

            ${getString(R.string.about_copyright)}
            ${getString(R.string.about_license)}

            ${getString(R.string.about_source_code)}
            https://github.com/urikdev/Urik

            ${getString(R.string.about_release_notes)}
            https://github.com/urikdev/Urik/releases

            ${getString(R.string.about_report_bug)}
            https://github.com/urikdev/Urik/issues

            ${getString(R.string.about_privacy_policy)}
            https://github.com/urikdev/Urik/blob/main/PRIVACY.md

            ${getString(R.string.about_support)}
            https://buymeacoffee.com/urikdevelopment
            """.trimIndent()

        val spannable = SpannableString(text)

        val links =
            listOf(
                "https://github.com/urikdev/Urik",
                "https://github.com/urikdev/Urik/releases",
                "https://github.com/urikdev/Urik/issues",
                "https://github.com/urikdev/Urik/blob/main/PRIVACY.md",
                "https://buymeacoffee.com/urikdevelopment",
            )

        links.forEach { url ->
            val start = text.indexOf(url)
            if (start >= 0) {
                spannable.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            openUrl(url)
                        }
                    },
                    start,
                    start + url.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        return spannable
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
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
