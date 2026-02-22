package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.urik.keyboard.R
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.theme.ThemeManager

class LanguagePickerPopup(
    private val context: Context,
    private val themeManager: ThemeManager,
) {
    private var dialog: BottomSheetDialog? = null

    fun setLanguages(
        languages: List<String>,
        currentLanguage: String,
        anchorView: View,
        onSelected: (String) -> Unit,
    ) {
        val displayNames = KeyboardSettings.getLanguageDisplayNames()
        val languageLabels = languages.map { displayNames[it] ?: it }
        val currentIndex = languages.indexOf(currentLanguage).takeIf { it >= 0 } ?: 0

        val theme = themeManager.currentTheme.value
        val density = context.resources.displayMetrics.density

        dialog =
            BottomSheetDialog(context).apply {
                val container =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(theme.colors.keyboardBackground)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                    }

                val titleView =
                    TextView(context).apply {
                        text = context.getString(R.string.select_active_language)
                        textSize = 20f
                        setTextColor(theme.colors.keyTextAction)
                        gravity = Gravity.CENTER
                        setPadding(
                            (24 * density).toInt(),
                            (20 * density).toInt(),
                            (24 * density).toInt(),
                            (16 * density).toInt(),
                        )
                    }
                container.addView(titleView)

                val listView =
                    ListView(context).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        ViewCompat.setAccessibilityDelegate(
                            this,
                            object : AccessibilityDelegateCompat() {
                                override fun onInitializeAccessibilityNodeInfo(
                                    host: View,
                                    info: AccessibilityNodeInfoCompat,
                                ) {
                                    super.onInitializeAccessibilityNodeInfo(host, info)
                                    info.removeAction(
                                        AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_TEXT,
                                    )
                                    info.isEditable = false
                                }
                            },
                        )
                        adapter =
                            object : ArrayAdapter<String>(
                                context,
                                android.R.layout.select_dialog_singlechoice,
                                languageLabels,
                            ) {
                                override fun getView(
                                    position: Int,
                                    convertView: View?,
                                    parent: ViewGroup,
                                ): View {
                                    val view = super.getView(position, convertView, parent) as CheckedTextView
                                    view.setTextColor(theme.colors.keyTextCharacter)
                                    view.textSize = 16f
                                    view.setPadding(
                                        (24 * density).toInt(),
                                        (16 * density).toInt(),
                                        (24 * density).toInt(),
                                        (16 * density).toInt(),
                                    )

                                    view.checkMarkTintList = ColorStateList.valueOf(theme.colors.keyTextAction)

                                    return view
                                }
                            }
                        choiceMode = ListView.CHOICE_MODE_SINGLE
                        setItemChecked(currentIndex, true)
                        divider = null
                        setBackgroundColor(theme.colors.keyboardBackground)
                        setOnItemClickListener { _, _, position, _ ->
                            onSelected(languages[position])
                            dismiss()
                        }
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                    }
                container.addView(listView)

                setContentView(container)

                window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    val lp = attributes
                    lp.token = anchorView.applicationWindowToken
                    lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                    attributes = lp
                    addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                }
            }
    }

    fun showAboveAnchor() {
        dialog?.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun cleanup() {
        dismiss()
    }
}
