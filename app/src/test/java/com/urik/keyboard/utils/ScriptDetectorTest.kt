package com.urik.keyboard.utils

import com.ibm.icu.lang.UScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScriptDetectorTest {
    @Test
    fun `getScriptFromLocale returns LATIN`() {
        val script = ScriptDetector.getScriptFromLocale()
        assertEquals(UScript.LATIN, script)
    }

    @Test
    fun `isRtlScript returns false`() {
        val isRtl = ScriptDetector.isRtlScript()
        assertFalse(isRtl)
    }
}
