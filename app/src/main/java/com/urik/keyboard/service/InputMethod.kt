package com.urik.keyboard.service

/**
 * Tracks how words were entered for learning context.
 */
enum class InputMethod {
    /** Character-by-character typing */
    TYPED,

    /** Swipe gesture across keys */
    SWIPED,

    /** Selected from suggestion bar */
    SELECTED_FROM_SUGGESTION,
}
