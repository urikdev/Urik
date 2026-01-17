package com.urik.keyboard

object KeyboardConstants {
    object CacheConstants {
        const val PROCESSING_CACHE_MAX_SIZE = 200
        const val PROCESSING_CACHE_CLEANUP_THRESHOLD = 250
        const val CACHE_TTL_MS = 300000L

        const val SUGGESTION_CACHE_SIZE = 500
        const val DICTIONARY_CACHE_SIZE = 1000

        const val LEARNED_WORDS_CACHE_SIZE = 100
        const val LAYOUT_CACHE_SIZE = 20
        const val CHARACTER_VARIATIONS_CACHE_SIZE = 8

        const val DEFAULT_CACHE_MAX_SIZE = 100
    }

    object WordLearningConstants {
        const val MIN_WORD_LENGTH = 1
        const val MAX_WORD_LENGTH = 100
        const val MIN_FREQUENCY_THRESHOLD = 2
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val ERROR_COOLDOWN_MS = 1500L

        const val MAX_SIMILAR_WORD_LENGTH = 50
        const val MAX_NORMALIZED_WORD_LENGTH = 50
        const val MIN_PREFIX_MATCH_LENGTH = 2
        const val MIN_FUZZY_SEARCH_LENGTH = 4
        const val FUZZY_SEARCH_CANDIDATE_LIMIT = 30
        const val STRIPPED_MATCH_LIMIT_SHORT = 200
        const val STRIPPED_MATCH_LIMIT_MEDIUM = 100
        const val MAX_LENGTH_DIFFERENCE_FUZZY = 2
        const val MIN_EDIT_DISTANCE = 1
        const val MAX_EDIT_DISTANCE_FUZZY = 2

        const val MAX_EDIT_DISTANCE_STRING_LENGTH = 50
        const val MAX_EDIT_DISTANCE_ARRAY_SIZE = 51
        const val EDIT_DISTANCE_ROW_THRESHOLD = 2

        const val CLEANUP_CUTOFF_MS = 30L * 24 * 60 * 60 * 1000
    }

    object SpellCheckConstants {
        const val MAX_EDIT_DISTANCE = 2.0
        const val PREFIX_LENGTH = 7
        const val COUNT_THRESHOLD = 1L
        const val TOP_K = 100
        const val MAX_SUGGESTIONS = 5
        const val MIN_COMPLETION_LENGTH = 4
        const val APOSTROPHE_BOOST = 0.30
        const val CONTRACTION_GUARANTEED_CONFIDENCE = 0.995

        const val DICTIONARY_BATCH_SIZE = 2000
        const val INITIALIZATION_TIMEOUT_MS = 5000L

        const val FREQUENCY_BOOST_MULTIPLIER = 0.02
        const val LEARNED_WORD_BASE_CONFIDENCE = 0.95
        const val LEARNED_WORD_CONFIDENCE_MIN = 0.85
        const val LEARNED_WORD_CONFIDENCE_MAX = 0.99

        const val MAX_PREFIX_COMPLETIONS = 5
        const val FREQUENCY_SCORE_DIVISOR = 15.0
        const val COMPLETION_LENGTH_WEIGHT = 0.70
        const val COMPLETION_FREQUENCY_WEIGHT = 0.30
        const val COMPLETION_CONFIDENCE_MIN = 0.50
        const val COMPLETION_CONFIDENCE_MAX = 0.84

        const val SYMSPELL_DISTANCE_WEIGHT = 0.45
        const val SYMSPELL_FREQUENCY_WEIGHT = 0.05
        const val SYMSPELL_CONFIDENCE_MIN = 0.0
        const val SYMSPELL_CONFIDENCE_MAX = 0.70
        const val MAX_DICT_FREQUENCY = 30_000_000.0

        const val SAME_LENGTH_BONUS = 0.10
        const val SAME_FIRST_LETTER_BONUS = 0.15
        const val SAME_LAST_LETTER_BONUS = 0.10

        const val PROXIMITY_MAX_BONUS = 0.20
        const val PROXIMITY_SIGMA_MULTIPLIER = 2.0

        const val MAX_PREFIX_COMPLETION_RESULTS = 10
        const val MAX_INPUT_CODEPOINTS = 100

        const val COMMON_WORD_MIN_LENGTH = 2
        const val COMMON_WORD_MAX_LENGTH = 15

        // Dynamic frequency weighting thresholds
        const val HIGH_FREQUENCY_THRESHOLD = 10
        const val MEDIUM_FREQUENCY_THRESHOLD = 3
        const val HIGH_FREQUENCY_BASE_BOOST = 0.15
        const val HIGH_FREQUENCY_LOG_MULTIPLIER = 0.04
        const val MEDIUM_FREQUENCY_BASE_BOOST = 0.05
        const val MEDIUM_FREQUENCY_LOG_MULTIPLIER = 0.03
    }

