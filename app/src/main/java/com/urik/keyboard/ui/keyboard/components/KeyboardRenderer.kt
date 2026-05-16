package com.urik.keyboard.ui.keyboard.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.urik.keyboard.utils.ErrorLogger

class KeyboardRenderer {
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

    private val pathPointsX = FloatArray(MAX_PATH_POINTS)
    private val pathPointsY = FloatArray(MAX_PATH_POINTS)
    private var pathPointCount = 0
    private val startPoint = PointF()
    private val currentPoint = PointF()
    private var hasStartPoint = false
    private var hasCurrentPoint = false
    private var colorsInitialized = false

    fun startSwipe(point: PointF): Boolean {
        resetPath()
        hasStartPoint = true
        startPoint.set(point.x, point.y)
        pathPointsX[0] = point.x
        pathPointsY[0] = point.y
        pathPointCount = 1
        swipePath.reset()
        swipePath.moveTo(point.x, point.y)
        if (!colorsInitialized) {
            initializeColors()
            colorsInitialized = true
        }
        return true
    }

    fun updateSwipe(point: PointF): Boolean {
        if (pathPointCount > 0) {
            val lastX = pathPointsX[pathPointCount - 1]
            val lastY = pathPointsY[pathPointCount - 1]
            val distSq = (point.x - lastX) * (point.x - lastX) + (point.y - lastY) * (point.y - lastY)
            if (distSq > MIN_DISTANCE_THRESHOLD_SQUARED && pathPointCount < MAX_PATH_POINTS) {
                currentPoint.set(point.x, point.y)
                hasCurrentPoint = true
                pathPointsX[pathPointCount] = point.x
                pathPointsY[pathPointCount] = point.y
                pathPointCount++
                val controlX = (lastX + point.x) / 2
                val controlY = (lastY + point.y) / 2
                swipePath.quadTo(controlX, controlY, point.x, point.y)
                return true
            }
        }
        return false
    }

    fun endSwipe() {
        // animator is in SwipeOverlayView
    }

    fun draw(canvas: Canvas, fadeAlpha: Float, pulseScale: Float, isActive: Boolean) {
        if (pathPointCount == 0 && !isActive) return
        if (fadeAlpha <= 0f) return
        val trailAlpha = (fadeAlpha * 180).toInt()
        val startAlpha = (fadeAlpha * 200).toInt()
        val currentAlpha = (fadeAlpha * 160).toInt()
        try {
            if (pathPointCount > 1) {
                swipePaint.alpha = trailAlpha
                canvas.drawPath(swipePath, swipePaint)
            }
            if (isActive && hasCurrentPoint) {
                currentDotPaint.alpha = currentAlpha
                val currentRadius = 6f * pulseScale * fadeAlpha
                canvas.drawCircle(currentPoint.x, currentPoint.y, currentRadius, currentDotPaint)
            } else if (hasStartPoint) {
                startDotPaint.alpha = startAlpha
                val startRadius = 10f * fadeAlpha
                canvas.drawCircle(startPoint.x, startPoint.y, startRadius, startDotPaint)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KeyboardRenderer",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "draw")
            )
        }
    }

    fun applyTheme(swipePrimaryColor: Int, swipeSecondaryColor: Int) {
        swipePaint.color = swipePrimaryColor
        startDotPaint.color = swipePrimaryColor
        currentDotPaint.color = swipeSecondaryColor
        colorsInitialized = true
    }

    fun resetColors() {
        colorsInitialized = false
    }

    fun reset() {
        swipePath.reset()
        pathPointCount = 0
        hasStartPoint = false
        hasCurrentPoint = false
    }

    private fun initializeColors() {
        swipePaint.color = 0xFFd4d2a5.toInt()
        startDotPaint.color = 0xFFd4d2a5.toInt()
        currentDotPaint.color = 0xFFfcdebe.toInt()
    }

    private fun resetPath() {
        swipePath.reset()
        pathPointCount = 0
        hasStartPoint = false
        hasCurrentPoint = false
    }

    companion object {
        private const val MAX_PATH_POINTS = 500
        private const val MIN_DISTANCE_THRESHOLD_SQUARED = 25f
    }
}
