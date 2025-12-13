package com.urik.keyboard.ui.keyboard.components

import android.os.VibrationEffect

sealed class HapticSignature {
    abstract fun createEffect(baseAmplitude: Int): VibrationEffect

    data object LetterClick : HapticSignature() {
        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude = baseAmplitude.coerceIn(1, 255)
            return VibrationEffect.createWaveform(
                longArrayOf(0, 25),
                intArrayOf(0, amplitude),
                -1,
            )
        }
    }

    data object SpaceThump : HapticSignature() {
        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val peakAmplitude = baseAmplitude.coerceIn(1, 255)
            val startAmplitude = (peakAmplitude * 0.5).toInt()
            return VibrationEffect.createWaveform(
                longArrayOf(0, 15, 20),
                intArrayOf(0, startAmplitude, peakAmplitude),
                -1,
            )
        }
    }

    data object BackspaceChirp : HapticSignature() {
        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val startAmplitude = baseAmplitude.coerceIn(1, 255)
            val endAmplitude = (startAmplitude * 0.4).toInt()
            return VibrationEffect.createWaveform(
                longArrayOf(0, 14, 14),
                intArrayOf(0, startAmplitude, endAmplitude),
                -1,
            )
        }
    }

    data object ShiftPulse : HapticSignature() {
        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude = (baseAmplitude * 0.9).toInt().coerceIn(1, 255)
            return VibrationEffect.createWaveform(
                longArrayOf(0, 15, 12, 15),
                intArrayOf(0, amplitude, 0, amplitude),
                -1,
            )
        }
    }

    data object EnterCompletion : HapticSignature() {
        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val peakAmplitude = baseAmplitude.coerceIn(1, 255)
            val midAmplitude = (peakAmplitude * 0.7).toInt()
            return VibrationEffect.createWaveform(
                longArrayOf(0, 12, 25, 14),
                intArrayOf(0, midAmplitude, peakAmplitude, midAmplitude),
                -1,
            )
        }
    }

    data object PunctuationTick : HapticSignature() {
        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude = (baseAmplitude * 0.7).toInt().coerceIn(1, 255)
            return VibrationEffect.createWaveform(
                longArrayOf(0, 18),
                intArrayOf(0, amplitude),
                -1,
            )
        }
    }

    data object NumberClick : HapticSignature() {
        override fun createEffect(baseAmplitude: Int): VibrationEffect {
            val amplitude = (baseAmplitude * 0.85).toInt().coerceIn(1, 255)
            return VibrationEffect.createOneShot(25L, amplitude)
        }
    }
}
