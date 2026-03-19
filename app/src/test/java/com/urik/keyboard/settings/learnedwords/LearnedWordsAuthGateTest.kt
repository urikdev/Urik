package com.urik.keyboard.settings.learnedwords

import androidx.biometric.BiometricManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedWordsAuthGateTest {
    @Test
    fun `BIOMETRIC_SUCCESS requires authentication`() {
        assertTrue(shouldRequireAuth(BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `BIOMETRIC_ERROR_NONE_ENROLLED skips authentication`() {
        assertFalse(shouldRequireAuth(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun `BIOMETRIC_ERROR_NO_HARDWARE skips authentication`() {
        assertFalse(shouldRequireAuth(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
    }

    @Test
    fun `BIOMETRIC_ERROR_HW_UNAVAILABLE skips authentication`() {
        assertFalse(shouldRequireAuth(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun `BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED skips authentication`() {
        assertFalse(shouldRequireAuth(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
    }

    companion object {
        fun shouldRequireAuth(canAuthenticateResult: Int): Boolean =
            canAuthenticateResult == BiometricManager.BIOMETRIC_SUCCESS
    }
}
