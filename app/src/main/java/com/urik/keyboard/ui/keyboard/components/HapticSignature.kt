package com.urik.keyboard.ui.keyboard.components

import android.os.VibrationEffect

sealed class HapticSignature {
    abstract val durationMs: Long

    abstract fun createEffect(baseAmplitude: Int): VibrationEffect

    protected fun createAmplitudeEffect(
        timings: LongArray,
        amplitudes: IntArray,
        totalDurationMs: Long,
        baseAmplitude: Int
    ): VibrationEffect = if (baseAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
        VibrationEffect.createOneShot(totalDurationMs, VibrationEffect.DEFAULT_AMPLITUDE)
    } else {
        VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    data object LetterClick : HapticSignature() {
        override val durationMs = 25L

        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude = baseAmplitude.coerceIn(1, 255)
            return createAmplitudeEffect(
                longArrayOf(0, 25),
                intArrayOf(0, amplitude),
                durationMs,
                baseAmplitude
            )
        }
    }

    data object SpaceThump : HapticSignature() {
        override val durationMs = 35L

        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val peakAmplitude = baseAmplitude.coerceIn(1, 255)
            val startAmplitude = (peakAmplitude * 0.5).toInt()
            return createAmplitudeEffect(
                longArrayOf(0, 15, 20),
                intArrayOf(0, startAmplitude, peakAmplitude),
                durationMs,
                baseAmplitude
            )
        }
    }

    data object BackspaceChirp : HapticSignature() {
        override val durationMs = 28L

        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val startAmplitude = baseAmplitude.coerceIn(1, 255)
            val endAmplitude = (startAmplitude * 0.4).toInt()
            return createAmplitudeEffect(
                longArrayOf(0, 14, 14),
                intArrayOf(0, startAmplitude, endAmplitude),
                durationMs,
                baseAmplitude
            )
        }
    }

    data object ShiftPulse : HapticSignature() {
        override val durationMs = 42L

        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude = (baseAmplitude * 0.9).toInt().coerceIn(1, 255)
            return createAmplitudeEffect(
                longArrayOf(0, 15, 12, 15),
                intArrayOf(0, amplitude, 0, amplitude),
                durationMs,
                baseAmplitude
            )
        }
    }

    data object EnterCompletion : HapticSignature() {
        override val durationMs = 51L

        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val peakAmplitude = baseAmplitude.coerceIn(1, 255)
            val midAmplitude = (peakAmplitude * 0.7).toInt()
            return createAmplitudeEffect(
                longArrayOf(0, 12, 25, 14),
                intArrayOf(0, midAmplitude, peakAmplitude, midAmplitude),
                durationMs,
                baseAmplitude
            )
        }
    }

    data object PunctuationTick : HapticSignature() {
        override val durationMs = 18L

        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude = (baseAmplitude * 0.7).toInt().coerceIn(1, 255)
            return createAmplitudeEffect(
                longArrayOf(0, 18),
                intArrayOf(0, amplitude),
                durationMs,
                baseAmplitude
            )
        }
    }

    data object NumberClick : HapticSignature() {
        override val durationMs = 25L

        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude =
                if (baseAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                    VibrationEffect.DEFAULT_AMPLITUDE
                } else {
                    (baseAmplitude * 0.85).toInt().coerceIn(1, 255)
                }
            return VibrationEffect.createOneShot(25L, amplitude)
        }
    }
}
