package com.urik.keyboard.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.urik.keyboard.R

class SuggestionBarView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private val suggestionViews = mutableListOf<TextView>()
        private val dividerViews = mutableListOf<View>()

        private var appearAnimator: ValueAnimator? = null
        private var disappearAnimator: ValueAnimator? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
            val verticalPadding = (basePadding * 0.3f).toInt()
            setPadding(basePadding, verticalPadding, basePadding, verticalPadding)

            val keyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
            minimumHeight = (keyHeight * 0.8f).toInt()
            setBackgroundColor(ContextCompat.getColor(context, R.color.suggestion_bar_background))

            visibility = GONE
            alpha = 0f
            scaleY = 0.8f
        }

        fun setSuggestions(suggestions: List<String>) {
            removeAllViews()
            suggestionViews.clear()
            dividerViews.clear()

            if (suggestions.isEmpty()) {
                visibility = GONE
                return
            }

            val suggestionTextSize = calculateResponsiveSuggestionTextSize()

            suggestions.take(3).forEachIndexed { index, suggestion ->
                val textView =
                    TextView(context).apply {
                        text = suggestion
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, suggestionTextSize)
                        setTextColor(ContextCompat.getColor(context, R.color.suggestion_text))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE

                        val horizontalPadding = (suggestionTextSize * context.resources.displayMetrics.density * 1.2f).toInt()
                        val verticalPadding = (suggestionTextSize * context.resources.displayMetrics.density * 0.65f).toInt()
                        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

                        typeface = android.graphics.Typeface.DEFAULT
                    }

                suggestionViews.add(textView)
                val layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                addView(textView, layoutParams)

                if (index < suggestions.size - 1) {
                    val divider =
                        View(context).apply {
                            setBackgroundColor(ContextCompat.getColor(context, R.color.suggestion_text))
                        }

                    val dividerParams =
                        LayoutParams(
                            (1 * context.resources.displayMetrics.density).toInt(),
                            LayoutParams.MATCH_PARENT,
                        ).apply {
                            val margin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal) / 2
                            marginStart = margin
                            marginEnd = margin
                        }

                    dividerViews.add(divider)
                    addView(divider, dividerParams)
                }
            }
        }

        fun animateAppear(duration: Long = 300L) {
            appearAnimator?.cancel()
            disappearAnimator?.cancel()

            visibility = VISIBLE
            appearAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    this.duration = duration
                    interpolator = OvershootInterpolator(1.05f)
                    addUpdateListener { animator ->
                        val progress = animator.animatedValue as Float
                        alpha = progress
                        scaleY = 0.8f + (0.2f * progress)
                    }
                    start()
                }
        }

        fun animateDisappear(duration: Long = 200L) {
            appearAnimator?.cancel()
            disappearAnimator?.cancel()

            disappearAnimator =
                ValueAnimator.ofFloat(1f, 0f).apply {
                    this.duration = duration
                    addUpdateListener { animator ->
                        val progress = animator.animatedValue as Float
                        alpha = progress
                        scaleY = 0.8f + (0.2f * progress)
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                visibility = GONE
                            }
                        },
                    )
                    start()
                }
        }

        fun simulateTapOnSuggestion(
            index: Int,
            onComplete: () -> Unit,
        ) {
            if (index < 0 || index >= suggestionViews.size) {
                onComplete()
                return
            }

            val suggestionView = suggestionViews[index]
            suggestionView.isPressed = true
            suggestionView.postDelayed({
                suggestionView.isPressed = false
                onComplete()
            }, 150L)
        }

        private fun calculateResponsiveSuggestionTextSize(): Float {
            val keyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
            val baseTextSize = keyHeight * 0.30f / context.resources.displayMetrics.density
            val minSize = 13f
            val maxSize = 16f
            return baseTextSize.coerceIn(minSize, maxSize)
        }

        override fun onDetachedFromWindow() {
            appearAnimator?.cancel()
            disappearAnimator?.cancel()
            super.onDetachedFromWindow()
        }
    }
