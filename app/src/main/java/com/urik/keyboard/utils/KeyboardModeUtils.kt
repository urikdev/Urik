package com.urik.keyboard.utils

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardMode

object KeyboardModeUtils {
    fun isNumberInputType(editorInfo: EditorInfo?): Boolean {
        val inputType = editorInfo?.inputType ?: return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER
    }

    fun determineTargetMode(
        editorInfo: EditorInfo?,
        currentMode: KeyboardMode,
    ): KeyboardMode {
        val isNumberInput = isNumberInputType(editorInfo)

        return when {
            isNumberInput -> KeyboardMode.NUMBERS
            currentMode == KeyboardMode.NUMBERS -> KeyboardMode.LETTERS
            else -> currentMode
        }
    }
}
