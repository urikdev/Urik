package com.urik.keyboard.utils

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.urik.keyboard.utils.SecureFieldDetector.isSecure

/**
 * Detects sensitive input fields requiring privacy protection (passwords, PINs, emails).
 *
 * ### Detected Field Types
 * **Text passwords:**
 * - TYPE_TEXT_VARIATION_PASSWORD (standard password field)
 * - TYPE_TEXT_VARIATION_WEB_PASSWORD (HTML password input)
 * - TYPE_TEXT_VARIATION_VISIBLE_PASSWORD (show/hide password toggle)
 *
 * **Email addresses:**
 * - TYPE_TEXT_VARIATION_EMAIL_ADDRESS (email input)
 * - TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS (HTML email input)
 *
 * **Numeric passwords:**
 * - TYPE_NUMBER_VARIATION_PASSWORD (PIN codes, numeric passwords)
 *
 * ### Direct-Commit Field Types (CLI/Terminal)
 * Fields that cannot render composing text and require immediate character commitment:
 * - TYPE_NULL (raw input mode — terminals, game inputs)
 * - TYPE_TEXT_FLAG_NO_SUGGESTIONS + TYPE_TEXT_VARIATION_VISIBLE_PASSWORD (CLI editors)
 * - TYPE_TEXT_FLAG_NO_SUGGESTIONS + IME_FLAG_NO_EXTRACT_UI (apps opting out of IME features)
 *
 */
object SecureFieldDetector {
    /**
     * Detects if current input field is sensitive and requires privacy protection.
     *
     * Checks EditorInfo inputType for password/PIN/email variations. Returns true if field
     * requires bypassing learning, suggestions, auto-correction, and auto-capitalization.
     *
     * Examples of detected fields:
     * - Login password: `inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD`
     * - Web password: `inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD`
     * - PIN code: `inputType = TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD`
     * - Email address: `inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS`
     * - Visible password: `inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
     *
     * @param info EditorInfo from input field, or null if unavailable
     * @return true if field requires privacy protection, false for normal text fields
     */
    fun isSecure(info: EditorInfo?): Boolean {
        if (info == null) return false

        val inputType = info.inputType
        val inputClass = inputType and EditorInfo.TYPE_MASK_CLASS
        val inputVariation = inputType and EditorInfo.TYPE_MASK_VARIATION

        when (inputClass) {
            EditorInfo.TYPE_CLASS_TEXT if (
                inputVariation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                    inputVariation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    inputVariation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    inputVariation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    inputVariation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            ) -> return true

            EditorInfo.TYPE_CLASS_NUMBER if inputVariation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD -> return true
        }

        return false
    }

    /**
     * Detects if current input field requires direct character commitment (CLI/terminal mode).
     *
     * Unlike [isSecure], direct-commit fields are not a security concern — they are
     * a technical limitation. No cache clearing is triggered.
     *
     * @param info EditorInfo from input field, or null if unavailable
     * @return true if field requires direct-commit pipeline, false for normal text fields
     */
    fun isDirectCommit(info: EditorInfo?): Boolean {
        if (info == null) return false

        val inputType = info.inputType

        if (inputType == InputType.TYPE_NULL) return true

        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false

        val flags = inputType and InputType.TYPE_MASK_FLAGS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val hasNoSuggestions = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        if (hasNoSuggestions && variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) return true
        if (hasNoSuggestions && (info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0) return true

        return false
    }
}
