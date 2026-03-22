package com.urik.keyboard.model

import com.urik.keyboard.service.AdaptiveDimensions

enum class KeyboardDisplayMode {
    STANDARD,
    ONE_HANDED_LEFT,
    ONE_HANDED_RIGHT,
    SPLIT
}

data class KeyboardModeConfig(
    val mode: KeyboardDisplayMode,
    val widthFactor: Float = 1.0f,
    val offsetX: Float = 0f,
    val splitGapPx: Int = 0,
    val adaptiveDimensions: AdaptiveDimensions? = null
) {
    companion object {
        private const val ONE_HANDED_WIDTH_FACTOR = 0.90f

        fun standard() = KeyboardModeConfig(
            mode = KeyboardDisplayMode.STANDARD,
            widthFactor = 1.0f,
            offsetX = 0f
        )

        fun oneHandedLeft() = KeyboardModeConfig(
            mode = KeyboardDisplayMode.ONE_HANDED_LEFT,
            widthFactor = ONE_HANDED_WIDTH_FACTOR,
            offsetX = 0f
        )

        fun oneHandedRight(screenWidth: Int) = KeyboardModeConfig(
            mode = KeyboardDisplayMode.ONE_HANDED_RIGHT,
            widthFactor = ONE_HANDED_WIDTH_FACTOR,
            offsetX = screenWidth * (1f - ONE_HANDED_WIDTH_FACTOR)
        )
    }
}
