package com.urik.keyboard.ui.keyboard.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
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
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {
    private val renderer = KeyboardRenderer()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var isActive = false
    private var fadeAlpha = 1.0f
    private var pulseScale = 1.0f
    private var fadeAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var themeManager: ThemeManager? = null

    fun setThemeManager(manager: ThemeManager) {
        themeManager = manager
    }

    fun resetColors() {
        renderer.resetColors()
    }

    /**
     * Starts swipe gesture trail from initial touch point.
     *
     * Resets all state, initializes path, starts pulse animation.
     * Call on ACTION_DOWN when swipe detected.
     */
    fun startSwipe(point: PointF) {
        cleanupAnimators()
        isActive = true
        val theme = themeManager?.currentTheme?.value
        if (theme != null) {
            renderer.applyTheme(theme.colors.swipePrimary, theme.colors.swipeSecondary)
        }
        if (renderer.startSwipe(point)) invalidate()
        fadeAlpha = 1.0f
        pulseScale = 1.0f
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
        if (renderer.updateSwipe(point)) invalidate()
    }

    /**
     * Ends swipe gesture, starts fade-out animation.
     *
     * Call on ACTION_UP when swipe completes.
     * Trail fades over 800ms, then cleans up automatically.
     */
    fun endSwipe() {
        isActive = false
        renderer.endSwipe()
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
                        renderer.reset()
                    }
                    invalidate()
                }

                addListener(
                    object : android.animation.Animator.AnimatorListener {
                        override fun onAnimationStart(animation: android.animation.Animator) {}

                        override fun onAnimationRepeat(animation: android.animation.Animator) {}

                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            renderer.reset()
                        }

                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            renderer.reset()
                        }
                    }
                )

                start()
            }
    }

    private fun cleanupAnimators() {
        val animator = fadeAnimator
        fadeAnimator = null

        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()

        val pulse = pulseAnimator
        pulseAnimator = null
        pulse?.removeAllUpdateListeners()
        pulse?.cancel()

        renderer.reset()
        isActive = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, fadeAlpha, pulseScale, isActive)
    }

    override fun onDetachedFromWindow() {
        cleanupAnimators()
        themeManager = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.resetColors()
    }

    companion object {
        private const val FADE_DURATION = 800L
    }
}
