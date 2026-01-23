package com.urik.keyboard.ui.keyboard.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.urik.keyboard.theme.ThemeManager

/**
 * Visual overlay for swipe typing gesture trail.
 *
 */
class SwipeOverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        companion object {
            private const val MAX_PATH_POINTS = 500
            private const val MIN_DISTANCE_THRESHOLD_SQUARED = 25f
            private const val FADE_DURATION = 800L
        }

        private val swipePath = Path()

        private val swipePaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 8f
                alpha = 180
            }

        private val startDotPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                alpha = 200
            }

        private val currentDotPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                alpha = 160
            }

        private val shadowPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 12f
                alpha = 60
            }

        private var isActive = false
        private val startPoint = PointF()
        private var hasStartPoint = false
        private val currentPoint = PointF()
        private var hasCurrentPoint = false

        private val pathPointsX = FloatArray(MAX_PATH_POINTS)
        private val pathPointsY = FloatArray(MAX_PATH_POINTS)
        private var pathPointCount = 0

        private var fadeAlpha = 1.0f
        private var pulseScale = 1.0f
        private var fadeAnimator: ValueAnimator? = null

        private var themeManager: ThemeManager? = null

        fun setThemeManager(manager: ThemeManager) {
            themeManager = manager
        }

        fun resetColors() {
            colorsInitialized = false
        }

        private var pulseAnimator: ValueAnimator? = null

        private var colorsInitialized = false

        /**
         * Starts swipe gesture trail from initial touch point.
         *
         * Resets all state, initializes path, starts pulse animation.
         * Call on ACTION_DOWN when swipe detected.
         */
        fun startSwipe(point: PointF) {
            cleanupSwipeState()

            isActive = true
            startPoint.set(point.x, point.y)
            hasStartPoint = true
            currentPoint.set(point.x, point.y)
            hasCurrentPoint = true

            pathPointCount = 0
            pathPointsX[0] = point.x
            pathPointsY[0] = point.y
            pathPointCount = 1

            swipePath.reset()
            swipePath.moveTo(point.x, point.y)

            fadeAlpha = 1.0f
            pulseScale = 1.0f

            if (!colorsInitialized) {
                initializeColors()
                colorsInitialized = true
            }

            pulseAnimator =
                ValueAnimator.ofFloat(0.7f, 1.3f).apply {
                    duration = 800L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { animator ->
                        pulseScale = animator.animatedValue as Float
                    }
                    start()
                }

            invalidate()
        }

        private fun initializeColors() {
            try {
                val theme = themeManager?.currentTheme?.value
                if (theme != null) {
                    swipePaint.color = theme.colors.swipePrimary
                    startDotPaint.color = theme.colors.swipePrimary
                    currentDotPaint.color = theme.colors.swipeSecondary
                    shadowPaint.color = 0x40000000
                } else {
                    swipePaint.color = 0xFFd4d2a5.toInt()
                    startDotPaint.color = 0xFFd4d2a5.toInt()
                    currentDotPaint.color = 0xFFfcdebe.toInt()
                    shadowPaint.color = 0x40000000
                }
            } catch (_: Exception) {
                swipePaint.color = 0xFFd4d2a5.toInt()
                startDotPaint.color = 0xFFd4d2a5.toInt()
                currentDotPaint.color = 0xFFfcdebe.toInt()
                shadowPaint.color = 0x40000000
            }
        }

        /**
         * Updates swipe trail with new touch point.
         *
         * Adds point if >5px from last point (distance squared check).
         * Renders quadratic Bézier curve for smooth trail.
         * Caps at 100 points for performance.
         *
         * Call on ACTION_MOVE during swipe.
         */
        fun updateSwipe(point: PointF) {
            if (!isActive) return

            if (pathPointCount > 0) {
                val lastX = pathPointsX[pathPointCount - 1]
                val lastY = pathPointsY[pathPointCount - 1]
                val distanceSquared = calculateDistanceSquared(lastX, lastY, point.x, point.y)

                if (distanceSquared > MIN_DISTANCE_THRESHOLD_SQUARED) {
                    if (pathPointCount < MAX_PATH_POINTS) {
                        currentPoint.set(point.x, point.y)
                        hasCurrentPoint = true

                        pathPointsX[pathPointCount] = point.x
                        pathPointsY[pathPointCount] = point.y
                        pathPointCount++

                        val controlX = (lastX + point.x) / 2
                        val controlY = (lastY + point.y) / 2
                        swipePath.quadTo(controlX, controlY, point.x, point.y)

                        invalidate()
                    }
                }
            }
        }

        /**
         * Ends swipe gesture, starts fade-out animation.
         *
         * Call on ACTION_UP when swipe completes.
         * Trail fades over 800ms, then cleans up automatically.
         */
        fun endSwipe() {
            isActive = false
            startFadeAnimation()
        }

        private fun startFadeAnimation() {
            fadeAnimator?.cancel()

            fadeAnimator =
                ValueAnimator.ofFloat(1.0f, 0.0f).apply {
                    duration = FADE_DURATION
                    interpolator = DecelerateInterpolator()

                    addUpdateListener { animator ->
                        fadeAlpha = animator.animatedValue as Float
                        if (fadeAlpha <= 0f) {
                            cleanupSwipeState()
                        }
                        invalidate()
                    }

                    addListener(
                        object : android.animation.Animator.AnimatorListener {
                            override fun onAnimationStart(animation: android.animation.Animator) {}

                            override fun onAnimationRepeat(animation: android.animation.Animator) {}

                            override fun onAnimationCancel(animation: android.animation.Animator) {
                                cleanupSwipeState()
                            }

                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                cleanupSwipeState()
                            }
                        },
                    )

                    start()
                }
        }

        private fun cleanupSwipeState() {
            val animator = fadeAnimator
            fadeAnimator = null

            animator?.removeAllListeners()
            animator?.removeAllUpdateListeners()
            animator?.cancel()

            val pulse = pulseAnimator
            pulseAnimator = null
            pulse?.removeAllUpdateListeners()
            pulse?.cancel()

            pathPointCount = 0
            swipePath.reset()
            hasStartPoint = false
            hasCurrentPoint = false
            isActive = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (pathPointCount == 0 && !isActive) return
            if (fadeAlpha <= 0f) return

            val trailAlpha = (fadeAlpha * 180).toInt()
            val shadowAlpha = (fadeAlpha * 60).toInt()
            val startAlpha = (fadeAlpha * 200).toInt()
            val currentAlpha = (fadeAlpha * 160).toInt()

            try {
                if (pathPointCount > 1) {
                    shadowPaint.alpha = shadowAlpha
                    canvas.drawPath(swipePath, shadowPaint)
                }

                if (pathPointCount > 1) {
                    swipePaint.alpha = trailAlpha
                    canvas.drawPath(swipePath, swipePaint)
                }

                if (hasStartPoint) {
                    startDotPaint.alpha = startAlpha
                    val startRadius = 10f * fadeAlpha
                    canvas.drawCircle(startPoint.x, startPoint.y, startRadius, startDotPaint)

                    val ringAlpha = (startAlpha * 0.5f).toInt()
                    shadowPaint.alpha = ringAlpha
                    shadowPaint.strokeWidth = 2f
                    canvas.drawCircle(startPoint.x, startPoint.y, startRadius + 4f, shadowPaint)
                    shadowPaint.strokeWidth = 12f
                }

                if (isActive && hasCurrentPoint) {
                    currentDotPaint.alpha = currentAlpha
                    val currentRadius = 6f * pulseScale * fadeAlpha
                    canvas.drawCircle(currentPoint.x, currentPoint.y, currentRadius, currentDotPaint)
                }
            } catch (_: Exception) {
            }
        }

        private fun calculateDistanceSquared(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            return dx * dx + dy * dy
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            cleanupSwipeState()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            colorsInitialized = false
        }
    }
