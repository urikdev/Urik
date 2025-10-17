package com.urik.keyboard.utils

import android.view.inputmethod.EditorInfo
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests SecureFieldDetector password and PIN field detection.
 */
@RunWith(RobolectricTestRunner::class)
class SecureFieldDetectorTest {
    private fun createEditorInfo(
        inputClass: Int = EditorInfo.TYPE_CLASS_TEXT,
        inputVariation: Int = EditorInfo.TYPE_TEXT_VARIATION_NORMAL,
        hintText: String? = null,
        label: String? = null,
    ): EditorInfo {
        val editorInfo = EditorInfo()
        editorInfo.inputType = inputClass or inputVariation
        editorInfo.hintText = hintText
        editorInfo.label = label
        return editorInfo
    }

    @Test
    fun `test real Android password field constants`() {
        val passwordField =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
            )
        Assert.assertTrue(
            "Real Android password field should be detected",
            SecureFieldDetector.isSecure(passwordField),
        )

        val webPasswordField =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
        Assert.assertTrue(
            "Real Android web password field should be detected",
            SecureFieldDetector.isSecure(webPasswordField),
        )

        val pinField =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_NUMBER,
                inputVariation = EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD,
            )
        Assert.assertTrue(
            "Real Android PIN field should be detected",
            SecureFieldDetector.isSecure(pinField),
        )
    }

    @Test
    fun `test normal fields are not detected`() {
        val normalField =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_NORMAL,
            )
        Assert.assertFalse(
            "Normal text field should not be detected",
            SecureFieldDetector.isSecure(normalField),
        )
    }

    @Test
    fun `test email fields are detected as secure`() {
        val emailField =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            )
        Assert.assertTrue(
            "Email field should be detected as secure",
            SecureFieldDetector.isSecure(emailField),
        )

        val webEmailField =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            )
        Assert.assertTrue(
            "Web email field should be detected as secure",
            SecureFieldDetector.isSecure(webEmailField),
        )
    }

    @Test
    fun `test null handling`() {
        Assert.assertFalse(
            "Null EditorInfo should not be detected",
            SecureFieldDetector.isSecure(null),
        )

        val emptyField = createEditorInfo()
        Assert.assertFalse(
            "Empty EditorInfo should not be detected",
            SecureFieldDetector.isSecure(emptyField),
        )
    }

    @Test
    fun `test field type precedence over hints`() {
        val field =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
                hintText = "Enter username",
            )
        Assert.assertTrue(
            "Password field type should override non-password hint",
            SecureFieldDetector.isSecure(field),
        )
    }

    @Test
    fun `test bit mask operations work correctly`() {
        val combinedType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD

        val field = EditorInfo()
        field.inputType = combinedType

        combinedType and EditorInfo.TYPE_MASK_CLASS
        combinedType and EditorInfo.TYPE_MASK_VARIATION

        Assert.assertTrue(
            "Combined field should be detected as secure",
            SecureFieldDetector.isSecure(field),
        )
    }
}
