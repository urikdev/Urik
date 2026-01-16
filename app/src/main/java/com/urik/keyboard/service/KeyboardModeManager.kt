package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardModeConfig
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyboardModeManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
    ) {
        private val _currentMode = MutableStateFlow(KeyboardModeConfig.standard())
        val currentMode: StateFlow<KeyboardModeConfig> = _currentMode.asStateFlow()

        private var collectionJob: Job? = null
        private var currentPostureInfo: PostureInfo? = null

        private val density: Float
            get() = context.resources.displayMetrics.density

        fun initialize(
            scope: CoroutineScope,
            postureDetector: PostureDetector,
        ) {
            collectionJob?.cancel()

            collectionJob =
                scope.launch {
                    combine(
                        settingsRepository.settings,
                        postureDetector.postureInfo,
                    ) { settings, postureInfo ->
                        currentPostureInfo = postureInfo
                        determineMode(settings, postureInfo)
                    }.collect { config ->
                        _currentMode.value = config
                    }
                }
        }

        private fun determineMode(
            settings: KeyboardSettings,
            postureInfo: PostureInfo,
        ): KeyboardModeConfig {
            if (settings.oneHandedModeEnabled) {
                return when (settings.keyboardDisplayMode) {
                    KeyboardDisplayMode.ONE_HANDED_RIGHT ->
                        KeyboardModeConfig.oneHandedRight(postureInfo.screenWidthPx)
                    else -> KeyboardModeConfig.oneHandedLeft()
                }
            }

            if (settings.adaptiveKeyboardModesEnabled && shouldAutoSplit(postureInfo)) {
                return KeyboardModeConfig.split(postureInfo.hingeBounds, density)
            }

            return KeyboardModeConfig.standard()
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

            _currentMode.value =
                when (mode) {
                    KeyboardDisplayMode.STANDARD -> KeyboardModeConfig.standard()
                    KeyboardDisplayMode.ONE_HANDED_LEFT -> KeyboardModeConfig.oneHandedLeft()
                    KeyboardDisplayMode.ONE_HANDED_RIGHT ->
                        KeyboardModeConfig.oneHandedRight(postureInfo.screenWidthPx)
                    KeyboardDisplayMode.SPLIT ->
                        KeyboardModeConfig.split(postureInfo.hingeBounds, density)
                }

            CoroutineScope(Dispatchers.IO).launch {
                when (mode) {
                    KeyboardDisplayMode.STANDARD -> {
                        settingsRepository.updateOneHandedModeEnabled(false)
                    }
                    KeyboardDisplayMode.ONE_HANDED_LEFT,
                    KeyboardDisplayMode.ONE_HANDED_RIGHT,
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
