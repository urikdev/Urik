package com.urik.keyboard.service

import android.content.res.Configuration
import com.urik.keyboard.settings.KeySize

data class AdaptiveDimensions(
    val maxKeyboardWidthPx: Int,
    val keyHeightPx: Int,
    val minimumTouchTargetPx: Int,
    val keyMarginHorizontalPx: Int,
    val keyMarginVerticalPx: Int,
    val keyboardPaddingHorizontalPx: Int,
    val keyboardPaddingVerticalPx: Int,
    val numberRowGutterPx: Int,
    val suggestionTextMinSp: Float,
    val suggestionTextMaxSp: Float,
    val swipeActivationDp: Float,
    val gestureThresholdDp: Float,
    val splitGapPx: Int,
    val keyTextBaseRatio: Float,
    val keyTextMinSp: Float,
    val keyTextMaxSp: Float
) {
    companion object {
        private const val BASE_KEY_HEIGHT_DP = 40f
        private const val BASE_MIN_TOUCH_TARGET_DP = 44f
        private const val BASE_KEY_MARGIN_H_DP = 3f
        private const val BASE_KEY_MARGIN_V_DP = 3f
        private const val BASE_KEYBOARD_PADDING_H_DP = 8f
        private const val BASE_KEYBOARD_PADDING_V_DP = 4f
        private const val BASE_NUMBER_ROW_GUTTER_DP = 2f

        private const val LANDSCAPE_KEY_HEIGHT_DP = 30f
        private const val LANDSCAPE_KEY_MARGIN_H_DP = 2f
        private const val LANDSCAPE_KEY_MARGIN_V_DP = 2f
        private const val LANDSCAPE_MIN_TOUCH_TARGET_DP = 34f

        private const val TABLET_PORTRAIT_KEY_HEIGHT_DP = 44f
        private const val TABLET_LANDSCAPE_KEY_HEIGHT_DP = 38f
        private const val TABLET_KEY_MARGIN_H_DP = 4f

        private const val DEFAULT_SPLIT_GAP_DP = 120f

        private const val KEY_TEXT_BASE_RATIO = 0.38f

        fun compute(postureInfo: PostureInfo, keySize: KeySize, density: Float): AdaptiveDimensions {
            val isLandscape = postureInfo.orientation == Configuration.ORIENTATION_LANDSCAPE
            val isTablet = postureInfo.isTablet
            val isExpanded = postureInfo.sizeClass == DeviceSizeClass.EXPANDED
            val isFoldableSplit = postureInfo.hingeBounds != null &&
                postureInfo.posture in listOf(DevicePosture.HALF_OPENED, DevicePosture.FLAT)
            val isLargeFormFactor = isTablet || isExpanded

            val scaleFactor = keySize.scaleFactor

            val baseKeyHeightDp = when {
                isLandscape && !isLargeFormFactor -> LANDSCAPE_KEY_HEIGHT_DP
                isLargeFormFactor && isLandscape -> TABLET_LANDSCAPE_KEY_HEIGHT_DP
                isLargeFormFactor -> TABLET_PORTRAIT_KEY_HEIGHT_DP
                else -> BASE_KEY_HEIGHT_DP
            }
            val keyHeightPx = (baseKeyHeightDp * scaleFactor * density).toInt()

            val minTouchTargetDp = when {
                isLandscape && !isLargeFormFactor -> LANDSCAPE_MIN_TOUCH_TARGET_DP
                else -> BASE_MIN_TOUCH_TARGET_DP
            }
            val minimumTouchTargetPx = (minTouchTargetDp * scaleFactor * density).toInt()

            val keyMarginHDp = when {
                isLandscape && !isLargeFormFactor -> LANDSCAPE_KEY_MARGIN_H_DP
                isLargeFormFactor -> TABLET_KEY_MARGIN_H_DP
                else -> BASE_KEY_MARGIN_H_DP
            }
            val keyMarginVDp = when {
                isLandscape && !isLargeFormFactor -> LANDSCAPE_KEY_MARGIN_V_DP
                else -> BASE_KEY_MARGIN_V_DP
            }
            val keyMarginHorizontalPx = (keyMarginHDp * density).toInt()
            val keyMarginVerticalPx = (keyMarginVDp * density).toInt()

            val keyboardPaddingHPx = (BASE_KEYBOARD_PADDING_H_DP * density).toInt()
            val keyboardPaddingVPx = (BASE_KEYBOARD_PADDING_V_DP * density).toInt()

            val numberRowGutterPx = (BASE_NUMBER_ROW_GUTTER_DP * density).toInt()

            val screenWidthPx = postureInfo.screenWidthPx

            val suggestionRange = when {
                isLandscape && !isLargeFormFactor -> 13f to 15f
                isLargeFormFactor && isLandscape -> 16f to 20f
                isLargeFormFactor -> 17f to 21f
                else -> 15f to 19f
            }

            val swipeActivationDp = when {
                isLandscape && !isLargeFormFactor -> 30f
                isLargeFormFactor && isLandscape -> 38f
                isLargeFormFactor -> 40f
                else -> 35f
            }

            val gestureThresholdDp = when {
                isLandscape && !isLargeFormFactor -> 16f
                isLargeFormFactor && isLandscape -> 22f
                isLargeFormFactor -> 24f
                else -> 20f
            }

            val textRange = when {
                isLandscape && !isLargeFormFactor -> 11f to 18f
                isLargeFormFactor -> 13f to 26f
                else -> 12f to 24f
            }

            val splitGapPx = when {
                isFoldableSplit -> postureInfo.hingeBounds?.width()?.takeIf { it > 0 }
                    ?: (DEFAULT_SPLIT_GAP_DP * density).toInt()

                isLargeFormFactor -> (DEFAULT_SPLIT_GAP_DP * density).toInt()

                else -> 0
            }

            return AdaptiveDimensions(
                maxKeyboardWidthPx = screenWidthPx,
                keyHeightPx = keyHeightPx,
                minimumTouchTargetPx = minimumTouchTargetPx,
                keyMarginHorizontalPx = keyMarginHorizontalPx,
                keyMarginVerticalPx = keyMarginVerticalPx,
                keyboardPaddingHorizontalPx = keyboardPaddingHPx,
                keyboardPaddingVerticalPx = keyboardPaddingVPx,
                numberRowGutterPx = numberRowGutterPx,
                suggestionTextMinSp = suggestionRange.first,
                suggestionTextMaxSp = suggestionRange.second,
                swipeActivationDp = swipeActivationDp,
                gestureThresholdDp = gestureThresholdDp,
                splitGapPx = splitGapPx,
                keyTextBaseRatio = KEY_TEXT_BASE_RATIO,
                keyTextMinSp = textRange.first,
                keyTextMaxSp = textRange.second
            )
        }
    }
}
