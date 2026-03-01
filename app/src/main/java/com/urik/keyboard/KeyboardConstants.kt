package com.urik.keyboard

object KeyboardConstants {
    object TextProcessingConstants {
        const val MIN_SPELL_CHECK_LENGTH = 2
    }

    object MemoryConstants {
        const val LOW_MEMORY_THRESHOLD_MB = 50L
    }

    object DatabaseConstants {
        const val MAX_CLIPBOARD_ITEMS = 100
    }

    object GeometricScoringConstants {
        const val DEFAULT_SIGMA = 45f
        const val NORMAL_VELOCITY_THRESHOLD = 0.8f
        const val FAST_VELOCITY_DISCOUNT = 0.85f
        const val KEY_TRAVERSAL_RADIUS = 58f
        const val VERTEX_MIN_SEGMENT_LENGTH_PX = 25f
        const val VERTEX_FILTER_MIN_PATH_POINTS = 100
        const val PATH_COHERENCE_MIN_WORD_LENGTH = 4
        const val PATH_COHERENCE_NEUTRAL = 0.50f
    }
}
