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

class OssLicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val rootLayout = createLayout()
        setContentView(rootLayout)

        val toolbar = rootLayout.findViewById<MaterialToolbar>(R.id.oss_licenses_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.licenses_title)
        }

        val textView = rootLayout.findViewById<TextView>(R.id.oss_licenses_text)
        textView.text = buildLicensesText()

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
                    id = R.id.oss_licenses_toolbar
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
                            id = R.id.oss_licenses_text
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

    private fun buildLicensesText(): String {
        val builder = StringBuilder()

        builder.append("OPEN SOURCE LICENSES\n\n")
        builder.append("This application uses the following open source libraries:\n\n")

        addLicense(
            builder,
            "AndroidX Libraries",
            "https://source.android.com",
            "Apache License 2.0",
        )

        addLicense(
            builder,
            "Kotlin Standard Library",
            "https://kotlinlang.org",
            "Apache License 2.0",
        )

        addLicense(
            builder,
            "Kotlinx Coroutines",
            "https://github.com/Kotlin/kotlinx.coroutines",
            "Apache License 2.0",
        )

        addLicense(
            builder,
            "Dagger Hilt",
            "https://github.com/google/dagger",
            "Apache License 2.0",
        )

        addLicense(
            builder,
            "Material Components for Android",
            "https://github.com/material-components/material-components-android",
            "Apache License 2.0",
        )

        addLicense(
            builder,
            "Room Persistence Library",
            "https://developer.android.com/jetpack/androidx/releases/room",
            "Apache License 2.0",
        )

        addLicense(
            builder,
            "DataStore",
            "https://developer.android.com/topic/libraries/architecture/datastore",
            "Apache License 2.0",
        )

        addLicense(
            builder,
            "SymSpellKt",
            "https://github.com/darkrockstudios/symspellkt",
            "MIT License",
        )

        addLicense(
            builder,
            "SQLCipher for Android",
            "https://github.com/sqlcipher/android-database-sqlcipher",
            "BSD License",
        )

        addLicense(
            builder,
            "ICU4J",
            "https://github.com/unicode-org/icu",
            "Unicode License",
        )

        builder.append("\n\n─────────────────────────────────────\n\n")
        builder.append("Apache License 2.0\n\n")
        builder.append(APACHE_2_LICENSE_SUMMARY)

        builder.append("\n\n─────────────────────────────────────\n\n")
        builder.append("MIT License\n\n")
        builder.append(MIT_LICENSE_SUMMARY)

        builder.append("\n\n─────────────────────────────────────\n\n")
        builder.append("BSD License\n\n")
        builder.append(BSD_LICENSE_SUMMARY)

        builder.append("\n\n─────────────────────────────────────\n\n")
        builder.append("Unicode License\n\n")
        builder.append(UNICODE_LICENSE_SUMMARY)

        return builder.toString()
    }

    private fun addLicense(
        builder: StringBuilder,
        name: String,
        url: String,
        license: String,
    ) {
        builder.append("• $name\n")
        builder.append("  $url\n")
        builder.append("  License: $license\n\n")
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

        private const val APACHE_2_LICENSE_SUMMARY =
            """Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License."""

        private const val MIT_LICENSE_SUMMARY =
            """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT."""

        private const val BSD_LICENSE_SUMMARY =
            """Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice.
2. Redistributions in binary form must reproduce the above copyright notice.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED."""

        private const val UNICODE_LICENSE_SUMMARY =
            """Permission is hereby granted, free of charge, to any person obtaining
a copy of the Unicode data files and associated documentation files,
to deal in them without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, and/or sell
copies, and to permit persons to whom they are furnished to do so,
provided that the above copyright notice(s) and this permission notice
appear in all copies."""
    }
}
