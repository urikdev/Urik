package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt
import com.urik.keyboard.theme.ThemeColors

/**
 * Renders hint symbols in the corner of keyboard keys.
 *
 * Creates drawable overlays that display custom mapping hints.
 */
class KeyHintRenderer(
    private val context: Context,
) {
    private val density = context.resources.displayMetrics.density

    /**
     * Creates a LayerDrawable combining the key background with a hint in the top-right corner.
     *
     * @param keyBackground The base background drawable for the key
     * @param hintText The hint symbol to display
     * @param colors Theme colors for styling
     * @return LayerDrawable with hint overlay
     */
    fun createKeyWithHint(
        keyBackground: Drawable,
        hintText: String,
        colors: ThemeColors,
    ): Drawable {
        val hintDrawable = HintDrawable(hintText, colors.keyTextCharacter, density)

        val layers = arrayOf(keyBackground, hintDrawable)
        return LayerDrawable(layers).apply {
            val hintSize = (8 * density).toInt()
            val padding = (4 * density).toInt()

            setLayerGravity(1, android.view.Gravity.TOP or android.view.Gravity.END)
            setLayerSize(1, hintSize, hintSize)
            setLayerInset(1, 0, padding, padding, 0)
        }
    }

    /**
     * Custom drawable for rendering hint text with reduced opacity.
     */
    private class HintDrawable(
        private val text: String,
        @ColorInt private val textColor: Int,
        private val density: Float,
    ) : Drawable() {
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 9 * density
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
            }

        private val textBounds = Rect()

        init {
            paint.alpha = HINT_ALPHA
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty || text.isEmpty()) return

            paint.getTextBounds(text, 0, text.length, textBounds)

            val x = bounds.centerX().toFloat()
            val y = bounds.centerY() + (textBounds.height() / 2f)

            canvas.drawText(text, x, y, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = (alpha * HINT_ALPHA / 255).coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = (12 * density).toInt()

        override fun getIntrinsicHeight(): Int = (12 * density).toInt()

        companion object {
            private const val HINT_ALPHA = 153
        }
    }
}
