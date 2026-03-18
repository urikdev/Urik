package com.urik.keyboard.settings.learnedwords

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.annotation.AttrRes

class AlphabetSideBar
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {
    private var letters: List<String> = emptyList()
    private var selectedIndex = -1
    private var isTouching = false
    private var onLetterSelected: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val barWidthDp = 28f
    private val barCornerRadius = 14f * density
    private val selectedScaleFactor = 1.8f
    private val neighborScaleFactor = 1.25f

    private var touchAnimationProgress = 0f
    private var activeAnimator: ValueAnimator? = null

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }

    private val selectedTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

    private val barBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundRect = RectF()

    private var textColor = 0x99000000.toInt()
    private var selectedTextColor = 0xFF1976D2.toInt()
    private var barBackgroundColor = 0x0D000000
    private var barTouchBackgroundColor = 0x1A000000
    private var selectedCircleColor = 0x1A000000

    init {
        resolveThemeColors()
    }

    fun setOnLetterSelectedListener(listener: (String) -> Unit) {
        onLetterSelected = listener
    }

    fun setLetters(newLetters: List<String>) {
        letters = newLetters
        selectedIndex = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (barWidthDp * density).toInt() + paddingLeft + paddingRight
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return

        drawBarBackground(canvas)

        val availableHeight = height - paddingTop - paddingBottom
        val letterHeight = availableHeight.toFloat() / letters.size
        val baseTextSize = (letterHeight * 0.65f)
            .coerceAtMost(11f * scaledDensity)
            .coerceAtLeast(8f * scaledDensity)
        val centerX = width / 2f

        letters.forEachIndexed { index, letter ->
            val scale = getLetterScale(index)
            val animatedScale = 1f + (scale - 1f) * touchAnimationProgress
            val isSelected = index == selectedIndex && isTouching

            val paint = if (isSelected) selectedTextPaint else textPaint
            paint.textSize = baseTextSize * animatedScale
            paint.color = if (isSelected) selectedTextColor else textColor

            val y = paddingTop + letterHeight * index + letterHeight / 2f

            if (isSelected && touchAnimationProgress > 0f) {
                drawSelectedIndicator(canvas, centerX, y, baseTextSize * animatedScale)
            }

            val textY = y - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, centerX, textY, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                startAnimator(from = 0f, to = 1f, durationMs = 200, overshoot = true)
                updateSelectedIndex(event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateSelectedIndex(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                selectedIndex = -1
                startAnimator(from = touchAnimationProgress, to = 0f, durationMs = 150, overshoot = false)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activeAnimator?.cancel()
        activeAnimator = null
    }

    private fun updateSelectedIndex(y: Float) {
        val index = getLetterIndex(y)
        if (index != selectedIndex) {
            selectedIndex = index
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
            onLetterSelected?.invoke(letters[index])
        }
    }

    private fun startAnimator(from: Float, to: Float, durationMs: Long, overshoot: Boolean) {
        activeAnimator?.cancel()
        activeAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            if (overshoot) interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                touchAnimationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun drawBarBackground(canvas: Canvas) {
        val bgAlpha = if (isTouching) {
            touchAnimationProgress
        } else {
            1f - touchAnimationProgress
        }.coerceIn(0f, 1f)

        barBackgroundPaint.color = blendColor(barBackgroundColor, barTouchBackgroundColor, bgAlpha)

        backgroundRect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat()
        )
        canvas.drawRoundRect(backgroundRect, barCornerRadius, barCornerRadius, barBackgroundPaint)
    }

    private fun drawSelectedIndicator(canvas: Canvas, cx: Float, cy: Float, textSize: Float) {
        val radius = textSize * 0.9f
        selectedCirclePaint.color = selectedCircleColor
        selectedCirclePaint.alpha = (255 * touchAnimationProgress).toInt()
        canvas.drawCircle(cx, cy, radius, selectedCirclePaint)
    }

    private fun getLetterScale(index: Int): Float {
        if (!isTouching || selectedIndex < 0) return 1f
        val distance = kotlin.math.abs(index - selectedIndex)
        return when (distance) {
            0 -> selectedScaleFactor
            1 -> neighborScaleFactor
            else -> 1f
        }
    }

    private fun getLetterIndex(y: Float): Int {
        val availableHeight = height - paddingTop - paddingBottom
        val relativeY = (y - paddingTop).coerceIn(0f, availableHeight.toFloat())
        val index = (relativeY / availableHeight * letters.size).toInt()
        return index.coerceIn(0, letters.lastIndex)
    }

    private fun resolveThemeColors() {
        textColor = resolveColorAttr(android.R.attr.textColorSecondary, textColor)
        selectedTextColor = resolveColorAttr(android.R.attr.colorPrimary, selectedTextColor)
        val highlight = resolveColorAttr(android.R.attr.colorControlHighlight, 0x1A000000)

        barBackgroundColor = adjustAlpha(highlight, 0.3f)
        barTouchBackgroundColor = adjustAlpha(highlight, 0.6f)
        selectedCircleColor = adjustAlpha(selectedTextColor, 0.15f)
    }

    private fun resolveColorAttr(@AttrRes attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attr, typedValue, true)) {
            typedValue.data
        } else {
            fallback
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return color and 0x00FFFFFF or (alpha shl 24)
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val a = (android.graphics.Color.alpha(from) * inv + android.graphics.Color.alpha(to) * ratio).toInt()
        val r = (android.graphics.Color.red(from) * inv + android.graphics.Color.red(to) * ratio).toInt()
        val g = (android.graphics.Color.green(from) * inv + android.graphics.Color.green(to) * ratio).toInt()
        val b = (android.graphics.Color.blue(from) * inv + android.graphics.Color.blue(to) * ratio).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }
}