    object SwipeDetectionConstants {
        const val MAX_SWIPE_POINTS = 75
        const val MIN_SAMPLING_INTERVAL = 2
        const val MAX_SAMPLING_INTERVAL = 8
        const val ADAPTIVE_THRESHOLD = 40
        const val ADAPTIVE_THRESHOLD_RATIO = 0.75
        const val MIN_POINT_DISTANCE = 8f
        const val MIN_CHARS_IN_BOUNDS_RATIO = 0.6f
        const val MIN_EXCELLENT_CANDIDATES = 3

        const val SWIPE_TIME_THRESHOLD_MS = 100L
        const val SWIPE_START_DISTANCE_DP = 35f
        const val MIN_SWIPE_POINTS_FOR_SAMPLING = 3
        const val SLOW_MOVEMENT_VELOCITY_THRESHOLD = 0.5f
        const val UI_UPDATE_INTERVAL_MS = 16
        const val TAP_DURATION_THRESHOLD_MS = 350L
        const val MAX_SWIPE_VELOCITY_PX_PER_MS = 10.0f

        const val PATH_BOUNDS_MARGIN_PX = 50f
        const val CLOSE_KEY_DISTANCE_THRESHOLD_SQ = 7225f
        const val EXCELLENT_CANDIDATE_THRESHOLD = 0.95f
        const val REPETITION_PENALTY_FACTOR = 0.08f

        const val PATH_EXHAUSTION_MIN_WORD_LENGTH = 5
        const val PATH_EXHAUSTION_QUARTILE_THRESHOLD = 0.75f
        const val PATH_EXHAUSTION_TAIL_RATIO = 0.4f
        const val PATH_EXHAUSTION_MIN_LETTERS_CHECK = 2
        const val LENGTH_BONUS_MIN_RATIO_QUALITY = 0.85f
        const val REPEATED_LETTER_MAX_INDEX_GAP = 3
    }

    object GeometricScoringConstants {
        const val MAX_PATH_POINTS = 100

        const val TIGHT_CLUSTER_SIGMA = 35f
        const val NORMAL_SIGMA = 42f
        const val EDGE_KEY_SIGMA = 55f
        const val DEFAULT_SIGMA = 45f

        const val NEIGHBOR_RADIUS_SQ = 10000f
        const val TIGHT_CLUSTER_THRESHOLD = 4
        const val NORMAL_CLUSTER_THRESHOLD = 2

        const val SLOW_VELOCITY_THRESHOLD = 0.3f
        const val NORMAL_VELOCITY_THRESHOLD = 0.8f
        const val SLOW_VELOCITY_BOOST = 1.35f
        const val FAST_VELOCITY_DISCOUNT = 0.85f

        const val INFLECTION_ANGLE_THRESHOLD = 0.52f
        const val INTENTIONAL_CORNER_THRESHOLD = 0.87f
        const val INTENTIONAL_CORNER_KEY_RADIUS = 60f
        const val INFLECTION_BOOST_RADIUS = 50f
        const val INFLECTION_BOOST_BASE = 1.0f
        const val INFLECTION_BOOST_MAX = 1.30f
        const val MAX_EXPECTED_ANGLE = 2.5f

        const val KEY_TRAVERSAL_RADIUS = 58f
        const val DWELL_DETECTION_RADIUS_SQ = 2500f
        const val DWELL_VELOCITY_THRESHOLD = 0.25f
        const val MAX_REPEATED_LETTER_BOOST = 1.25f

        const val MAX_CLUSTERED_WORD_LENGTH = 4
        const val CLUSTER_MAX_DISTANCE_SQ = 14400f

        const val PATH_COVERAGE_RADIUS = 45f
        const val MIN_PATH_COVERAGE_THRESHOLD = 0.60f

        const val CORNER_CURVATURE_THRESHOLD = 0.70f
        const val CURVE_CURVATURE_THRESHOLD = 0.30f
        const val SEGMENT_KEY_PROXIMITY_SQ = 3600f

        const val CONFIDENCE_INFLECTION_WEIGHT = 0.40f
        const val CONFIDENCE_VELOCITY_WEIGHT = 0.25f
        const val CONFIDENCE_SMOOTHNESS_WEIGHT = 0.35f
        const val MAX_EXPECTED_CURVATURE = 1.5f

        const val HIGH_CONFIDENCE_THRESHOLD = 0.80f
        const val MEDIUM_CONFIDENCE_THRESHOLD = 0.60f
        const val LOW_CONFIDENCE_THRESHOLD = 0.40f

