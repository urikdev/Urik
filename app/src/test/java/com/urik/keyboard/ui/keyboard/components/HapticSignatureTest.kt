package com.urik.keyboard.ui.keyboard.components

import android.os.VibrationEffect
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HapticSignatureTest {
    // ── Duration floors ───────────────────────────────────────────────────────

    @Test
    fun `LetterClick duration is at least 32ms`() {
        assertTrue(HapticSignature.LetterClick.durationMs >= 32L)
    }

    @Test
    fun `PunctuationTick duration is at least 28ms`() {
        assertTrue(HapticSignature.PunctuationTick.durationMs >= 28L)
    }

    @Test
    fun `BackspaceChirp duration is at least 28ms`() {
        assertTrue(HapticSignature.BackspaceChirp.durationMs >= 28L)
    }

    @Test
    fun `SpaceThump duration is at least 35ms`() {
        assertTrue(HapticSignature.SpaceThump.durationMs >= 35L)
    }

    @Test
    fun `ShiftPulse duration is at least 42ms`() {
        assertTrue(HapticSignature.ShiftPulse.durationMs >= 42L)
    }

    @Test
    fun `EnterCompletion duration is at least 51ms`() {
        assertTrue(HapticSignature.EnterCompletion.durationMs >= 51L)
    }

    @Test
    fun `NumberClick duration is at least 25ms`() {
        assertTrue(HapticSignature.NumberClick.durationMs >= 25L)
    }

    // ── Duration hierarchy ────────────────────────────────────────────────────

    @Test
    fun `LetterClick duration is greater than PunctuationTick duration`() {
        assertTrue(HapticSignature.LetterClick.durationMs > HapticSignature.PunctuationTick.durationMs)
    }

    @Test
    fun `PunctuationTick duration is less than BackspaceChirp duration`() {
        assertTrue(HapticSignature.PunctuationTick.durationMs < HapticSignature.BackspaceChirp.durationMs)
    }

    // ── DEFAULT_AMPLITUDE routing ─────────────────────────────────────────────

    @Test
    fun `LetterClick createEffect with DEFAULT_AMPLITUDE does not throw`() {
        val effect = HapticSignature.LetterClick.createEffect(VibrationEffect.DEFAULT_AMPLITUDE)
        assertNotNull(effect)
    }

    @Test
    fun `PunctuationTick createEffect with DEFAULT_AMPLITUDE does not throw`() {
        val effect = HapticSignature.PunctuationTick.createEffect(VibrationEffect.DEFAULT_AMPLITUDE)
        assertNotNull(effect)
    }
}
