package com.urik.keyboard.model

import android.graphics.Rect

enum class KeyboardDisplayMode {
    STANDARD,
    ONE_HANDED_LEFT,
    ONE_HANDED_RIGHT,
    SPLIT,
}

data class KeyboardModeConfig(
    val mode: KeyboardDisplayMode,
    val widthFactor: Float = 1.0f,
    val offsetX: Float = 0f,
    val splitGapPx: Int = 0,
) {
    companion object {
        private const val ONE_HANDED_WIDTH_FACTOR = 0.85f
        private const val DEFAULT_SPLIT_GAP_DP = 120

        fun standard() =
            KeyboardModeConfig(
                mode = KeyboardDisplayMode.STANDARD,
                widthFactor = 1.0f,
                offsetX = 0f,
            )

        fun oneHandedLeft() =
            KeyboardModeConfig(
                mode = KeyboardDisplayMode.ONE_HANDED_LEFT,
                widthFactor = ONE_HANDED_WIDTH_FACTOR,
                offsetX = 0f,
            )

        fun oneHandedRight(screenWidth: Int) =
            KeyboardModeConfig(
                mode = KeyboardDisplayMode.ONE_HANDED_RIGHT,
                widthFactor = ONE_HANDED_WIDTH_FACTOR,
                offsetX = screenWidth * (1f - ONE_HANDED_WIDTH_FACTOR),
            )

        fun split(
            hingeBounds: Rect?,
            density: Float,
        ): KeyboardModeConfig {
            val gapPx =
                hingeBounds?.width()?.takeIf { it > 0 }
                    ?: (DEFAULT_SPLIT_GAP_DP * density).toInt()

            return KeyboardModeConfig(
                mode = KeyboardDisplayMode.SPLIT,
                widthFactor = 1.0f,
                splitGapPx = gapPx,
            )
        }
    }
}
