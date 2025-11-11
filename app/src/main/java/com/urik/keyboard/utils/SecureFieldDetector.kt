package com.urik.keyboard.utils

import android.view.inputmethod.EditorInfo

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
}
