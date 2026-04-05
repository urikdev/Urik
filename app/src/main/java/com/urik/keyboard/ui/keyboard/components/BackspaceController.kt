package com.urik.keyboard.ui.keyboard.components

import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackspaceController(
    private val onBackspaceKey: () -> Unit,
    private val onAcceleratedDeletionChanged: (Boolean) -> Unit,
    private val vibrateEffect: (VibrationEffect) -> Unit,
    private val cancelVibration: () -> Unit,
    private val getHapticEnabled: () -> Boolean,
    private val getHapticAmplitude: () -> Int,
    private val getSupportsAmplitudeControl: () -> Boolean,
    private val getBackgroundScope: () -> CoroutineScope
) {
    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null
    private var vibrationJob: Job? = null
    private var startTime = 0L

    fun start() {
        stop()

        onAcceleratedDeletionChanged(true)

        startTime = System.currentTimeMillis()
        startSpinUp()

        backspaceRunnable =
            object : Runnable {
                override fun run() {
                    onBackspaceKey()

                    val nextInterval = calculateInterval(System.currentTimeMillis() - startTime)
                    handler.postDelayed(this, nextInterval)
                }
            }

        handler.postDelayed(backspaceRunnable!!, 50L)
    }

    fun stop() {
        backspaceRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }
        backspaceRunnable = null
        startTime = 0L
        stopSpinUp()

        onAcceleratedDeletionChanged(false)
    }

    fun cleanup() {
        stop()
    }

    @VisibleForTesting
    internal fun calculateInterval(elapsed: Long): Long {
        val startSpeed = 100L
        val endSpeed = 15L
        val accelerationDuration = 2000f

        if (elapsed >= accelerationDuration) {
            return endSpeed
        }

        val progress = elapsed / accelerationDuration
        return (startSpeed - (startSpeed - endSpeed) * progress).toLong()
    }

    private fun startSpinUp() {
        stopSpinUp()

        if (!getHapticEnabled() || getHapticAmplitude() == 0) return

        vibrationJob =
            getBackgroundScope().launch {
                var phase = 1

                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startTime

                    when {
                        elapsed < 500 -> {
                            if (phase != 1) {
                                phase = 1
                                withContext(Dispatchers.Main) {
                                    cancelVibration()
                                }
                            }

                            val phaseProgress = elapsed / 500f
                            val intervalMs = (80 - phaseProgress * 20).toLong().coerceAtLeast(60)
                            val intensity = 0.4f + phaseProgress * 0.3f
                            val amplitude =
                                if (getSupportsAmplitudeControl()) {
                                    (getHapticAmplitude() * intensity).toInt().coerceIn(1, 255)
                                } else {
                                    VibrationEffect.DEFAULT_AMPLITUDE
                                }

                            withContext(Dispatchers.Main) {
                                vibrateEffect(
                                    VibrationEffect.createOneShot(intervalMs / 2, amplitude)
                                )
                            }
                            delay(intervalMs)
                        }

                        elapsed < 1500 -> {
                            if (phase != 2) {
                                phase = 2
                                withContext(Dispatchers.Main) {
                                    cancelVibration()
                                }
                            }

                            val phaseProgress = (elapsed - 500) / 1000f
                            val intervalMs = (60 - phaseProgress * 30).toLong().coerceAtLeast(30)
                            val intensity = 0.7f + phaseProgress * 0.3f
                            val amplitude =
                                if (getSupportsAmplitudeControl()) {
                                    (getHapticAmplitude() * intensity).toInt().coerceIn(1, 255)
                                } else {
                                    VibrationEffect.DEFAULT_AMPLITUDE
                                }

                            withContext(Dispatchers.Main) {
                                vibrateEffect(
                                    VibrationEffect.createOneShot(intervalMs / 2, amplitude)
                                )
                            }
                            delay(intervalMs)
                        }

                        else -> {
                            if (phase != 3) {
                                phase = 3
                                startContinuousRumble()
                            }
                            delay(50)
                        }
                    }
                }
            }
    }

    private fun startContinuousRumble() {
        if (!getHapticEnabled() || getHapticAmplitude() == 0) return

        val rampSteps = 30
        val timings =
            LongArray(rampSteps + 10) { i ->
                if (i < rampSteps) 50L else 40L
            }

        val amplitudes =
            IntArray(rampSteps + 10) { i ->
                val progress =
                    if (i < rampSteps) {
                        i / rampSteps.toFloat()
                    } else {
                        1.0f
                    }

                val intensity = 0.4f + progress * 0.7f
                if (getSupportsAmplitudeControl()) {
                    (getHapticAmplitude() * intensity).toInt().coerceIn(1, 255)
                } else {
                    VibrationEffect.DEFAULT_AMPLITUDE
                }
            }

        getBackgroundScope().launch {
            withContext(Dispatchers.Main) {
                vibrateEffect(
                    VibrationEffect.createWaveform(
                        timings,
                        amplitudes,
                        rampSteps + 2
                    )
                )
            }
        }
    }

    private fun stopSpinUp() {
        vibrationJob?.cancel()
        vibrationJob = null
        cancelVibration()
    }
}
