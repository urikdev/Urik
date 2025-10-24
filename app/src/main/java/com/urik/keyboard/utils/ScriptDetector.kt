package com.urik.keyboard.utils

import com.ibm.icu.lang.UScript

object ScriptDetector {
    fun getScriptFromLocale(): Int = UScript.LATIN

    fun isRtlScript(): Boolean = false
}
