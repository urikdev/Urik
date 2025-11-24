package com.urik.keyboard.settings.theme

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.theme.KeyboardTheme

class ThemePickerAdapter(
    private val allThemes: List<KeyboardTheme>,
    private var selectedThemeId: String,
    private var favoriteThemeIds: Set<String>,
    private val onThemeSelected: (KeyboardTheme) -> Unit,
    private val onFavoriteToggled: (KeyboardTheme) -> Unit,
) : RecyclerView.Adapter<ThemePickerAdapter.ThemeViewHolder>() {
    private var previewLayout: KeyboardLayout? = null
    private var previewRenderer: KeyboardPreviewRenderer? = null
    private var themes: List<KeyboardTheme> = sortThemes(allThemes, favoriteThemeIds)

    private fun sortThemes(
        themes: List<KeyboardTheme>,
        favorites: Set<String>,
    ): List<KeyboardTheme> =
        themes.sortedBy { theme ->
            if (favorites.contains(theme.id)) 0 else 1
        }

    fun setPreviewLayout(layout: KeyboardLayout) {
        previewLayout = layout
        notifyItemRangeChanged(0, themes.size, PAYLOAD_PREVIEW_UPDATE)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ThemeViewHolder {
        if (previewRenderer == null) {
            previewRenderer = KeyboardPreviewRenderer(parent.context)
        }
        return ThemeViewHolder.create(parent)
    }

    override fun onBindViewHolder(
        holder: ThemeViewHolder,
        position: Int,
    ) {
        val theme = themes[position]
        val isSelected = theme.id == selectedThemeId
        val isFavorite = favoriteThemeIds.contains(theme.id)
        holder.bind(
            theme,
            isSelected,
            isFavorite,
            previewLayout,
            previewRenderer,
            onThemeClick = { onThemeSelected(theme) },
            onFavoriteClick = { onFavoriteToggled(theme) },
        )
    }

    override fun onBindViewHolder(
        holder: ThemeViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val theme = themes[position]
            for (payload in payloads) {
                when (payload) {
                    PAYLOAD_PREVIEW_UPDATE -> holder.updatePreview(theme, previewLayout, previewRenderer)
                }
            }
        }
    }

    override fun onViewRecycled(holder: ThemeViewHolder) {
        super.onViewRecycled(holder)
        holder.clearPreview()
    }

    override fun getItemCount(): Int = themes.size

    fun updateSelectedTheme(themeId: String) {
        val previousIndex = themes.indexOfFirst { it.id == selectedThemeId }
        selectedThemeId = themeId
        val newIndex = themes.indexOfFirst { it.id == selectedThemeId }

        if (previousIndex != -1) {
            notifyItemChanged(previousIndex)
        }
        if (newIndex != -1) {
            notifyItemChanged(newIndex)
        }
    }

    fun updateFavoriteThemes(favoriteIds: Set<String>) {
        val oldThemes = themes
        val oldFavorites = favoriteThemeIds
        favoriteThemeIds = favoriteIds
        val newThemes = sortThemes(allThemes, favoriteThemeIds)

        val diffResult =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldThemes.size

                    override fun getNewListSize() = newThemes.size

                    override fun areItemsTheSame(
                        oldPos: Int,
                        newPos: Int,
                    ) = oldThemes[oldPos].id == newThemes[newPos].id

                    override fun areContentsTheSame(
                        oldPos: Int,
                        newPos: Int,
                    ) = oldThemes[oldPos].id == newThemes[newPos].id &&
                        oldFavorites.contains(oldThemes[oldPos].id) ==
                        favoriteThemeIds.contains(newThemes[newPos].id)
                },
            )

        themes = newThemes
        diffResult.dispatchUpdatesTo(this)
    }

    fun getCurrentThemePosition(themeId: String): Int = themes.indexOfFirst { it.id == themeId }

    companion object {
        private const val PAYLOAD_PREVIEW_UPDATE = "preview_update"
    }

    class ThemeViewHolder(
        private val rootView: FrameLayout,
    ) : RecyclerView.ViewHolder(rootView) {
        private val cardView: MaterialCardView = rootView.findViewById(VIEW_ID_CARD)
        private val previewContainer: FrameLayout = rootView.findViewById(VIEW_ID_PREVIEW_CONTAINER)
        private val themeName: TextView = rootView.findViewById(VIEW_ID_THEME_NAME)
        private val selectedIndicator: ImageView = rootView.findViewById(VIEW_ID_SELECTED_INDICATOR)
        private val favoriteIndicator: ImageView = rootView.findViewById(VIEW_ID_FAVORITE_INDICATOR)

        fun bind(
            theme: KeyboardTheme,
            isSelected: Boolean,
            isFavorite: Boolean,
            previewLayout: KeyboardLayout?,
            previewRenderer: KeyboardPreviewRenderer?,
            onThemeClick: () -> Unit,
            onFavoriteClick: () -> Unit,
        ) {
            themeName.text = theme.displayName

            previewContainer.removeAllViews()

            if (previewLayout != null && previewRenderer != null) {
                val keyboardPreview = previewRenderer.createPreviewView(previewLayout, theme, PREVIEW_HEIGHT_DP)
                previewContainer.addView(keyboardPreview)
            } else {
                previewContainer.setBackgroundColor(theme.colors.keyboardBackground)
            }

            selectedIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE

            favoriteIndicator.setImageResource(
                if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline,
            )
            favoriteIndicator.contentDescription =
                rootView.context.getString(
                    if (isFavorite) R.string.theme_remove_favorite else R.string.theme_add_favorite,
                    theme.displayName,
                )
            favoriteIndicator.setOnClickListener { onFavoriteClick() }

            cardView.strokeWidth =
                if (isSelected) {
                    (3 * cardView.resources.displayMetrics.density).toInt()
                } else {
                    0
                }

            rootView.contentDescription =
                rootView.context.getString(
                    if (isSelected) R.string.theme_description_selected else R.string.theme_description,
                    theme.displayName,
                )
            rootView.setOnClickListener { onThemeClick() }
        }

        fun updatePreview(
            theme: KeyboardTheme,
            previewLayout: KeyboardLayout?,
            previewRenderer: KeyboardPreviewRenderer?,
        ) {
            previewContainer.removeAllViews()

            if (previewLayout != null && previewRenderer != null) {
                val keyboardPreview = previewRenderer.createPreviewView(previewLayout, theme, PREVIEW_HEIGHT_DP)
                previewContainer.addView(keyboardPreview)
            } else {
                previewContainer.setBackgroundColor(theme.colors.keyboardBackground)
            }
        }

        fun clearPreview() {
            previewContainer.removeAllViews()
        }

        companion object {
            private const val VIEW_ID_CARD = 1001
            private const val VIEW_ID_PREVIEW_CONTAINER = 1002
            private const val VIEW_ID_THEME_NAME = 1003
            private const val VIEW_ID_SELECTED_INDICATOR = 1004
            private const val VIEW_ID_FAVORITE_INDICATOR = 1005
            private const val PREVIEW_HEIGHT_DP = 80
            private const val CARD_MARGIN_DP = 12

            private val ICON_COLOR = "#d4d2a5".toColorInt()
            private val TEXT_COLOR = "#e9e6d5".toColorInt()
            private val CARD_BG_COLOR = "#1f2230".toColorInt()
            private val STROKE_COLOR = "#d4d2a5".toColorInt()

            fun create(parent: ViewGroup): ThemeViewHolder {
                val context = parent.context
                val density = context.resources.displayMetrics.density

                val rootView =
                    FrameLayout(context).apply {
                        layoutParams =
                            RecyclerView.LayoutParams(
                                RecyclerView.LayoutParams.MATCH_PARENT,
                                RecyclerView.LayoutParams.WRAP_CONTENT,
                            )

                        val cardMargin = (CARD_MARGIN_DP * density).toInt()
                        setPadding(cardMargin, cardMargin / 2, cardMargin, cardMargin / 2)
                    }

                val cardView =
                    MaterialCardView(context).apply {
                        id = VIEW_ID_CARD
                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                            )
                        radius = 12f * density
                        cardElevation = 4f * density
                        strokeColor = STROKE_COLOR
                        setCardBackgroundColor(CARD_BG_COLOR)
                    }

                val cardContent =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            )
                        val padding = (12 * density).toInt()
                        setPadding(padding, padding, padding, padding)
                    }

                val previewContainer =
                    FrameLayout(context).apply {
                        id = VIEW_ID_PREVIEW_CONTAINER
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                    }

                val bottomRow =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        gravity = Gravity.CENTER_VERTICAL
                        val topMargin = (8 * density).toInt()
                        (layoutParams as LinearLayout.LayoutParams).topMargin = topMargin
                    }

                val themeName =
                    TextView(context).apply {
                        id = VIEW_ID_THEME_NAME
                        layoutParams =
                            LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        textSize = 16f
                        setTextColor(TEXT_COLOR)
                    }

                val favoriteIndicator =
                    ImageView(context).apply {
                        id = VIEW_ID_FAVORITE_INDICATOR
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    (24 * density).toInt(),
                                    (24 * density).toInt(),
                                ).apply {
                                    marginEnd = (8 * density).toInt()
                                }
                        setImageResource(R.drawable.ic_heart_outline)
                        setColorFilter(ICON_COLOR)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }

                val selectedIndicator =
                    ImageView(context).apply {
                        id = VIEW_ID_SELECTED_INDICATOR
                        layoutParams =
                            LinearLayout.LayoutParams(
                                (24 * density).toInt(),
                                (24 * density).toInt(),
                            )
                        setImageResource(R.drawable.done_48px)
                        setColorFilter(ICON_COLOR)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        visibility = View.GONE
                    }

                bottomRow.addView(themeName)
                bottomRow.addView(favoriteIndicator)
                bottomRow.addView(selectedIndicator)

                cardContent.addView(previewContainer)
                cardContent.addView(bottomRow)

                cardView.addView(cardContent)
                rootView.addView(cardView)

                return ThemeViewHolder(rootView)
            }
        }
    }
}
