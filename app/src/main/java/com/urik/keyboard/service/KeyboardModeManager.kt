package com.urik.keyboard.service

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.di.ApplicationScope
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardModeConfig
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class KeyboardModeManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val _currentMode = MutableStateFlow(KeyboardModeConfig.standard())
    val currentMode: StateFlow<KeyboardModeConfig> = _currentMode.asStateFlow()

    private var collectionJob: Job? = null
    private var currentPostureInfo: PostureInfo? = null
    private var latestSettings: KeyboardSettings? = null

    private val density: Float
        get() = context.resources.displayMetrics.density

    fun initialize(scope: CoroutineScope, postureDetector: PostureDetector) {
        collectionJob?.cancel()

        collectionJob =
            scope.launch {
                combine(
                    settingsRepository.settings,
                    postureDetector.postureInfo
                ) { settings, postureInfo ->
                    latestSettings = settings
                    currentPostureInfo = postureInfo
                    determineMode(settings, postureInfo)
                }.collect { config ->
                    _currentMode.value = config
                }
            }
    }

    @VisibleForTesting
    internal fun determineMode(settings: KeyboardSettings, postureInfo: PostureInfo): KeyboardModeConfig {
        val dimensions = AdaptiveDimensions.compute(
            postureInfo = postureInfo,
            keySize = settings.keySize,
            density = density
        )

        if (settings.oneHandedModeEnabled) {
            val base = when (settings.keyboardDisplayMode) {
                KeyboardDisplayMode.ONE_HANDED_RIGHT ->
                    KeyboardModeConfig.oneHandedRight(postureInfo.screenWidthPx)

                else -> KeyboardModeConfig.oneHandedLeft()
            }
            return base.copy(adaptiveDimensions = dimensions)
        }

        val isLandscapeCompact = postureInfo.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            postureInfo.sizeClass == DeviceSizeClass.COMPACT
        if (isLandscapeCompact) {
            return KeyboardModeConfig.standard().copy(adaptiveDimensions = dimensions)
        }

        if (settings.adaptiveKeyboardModesEnabled && shouldAutoSplit(postureInfo)) {
            return KeyboardModeConfig(
                mode = KeyboardDisplayMode.SPLIT,
                widthFactor = 1.0f,
                splitGapPx = dimensions.splitGapPx,
                adaptiveDimensions = dimensions
            )
        }

        return KeyboardModeConfig.standard().copy(adaptiveDimensions = dimensions)
    }

    private fun shouldAutoSplit(postureInfo: PostureInfo): Boolean {
        val hasFoldableHinge =
            postureInfo.hingeBounds != null &&
                postureInfo.posture in listOf(DevicePosture.HALF_OPENED, DevicePosture.FLAT)

        if (hasFoldableHinge) return true

        return postureInfo.isTablet
    }

    fun setManualMode(mode: KeyboardDisplayMode) {
        val postureInfo = currentPostureInfo ?: return
        val settings = latestSettings ?: KeyboardSettings()

        val dimensions = AdaptiveDimensions.compute(
            postureInfo = postureInfo,
            keySize = settings.keySize,
            density = density
        )

        _currentMode.value =
            when (mode) {
                KeyboardDisplayMode.STANDARD ->
                    KeyboardModeConfig.standard().copy(adaptiveDimensions = dimensions)

                KeyboardDisplayMode.ONE_HANDED_LEFT ->
                    KeyboardModeConfig.oneHandedLeft().copy(adaptiveDimensions = dimensions)

                KeyboardDisplayMode.ONE_HANDED_RIGHT ->
                    KeyboardModeConfig.oneHandedRight(postureInfo.screenWidthPx)
                        .copy(adaptiveDimensions = dimensions)

                KeyboardDisplayMode.SPLIT ->
                    KeyboardModeConfig(
                        mode = KeyboardDisplayMode.SPLIT,
                        widthFactor = 1.0f,
                        splitGapPx = dimensions.splitGapPx,
                        adaptiveDimensions = dimensions
                    )
            }

        applicationScope.launch {
            withContext(Dispatchers.IO) {
                when (mode) {
                    KeyboardDisplayMode.STANDARD -> {
                        settingsRepository.updateOneHandedModeEnabled(false)
                    }

                    KeyboardDisplayMode.ONE_HANDED_LEFT,
                    KeyboardDisplayMode.ONE_HANDED_RIGHT
                    -> {
                        settingsRepository.updateKeyboardDisplayMode(mode)
                        settingsRepository.updateOneHandedModeEnabled(true)
                    }

                    KeyboardDisplayMode.SPLIT -> {
                        settingsRepository.updateKeyboardDisplayMode(mode)
                    }
                }
            }
        }
    }
}
