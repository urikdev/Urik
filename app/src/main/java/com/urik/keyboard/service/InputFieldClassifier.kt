package com.urik.keyboard.service

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
    val currentInputAction: KeyboardKey.ActionType
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
            currentInputAction = ActionDetector.detectAction(info)
        )
    }
}
