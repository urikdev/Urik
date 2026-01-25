package com.urik.keyboard.service

import android.content.Context
import android.graphics.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class DeviceSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

enum class DevicePosture {
    NORMAL,
    FLAT,
    HALF_OPENED,
}

data class PostureInfo(
    val sizeClass: DeviceSizeClass,
    val posture: DevicePosture,
    val hingeBounds: Rect? = null,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val isTablet: Boolean = false,
)

class PostureDetector(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var windowInfoTracker: WindowInfoTracker? = null
    private var windowContext: Context? = null

    private val _postureInfo = MutableStateFlow(getCurrentPostureInfo())
    val postureInfo: StateFlow<PostureInfo> = _postureInfo.asStateFlow()

    private var collectJob: Job? = null
    private var debounceJob: Job? = null
    private var fallbackJob: Job? = null

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val FALLBACK_POLL_MS = 2000L
        const val COMPACT_WIDTH_DP = 600
        const val MEDIUM_WIDTH_DP = 840
        const val TABLET_SMALLEST_WIDTH_DP = 600
    }

    fun attachToWindow(windowCtx: Context) {
        windowContext = windowCtx
        try {
            windowInfoTracker = WindowInfoTracker.getOrCreate(windowCtx)
            startWindowInfoCollection()
        } catch (e: Exception) {
            ensureFallbackPolling()
        }
    }

    fun start() {
        ensureFallbackPolling()
    }

    private fun startWindowInfoCollection() {
        collectJob?.cancel()

        val ctx = windowContext ?: return

        collectJob =
            scope.launch {
                windowInfoTracker?.windowLayoutInfo(ctx)?.collectLatest { layoutInfo ->
                    debounceJob?.cancel()
                    debounceJob =
                        launch {
                            delay(DEBOUNCE_MS)
                            updatePostureFromLayoutInfo(layoutInfo)
                        }
                }
            }
    }

    private fun ensureFallbackPolling() {
        if (fallbackJob?.isActive == true) return

        fallbackJob =
            scope.launch {
                while (true) {
                    _postureInfo.value = getCurrentPostureInfo()
                    delay(FALLBACK_POLL_MS)
                }
            }
    }

    fun stop() {
        collectJob?.cancel()
        fallbackJob?.cancel()
        debounceJob?.cancel()
        collectJob = null
        fallbackJob = null
        debounceJob = null
        windowInfoTracker = null
        windowContext = null
    }

    private fun updatePostureFromLayoutInfo(layoutInfo: androidx.window.layout.WindowLayoutInfo) {
        val displayMetrics = context.resources.displayMetrics
        val widthPx = displayMetrics.widthPixels
        val heightPx = displayMetrics.heightPixels
        val density = displayMetrics.density

        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()
        val smallestWidthDp = minOf(widthDp, heightDp)

        val sizeClass =
            when {
                widthDp < COMPACT_WIDTH_DP -> DeviceSizeClass.COMPACT
                widthDp < MEDIUM_WIDTH_DP -> DeviceSizeClass.MEDIUM
                else -> DeviceSizeClass.EXPANDED
            }

        val isTablet = smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP

        val foldingFeature = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()

        val posture =
            when {
                foldingFeature == null -> DevicePosture.NORMAL
                foldingFeature.state == FoldingFeature.State.FLAT -> DevicePosture.FLAT
                foldingFeature.state == FoldingFeature.State.HALF_OPENED -> DevicePosture.HALF_OPENED
                else -> DevicePosture.NORMAL
            }

        val hingeBounds =
            if (foldingFeature != null && foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL) {
                foldingFeature.bounds
            } else {
                null
            }

        _postureInfo.value =
            PostureInfo(
                sizeClass = sizeClass,
                posture = posture,
                hingeBounds = hingeBounds,
                screenWidthPx = widthPx,
                screenHeightPx = heightPx,
                isTablet = isTablet,
            )
    }

    private fun getCurrentPostureInfo(): PostureInfo {
        val displayMetrics = context.resources.displayMetrics
        val widthPx = displayMetrics.widthPixels
        val heightPx = displayMetrics.heightPixels
        val density = displayMetrics.density

        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()
        val smallestWidthDp = minOf(widthDp, heightDp)

        val sizeClass =
            when {
                widthDp < COMPACT_WIDTH_DP -> DeviceSizeClass.COMPACT
                widthDp < MEDIUM_WIDTH_DP -> DeviceSizeClass.MEDIUM
                else -> DeviceSizeClass.EXPANDED
            }

        val isTablet = smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP

        return PostureInfo(
            sizeClass = sizeClass,
            posture = DevicePosture.NORMAL,
            hingeBounds = null,
            screenWidthPx = widthPx,
            screenHeightPx = heightPx,
            isTablet = isTablet,
        )
    }
}
