package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.view.View

class TouchCoordinateTransformer(
    private val view: View,
) {
    private val cachedPoint = PointF()

    @Volatile
    private var isRtl = false

    fun updateRtlState(rtl: Boolean) {
        isRtl = rtl
    }

    fun normalizeForHitDetection(
        rawX: Float,
        rawY: Float,
    ): PointF {
        var normalizedX = rawX

        if (isRtl && view.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            normalizedX = view.width - rawX
        }

        cachedPoint.set(normalizedX, rawY)
        return cachedPoint
    }
}
