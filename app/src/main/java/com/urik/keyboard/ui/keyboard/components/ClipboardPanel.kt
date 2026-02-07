package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.urik.keyboard.R
import com.urik.keyboard.data.database.ClipboardItem
import com.urik.keyboard.theme.ThemeManager

/**
 * Clipboard history panel with consent screen and item management.
 *
 */
class ClipboardPanel(
    context: Context,
    private val themeManager: ThemeManager,
) : FrameLayout(context) {
    private var onConsentAccepted: (() -> Unit)? = null
    private var onItemSelected: ((String) -> Unit)? = null
    private var onItemPinToggled: ((ClipboardItem) -> Unit)? = null
    private var onItemDeleted: ((ClipboardItem) -> Unit)? = null
    private var onDeleteAllUnpinned: (() -> Unit)? = null

    private var deleteAllConfirmationOverlay: FrameLayout? = null

    private val consentScreen: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val density = context.resources.displayMetrics.density
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }

    private val clipboardContentScreen: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
            val density = context.resources.displayMetrics.density
            val padding = (8 * density).toInt()
            setPadding(0, 0, 0, padding)
        }

    private val tabContainer: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val density = context.resources.displayMetrics.density
            val padding = (8 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(themeManager.currentTheme.value.colors.suggestionBarBackground)
        }

    private val pinnedTab: Button
    private val recentTab: Button

    private val pinnedListContainer: ScrollView =
        ScrollView(context).apply {
            isVisible = false
        }

    private val recentListContainer: ScrollView =
        ScrollView(context).apply {
            isVisible = true
        }

    private val pinnedList: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

    private val recentList: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

    private val emptyStateText: TextView =
        TextView(context).apply {
            text = context.getString(R.string.clipboard_panel_empty)
            gravity = Gravity.CENTER
            val density = context.resources.displayMetrics.density
            val padding = (24 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setTextColor(themeManager.currentTheme.value.colors.suggestionText)
            textSize = 14f
        }

    private val itemViewPool = mutableListOf<LinearLayout>()
    private val activeItemViews = mutableListOf<LinearLayout>()

    private var currentTab: Tab = Tab.RECENT

    private enum class Tab {
        PINNED,
        RECENT,
    }

    init {
        val density = context.resources.displayMetrics.density

        setBackgroundColor(themeManager.currentTheme.value.colors.keyboardBackground)
        elevation = 8f * density
        visibility = GONE

        val pinnedTabDrawable = createTabBackground(isSelected = false)
        pinnedTab =
            Button(context).apply {
                text = context.getString(R.string.clipboard_panel_pinned)
                background = pinnedTabDrawable
                setTextColor(themeManager.currentTheme.value.colors.keyTextCharacter)
                val padding = (12 * density).toInt()
                setPadding(padding, padding, padding, padding)
                setOnClickListener { switchToTab(Tab.PINNED) }
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            }

        val recentTabDrawable = createTabBackground(isSelected = true)
        recentTab =
            Button(context).apply {
                text = context.getString(R.string.clipboard_panel_recent)
                background = recentTabDrawable
                setTextColor(themeManager.currentTheme.value.colors.keyTextAction)
                val padding = (12 * density).toInt()
                setPadding(padding, padding, padding, padding)
                setOnClickListener { switchToTab(Tab.RECENT) }
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            }

        tabContainer.addView(pinnedTab)
        tabContainer.addView(recentTab)

        pinnedListContainer.addView(pinnedList)
        recentListContainer.addView(recentList)

        val consentMessage =
            TextView(context).apply {
                text = context.getString(R.string.clipboard_consent_message)
                setTextColor(themeManager.currentTheme.value.colors.keyTextCharacter)
                textSize = 14f
                gravity = Gravity.CENTER
                val padding = (16 * density).toInt()
                setPadding(padding, padding, padding, padding)
            }

        val acceptButton =
            Button(context).apply {
                text = context.getString(android.R.string.ok)
                setTextColor(themeManager.currentTheme.value.colors.keyTextAction)
                background = createButtonBackground()
                val padding = (16 * density).toInt()
                setPadding(padding, padding, padding, padding)
                setOnClickListener {
                    onConsentAccepted?.invoke()
                }
            }

        consentScreen.addView(consentMessage)
        consentScreen.addView(acceptButton)

        clipboardContentScreen.addView(tabContainer)
        clipboardContentScreen.addView(pinnedListContainer)
        clipboardContentScreen.addView(recentListContainer)
        clipboardContentScreen.addView(emptyStateText)

        addView(consentScreen)
        addView(clipboardContentScreen)
    }

    private fun createTabBackground(isSelected: Boolean): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val cornerRadius = 8 * density

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(cornerRadius)
            setColor(
                if (isSelected) {
                    themeManager.currentTheme.value.colors.keyBackgroundCharacter
                } else {
                    themeManager.currentTheme.value.colors.keyBackgroundAction
                },
            )
        }
    }

    private fun createButtonBackground(): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val cornerRadius = 8 * density

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(cornerRadius)
            setColor(themeManager.currentTheme.value.colors.keyBackgroundAction)
        }
    }

    private fun switchToTab(tab: Tab) {
        currentTab = tab

        when (tab) {
            Tab.PINNED -> {
                pinnedTab.background = createTabBackground(isSelected = true)
                pinnedTab.setTextColor(themeManager.currentTheme.value.colors.keyTextAction)
                recentTab.background = createTabBackground(isSelected = false)
                recentTab.setTextColor(themeManager.currentTheme.value.colors.keyTextCharacter)

                pinnedListContainer.isVisible = true
                recentListContainer.isVisible = false
            }

            Tab.RECENT -> {
                recentTab.background = createTabBackground(isSelected = true)
                recentTab.setTextColor(themeManager.currentTheme.value.colors.keyTextAction)
                pinnedTab.background = createTabBackground(isSelected = false)
                pinnedTab.setTextColor(themeManager.currentTheme.value.colors.keyTextCharacter)

                pinnedListContainer.isVisible = false
                recentListContainer.isVisible = true
            }
        }
    }

    fun showConsentScreen(onAccepted: () -> Unit) {
        this.onConsentAccepted = onAccepted
        consentScreen.isVisible = true
        clipboardContentScreen.isVisible = false
        visibility = VISIBLE
    }

    fun showClipboardContent(
        pinnedItems: List<ClipboardItem>,
        recentItems: List<ClipboardItem>,
        onItemClick: (String) -> Unit,
        onPinToggle: (ClipboardItem) -> Unit,
        onDelete: (ClipboardItem) -> Unit,
        onDeleteAll: () -> Unit,
    ) {
        this.onItemSelected = onItemClick
        this.onItemPinToggled = onPinToggle
        this.onItemDeleted = onDelete
        this.onDeleteAllUnpinned = onDeleteAll

        consentScreen.isVisible = false
        clipboardContentScreen.isVisible = true
        visibility = View.VISIBLE

        refreshContent(pinnedItems, recentItems)
    }

    fun hide() {
        hideDeleteAllConfirmation()
        returnItemViewsToPool()
        visibility = GONE
        onConsentAccepted = null
        onItemSelected = null
        onItemPinToggled = null
        onItemDeleted = null
        onDeleteAllUnpinned = null
    }

    val isShowing: Boolean
        get() = isVisible

    fun refreshContent(
        pinnedItems: List<ClipboardItem>,
        recentItems: List<ClipboardItem>,
    ) {
        returnItemViewsToPool()
        updatePinnedList(pinnedItems)
        updateRecentList(recentItems)

        emptyStateText.isVisible = pinnedItems.isEmpty() && recentItems.isEmpty()
    }

    private fun updatePinnedList(items: List<ClipboardItem>) {
        pinnedList.removeAllViews()

        items.forEach { item ->
            val view = getOrCreateItemView()
            updateItemView(view, item)
            activeItemViews.add(view)
            pinnedList.addView(view)
        }
    }

    private fun updateRecentList(items: List<ClipboardItem>) {
        recentList.removeAllViews()

        if (items.isNotEmpty()) {
            val deleteAllButton = createDeleteAllButton()
            recentList.addView(deleteAllButton)
        }

        items.forEach { item ->
            val view = getOrCreateItemView()
            updateItemView(view, item)
            activeItemViews.add(view)
            recentList.addView(view)
        }
    }

    private fun createDeleteAllButton(): Button {
        val density = context.resources.displayMetrics.density
        return Button(context).apply {
            text = context.getString(R.string.clipboard_panel_delete_all)
            setTextColor(themeManager.currentTheme.value.colors.keyTextAction)
            background = createButtonBackground()
            val padding = (12 * density).toInt()
            setPadding(padding, padding, padding, padding)
            val margin = (8 * density).toInt()
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(margin, margin, margin, margin)
                    }
            setOnClickListener {
                showDeleteAllConfirmation()
            }
        }
    }

    private fun getOrCreateItemView(): LinearLayout =
        if (itemViewPool.isNotEmpty()) {
            itemViewPool.removeAt(itemViewPool.size - 1).apply {
                (parent as? LinearLayout)?.removeView(this)
            }
        } else {
            createEmptyItemView()
        }

    private fun createEmptyItemView(): LinearLayout {
        val density = context.resources.displayMetrics.density

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
                val padding = (8 * density).toInt()
                setPadding(padding, padding, padding, padding)
                val minTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
                minimumHeight = minTouchTarget

                val margin = (4 * density).toInt()
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(margin, margin, margin, margin)
                        }
            }

        val contentText =
            TextView(context).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                textSize = 16f
                val padding = (4 * density).toInt()
                setPadding(padding, 0, padding, 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                tag = "content_text"
            }

        val buttonContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        val pinButton =
            Button(context).apply {
                background = null
                val padding = (4 * density).toInt()
                setPadding(padding, padding, padding, padding)
                minWidth = 0
                minimumWidth = 0
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            val a11yMargin = (8 * density).toInt()
                            marginEnd = a11yMargin
                        }
                tag = "pin_button"
            }

        val deleteButton =
            Button(context).apply {
                text = "×"
                textSize = 20f
                background = null
                val padding = (4 * density).toInt()
                setPadding(padding, padding, padding, padding)
                minWidth = 0
                minimumWidth = 0
                tag = "delete_button"
            }

        buttonContainer.addView(pinButton)
        buttonContainer.addView(deleteButton)

        container.addView(contentText)
        container.addView(buttonContainer)

        return container
    }

    private fun updateItemView(
        view: LinearLayout,
        item: ClipboardItem,
    ) {
        val contentText = view.findViewWithTag<TextView>("content_text")
        val buttonContainer = view.getChildAt(1) as LinearLayout
        val pinButton = buttonContainer.findViewWithTag<Button>("pin_button")
        val deleteButton = buttonContainer.findViewWithTag<Button>("delete_button")

        contentText?.apply {
            text = item.content
            setTextColor(themeManager.currentTheme.value.colors.keyTextCharacter)
            contentDescription =
                context.getString(R.string.clipboard_item_description, item.content.take(50))
        }

        view.apply {
            setBackgroundColor(themeManager.currentTheme.value.colors.keyBackgroundCharacter)
            setOnClickListener {
                onItemSelected?.invoke(item.content)
            }
        }

        pinButton?.apply {
            val pinIcon =
                if (item.isPinned) {
                    ContextCompat.getDrawable(context, R.drawable.ic_pin_filled)
                } else {
                    ContextCompat.getDrawable(context, R.drawable.ic_pin_outline)
                }
            pinIcon?.setTint(themeManager.currentTheme.value.colors.keyTextAction)
            setCompoundDrawablesRelativeWithIntrinsicBounds(pinIcon, null, null, null)
            contentDescription =
                if (item.isPinned) {
                    context.getString(R.string.clipboard_item_unpin)
                } else {
                    context.getString(R.string.clipboard_item_pin)
                }
            setOnClickListener {
                onItemPinToggled?.invoke(item)
            }
        }

        deleteButton?.apply {
            setTextColor(themeManager.currentTheme.value.colors.keyTextAction)
            contentDescription = context.getString(R.string.clipboard_item_delete)
            setOnClickListener {
                onItemDeleted?.invoke(item)
            }
        }
    }

    private fun showDeleteAllConfirmation() {
        if (deleteAllConfirmationOverlay != null) return

        val density = context.resources.displayMetrics.density

        deleteAllConfirmationOverlay =
            FrameLayout(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.black))
                alpha = 0.8f

                val container =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setBackgroundColor(themeManager.currentTheme.value.colors.keyboardBackground)

                        val padding = (16 * density).toInt()
                        setPadding(padding, padding, padding, padding)

                        val message =
                            TextView(context).apply {
                                text = context.getString(R.string.clipboard_panel_delete_all_confirm)
                                setTextColor(themeManager.currentTheme.value.colors.suggestionText)
                                gravity = Gravity.CENTER
                                setPadding(0, 0, 0, padding)
                            }
                        addView(message)

                        val buttonsContainer =
                            LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER

                                val cancelBtn =
                                    Button(context).apply {
                                        text = context.getString(android.R.string.cancel)
                                        setTextColor(themeManager.currentTheme.value.colors.keyTextAction)
                                        setBackgroundColor(themeManager.currentTheme.value.colors.keyBackgroundAction)
                                        setOnClickListener { hideDeleteAllConfirmation() }
                                    }

                                val deleteBtn =
                                    Button(context).apply {
                                        text = context.getString(R.string.clipboard_panel_delete_all)
                                        setTextColor(ContextCompat.getColor(context, android.R.color.white))
                                        setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                                        setOnClickListener { confirmDeleteAll() }
                                    }

                                val margin = padding / 2
                                addView(
                                    cancelBtn,
                                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        marginEnd = margin
                                    },
                                )
                                addView(
                                    deleteBtn,
                                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        marginStart = margin
                                    },
                                )
                            }
                        addView(buttonsContainer)
                    }

                addView(
                    container,
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }

        addView(
            deleteAllConfirmationOverlay,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun hideDeleteAllConfirmation() {
        deleteAllConfirmationOverlay?.let { overlay ->
            removeView(overlay)
        }
        deleteAllConfirmationOverlay = null
    }

    private fun confirmDeleteAll() {
        hideDeleteAllConfirmation()
        onDeleteAllUnpinned?.invoke()
    }

    private fun returnItemViewsToPool() {
        activeItemViews.forEach { view ->
            view.setOnClickListener(null)
            val buttonContainer = view.getChildAt(1) as? LinearLayout
            buttonContainer?.findViewWithTag<Button>("pin_button")?.setOnClickListener(null)
            buttonContainer?.findViewWithTag<Button>("delete_button")?.setOnClickListener(null)

            if (itemViewPool.size < 100) {
                itemViewPool.add(view)
            }
        }
        activeItemViews.clear()
    }
}
