package com.urik.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.utils.ActionDetector
import com.urik.keyboard.utils.SecureFieldDetector

data class FieldClassification(
    val isSecureField: Boolean,
    val isDirectCommitField: Boolean,
    val isRawKeyEventField: Boolean,
    val isTerminalField: Boolean,
    val isUrlOrEmailField: Boolean,
    val currentInputAction: KeyboardKey.ActionType,
    val isSuggestionsDisabled: Boolean = false
)

object InputFieldClassifier {
    fun classify(info: EditorInfo?): FieldClassification {
        val inputType = info?.inputType ?: 0
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        return FieldClassification(
            isSecureField = SecureFieldDetector.isSecure(info),
            isDirectCommitField = SecureFieldDetector.isDirectCommit(info),
            isRawKeyEventField = SecureFieldDetector.isRawKeyEvent(info),
            isTerminalField = SecureFieldDetector.isTerminalField(info),
            isUrlOrEmailField = variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            currentInputAction = ActionDetector.detectAction(info),
            isSuggestionsDisabled = detectSuggestionsDisabled(inputType)
        )
    }

    private fun detectSuggestionsDisabled(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS

        if (inputClass == InputType.TYPE_CLASS_PHONE) return true

        if (inputClass == InputType.TYPE_CLASS_NUMBER) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            val flags = inputType and InputType.TYPE_MASK_FLAGS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            if (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return true
            if (flags and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0) return true
            if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) return true
        }

        return false
    }
}
