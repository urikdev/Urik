package com.urik.keyboard.ui.animation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import com.urik.keyboard.R

class EditTextSimulatorView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val textPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize =
                    android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        18f,
                        resources.displayMetrics,
                    )
            }

        private val cursorPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 2f * resources.displayMetrics.density
                style = Paint.Style.FILL
            }

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val backgroundRect = RectF()

        private val text = SpannableStringBuilder()
        private var textLayout: StaticLayout? = null
        private var lastTextForLayout: String = ""
        private var lastWidthForLayout: Int = 0

        private var cursorVisible = true
        private var cursorAnimator: ValueAnimator? = null

        private val padding = (16f * resources.displayMetrics.density).toInt()
        private val cornerRadius = 8f * resources.displayMetrics.density

        init {
            updateThemeColors()
            startCursorBlink()
            minimumHeight = (textPaint.textSize * 6).toInt() + padding * 3
        }

        private fun updateThemeColors() {
            textPaint.color = ContextCompat.getColor(context, R.color.content_primary)
            cursorPaint.color = ContextCompat.getColor(context, R.color.content_primary)
            backgroundPaint.color = ContextCompat.getColor(context, R.color.surface_primary)
        }

        fun setState(state: TypewriterState) {
            when (state) {
                is TypewriterState.Idle -> {
                    text.clear()
                    cursorVisible = false
                }
                is TypewriterState.Typing -> {
                    text.clear()
                    text.append(state.text)
                    cursorVisible = state.cursorVisible
                }
                is TypewriterState.ComposingWord -> {
                    text.clear()
                    text.append(state.text)
                    text.setSpan(
                        UnderlineSpan(),
                        state.composingStart,
                        state.composingEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    cursorVisible = state.cursorVisible
                }
                is TypewriterState.ShowingError -> {
                    text.clear()
                    text.append(state.text)
                    text.setSpan(
                        BackgroundColorSpan(ContextCompat.getColor(context, android.R.color.holo_red_dark)),
                        state.errorStart,
                        state.errorEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    text.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, android.R.color.white)),
                        state.errorStart,
                        state.errorEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    cursorVisible = state.cursorVisible
                }
                is TypewriterState.ShowingSuggestions -> {
                    text.clear()
                    text.append(state.text)
                    text.setSpan(
                        BackgroundColorSpan(ContextCompat.getColor(context, android.R.color.holo_red_dark)),
                        state.errorStart,
                        state.errorEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    text.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, android.R.color.white)),
                        state.errorStart,
                        state.errorEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    cursorVisible = state.cursorVisible
                }
                is TypewriterState.SelectingSuggestion -> {
                    text.clear()
                    text.append(state.text)
                    cursorVisible = false
                }
                is TypewriterState.Complete -> {
                    text.clear()
                    text.append(state.text)
                    cursorVisible = false
                }
            }

            val currentText = text.toString()
            if (currentText != lastTextForLayout || width != lastWidthForLayout) {
                rebuildLayout()
                lastTextForLayout = currentText
                lastWidthForLayout = width
            }

            invalidate()
        }

        private fun rebuildLayout() {
            val availableWidth = width - padding * 2
            if (availableWidth <= 0 || text.isEmpty()) {
                textLayout = null
                return
            }

            textLayout =
                StaticLayout
                    .Builder
                    .obtain(text, 0, text.length, textPaint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(4f, 1.2f)
                    .setIncludePad(true)
                    .build()
        }

        private fun startCursorBlink() {
            cursorAnimator?.cancel()
            cursorAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 530L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        val wasVisible = cursorVisible
                        cursorVisible = it.animatedValue as Float > 0.5f
                        if (wasVisible != cursorVisible) {
                            invalidate()
                        }
                    }
                    start()
                }
        }

        fun pauseAnimation() {
            cursorAnimator?.pause()
        }

        fun resumeAnimation() {
            cursorAnimator?.resume()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            backgroundRect.set(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
            )
            canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint)

            val layout = textLayout
            if (text.isEmpty() || layout == null) return

            canvas.withTranslation(padding.toFloat(), padding.toFloat()) {
                layout.draw(this)

                if (cursorVisible) {
                    val lastLine = layout.lineCount - 1
                    val cursorX = layout.getLineRight(lastLine)
                    val cursorTop = layout.getLineTop(lastLine).toFloat()
                    val cursorBottom = layout.getLineBottom(lastLine).toFloat()

                    drawRect(
                        cursorX,
                        cursorTop,
                        cursorX + cursorPaint.strokeWidth,
                        cursorBottom,
                        cursorPaint,
                    )
                }
            }
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)

            val availableWidth = width - padding * 2
            if (availableWidth > 0 && text.isNotEmpty() && (width != lastWidthForLayout || text.toString() != lastTextForLayout)) {
                textLayout =
                    StaticLayout
                        .Builder
                        .obtain(text, 0, text.length, textPaint, availableWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(4f, 1.2f)
                        .setIncludePad(true)
                        .build()
                lastTextForLayout = text.toString()
                lastWidthForLayout = width
            }

            val textHeight = textLayout?.height ?: (textPaint.textSize * 4).toInt()
            val desiredHeight = textHeight + padding * 2

            val height =
                when (MeasureSpec.getMode(heightMeasureSpec)) {
                    MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
                    MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
                    else -> desiredHeight
                }

            setMeasuredDimension(width, height)
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw) {
                lastWidthForLayout = 0
                rebuildLayout()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startCursorBlink()
        }

        override fun onDetachedFromWindow() {
            cursorAnimator?.cancel()
            super.onDetachedFromWindow()
        }
    }