        const val HIGH_CONFIDENCE_SPATIAL_WEIGHT = 0.85f
        const val HIGH_CONFIDENCE_FREQ_WEIGHT = 0.15f
        const val MEDIUM_CONFIDENCE_SPATIAL_WEIGHT = 0.72f
        const val MEDIUM_CONFIDENCE_FREQ_WEIGHT = 0.28f
        const val LOW_CONFIDENCE_SPATIAL_WEIGHT = 0.60f
        const val LOW_CONFIDENCE_FREQ_WEIGHT = 0.40f
        const val VERY_LOW_CONFIDENCE_SPATIAL_WEIGHT = 0.52f
        const val VERY_LOW_CONFIDENCE_FREQ_WEIGHT = 0.48f

        const val CLUSTERED_WORD_SPATIAL_WEIGHT = 0.92f
        const val CLUSTERED_WORD_FREQ_WEIGHT = 0.08f
        const val CLUSTERED_SEQUENCE_TOLERANCE_MULTIPLIER = 0.5f

        const val TRAVERSAL_FLOOR_SCORE = 0.65f
        const val GEOMETRIC_SIMILARITY_THRESHOLD = 0.02f

        const val VELOCITY_INTERPOLATION_THRESHOLD = 1.1f
        const val MAX_INTERPOLATED_POINTS = 3
        const val INTERPOLATION_MIN_GAP_PX = 25f
        const val LARGE_GAP_INTERPOLATION_THRESHOLD_PX = 60f

        const val WORD_LENGTH_EXCESS_PENALTY = 0.05f
        const val WORD_LENGTH_DEFICIT_PENALTY = 0.10f

        const val START_KEY_MATCH_BONUS = 1.10f
        const val START_KEY_DISTANCE_PENALTY_FACTOR = 0.30f

        const val END_KEY_MATCH_BONUS = 1.15f
        const val END_KEY_DISTANCE_PENALTY_FACTOR = 0.25f
    }

    object TextProcessingConstants {
        const val MIN_SPELL_CHECK_LENGTH = 2
        const val MIN_SUGGESTION_QUERY_LENGTH = 1
        const val MAX_WORD_INPUT_LENGTH = 50
        const val MAX_CURSOR_POSITION_CHARS = 1000
    }

    object InputTimingConstants {
        const val SUGGESTION_DEBOUNCE_MS = 10L
        const val DOUBLE_TAP_SPACE_THRESHOLD_MS = 250L
        const val DOUBLE_SHIFT_THRESHOLD_MS = 400L
    }

    object GestureConstants {
        const val GESTURE_START_DISTANCE_DP = 20f
        const val BACKSPACE_SWIPE_MIN_DISTANCE_DP = 30f
        const val BACKSPACE_INITIAL_DELAY_MS = 50L
    }

    object MemoryConstants {
        const val LOW_MEMORY_THRESHOLD_MB = 50L
        const val CRITICAL_MEMORY_THRESHOLD_MB = 20L
        const val MEMORY_CHECK_INTERVAL_MS = 30000L
        const val MEMORY_CHECK_ERROR_DELAY_MS = 60000L

        const val CRITICAL_TRIM_RATIO = 0.25
        const val MODERATE_CRITICAL_TRIM_RATIO = 0.7
        const val MODERATE_NON_CRITICAL_TRIM_RATIO = 0.5
        const val LOW_MEMORY_NON_CRITICAL_TRIM_RATIO = 0.8
        const val UI_HIDDEN_TRIM_RATIO = 0.9
    }

    object AssetLoadingConstants {
        const val MAX_ASSET_RETRIES = 3
        const val ASSET_ERROR_COOLDOWN_MS = 60000L
        const val ERROR_STATE_EXPIRY_MS = 3600000L
        const val ERROR_CLEANUP_INTERVAL_MS = 600000L
        const val ERROR_TRACKER_MAX_SIZE = 15

        const val MAX_LAYOUT_RETRIES = 3
        const val LAYOUT_ERROR_COOLDOWN_MS = 60000L
        const val LAYOUT_ERROR_STATE_EXPIRY_MS = 3600000L
        const val LAYOUT_ERROR_CLEANUP_INTERVAL_MS = 600000L
        const val LAYOUT_ERROR_TRACKER_MAX_SIZE = 20
    }

    object DatabaseConstants {
        const val DATABASE_VERSION = 4
        const val MAX_CLIPBOARD_ITEMS = 100
        const val MAX_CLIPBOARD_CONTENT_LENGTH = 100_000
    }
}
