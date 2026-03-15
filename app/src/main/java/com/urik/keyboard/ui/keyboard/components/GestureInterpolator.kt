package com.urik.keyboard.ui.keyboard.components

import kotlin.math.sqrt

/** Catmull-Rom spline interpolation for raw touch input. */
class GestureInterpolator(private val ringBuffer: SwipePointRingBuffer) {
    private val windowX = FloatArray(WINDOW_SIZE)
    private val windowY = FloatArray(WINDOW_SIZE)
    private val windowTimestamp = LongArray(WINDOW_SIZE)
    private val windowPressure = FloatArray(WINDOW_SIZE)
    private val windowVelocity = FloatArray(WINDOW_SIZE)
    private var windowCount = 0
    private var rawPointIndex = 0
    val rawPointCount: Int get() = rawPointIndex

    fun onRawPoint(x: Float, y: Float, timestamp: Long, pressure: Float, velocity: Float) {
        rawPointIndex++
        if (windowCount < WINDOW_SIZE) {
            val i = windowCount
            windowX[i] = x
            windowY[i] = y
            windowTimestamp[i] = timestamp
            windowPressure[i] = pressure
            windowVelocity[i] = velocity
            windowCount++

            if (windowCount == WINDOW_SIZE) {
                interpolateSegment()
            }

            ringBuffer.write(x, y, timestamp, pressure, velocity)
            return
        }

        windowX[0] = windowX[1]
        windowY[0] = windowY[1]
        windowTimestamp[0] = windowTimestamp[1]
        windowPressure[0] = windowPressure[1]
        windowVelocity[0] = windowVelocity[1]

        windowX[1] = windowX[2]
        windowY[1] = windowY[2]
        windowTimestamp[1] = windowTimestamp[2]
        windowPressure[1] = windowPressure[2]
        windowVelocity[1] = windowVelocity[2]

        windowX[2] = windowX[3]
        windowY[2] = windowY[3]
        windowTimestamp[2] = windowTimestamp[3]
        windowPressure[2] = windowPressure[3]
        windowVelocity[2] = windowVelocity[3]

        windowX[3] = x
        windowY[3] = y
        windowTimestamp[3] = timestamp
        windowPressure[3] = pressure
        windowVelocity[3] = velocity

        interpolateSegment()

        ringBuffer.write(x, y, timestamp, pressure, velocity)
    }

    private fun interpolateSegment() {
        val dx = windowX[3] - windowX[2]
        val dy = windowY[3] - windowY[2]
        val segmentLength = sqrt(dx * dx + dy * dy)

        if (segmentLength < MIN_SEGMENT_FOR_INTERPOLATION) {
            return
        }

        val pointCount =
            ((segmentLength / TARGET_DENSITY_PX).toInt() - 1)
                .coerceIn(0, MAX_INTERPOLATED_PER_SEGMENT)

        if (pointCount <= 0) return

        val p1x = windowX[1]
        val p1y = windowY[1]
        val p2x = windowX[2]
        val p2y = windowY[2]
        val p3x = windowX[3]
        val p3y = windowY[3]

        val t1 = windowTimestamp[2]
        val t2 = windowTimestamp[3]
        val pr1 = windowPressure[2]
        val pr2 = windowPressure[3]
        val v1 = windowVelocity[2]
        val v2 = windowVelocity[3]

        for (i in 1..pointCount) {
            val t = i.toFloat() / (pointCount + 1)
            val t2f = t * t
            val t3f = t2f * t

            val interpX =
                ALPHA * (
                    (-p1x + 2f * p2x - p3x) * t3f +
                        (2f * p1x - 4f * p2x + 2f * p3x) * t2f +
                        (-p1x + p3x) * t +
                        2f * p2x
                    )

            val interpY =
                ALPHA * (
                    (-p1y + 2f * p2y - p3y) * t3f +
                        (2f * p1y - 4f * p2y + 2f * p3y) * t2f +
                        (-p1y + p3y) * t +
                        2f * p2y
                    )

            val interpTimestamp = t1 + ((t2 - t1) * t).toLong()
            val interpPressure = pr1 + (pr2 - pr1) * t
            val interpVelocity = v1 + (v2 - v1) * t

            ringBuffer.write(interpX, interpY, interpTimestamp, interpPressure, interpVelocity)
        }
    }

    fun reset() {
        windowCount = 0
        rawPointIndex = 0
    }

    companion object {
        private const val WINDOW_SIZE = 4
        private const val TARGET_DENSITY_PX = 6f
        private const val MIN_SEGMENT_FOR_INTERPOLATION = 6f
        private const val MAX_INTERPOLATED_PER_SEGMENT = 10
        private const val ALPHA = 0.5f
    }
}
