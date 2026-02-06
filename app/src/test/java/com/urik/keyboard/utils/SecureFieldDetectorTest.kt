package com.urik.keyboard.utils

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests SecureFieldDetector password/PIN field detection and direct-commit field detection.
 */
@RunWith(RobolectricTestRunner::class)
class SecureFieldDetectorTest {
    private fun createEditorInfo(
        inputClass: Int = EditorInfo.TYPE_CLASS_TEXT,
        inputVariation: Int = EditorInfo.TYPE_TEXT_VARIATION_NORMAL,
        inputFlags: Int = 0,
        imeOptions: Int = 0,
        hintText: String? = null,
        label: String? = null,
    ): EditorInfo {
        val editorInfo = EditorInfo()
        editorInfo.inputType = inputClass or inputVariation or inputFlags
        editorInfo.imeOptions = imeOptions
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

    @Test
    fun `test TYPE_NULL detected as direct commit`() {
        val field = EditorInfo()
        field.inputType = InputType.TYPE_NULL

        Assert.assertTrue(
            "TYPE_NULL should be detected as direct commit",
            SecureFieldDetector.isDirectCommit(field),
        )
        Assert.assertFalse(
            "TYPE_NULL should not be detected as secure",
            SecureFieldDetector.isSecure(field),
        )
    }

    @Test
    fun `test no suggestions with visible password detected as direct commit`() {
        val field =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                inputFlags = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            )

        Assert.assertTrue(
            "NO_SUGGESTIONS + VISIBLE_PASSWORD should be detected as direct commit",
            SecureFieldDetector.isDirectCommit(field),
        )
    }

    @Test
    fun `test no suggestions with no extract UI detected as direct commit`() {
        val field =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_NORMAL,
                inputFlags = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI,
            )

        Assert.assertTrue(
            "NO_SUGGESTIONS + NO_EXTRACT_UI should be detected as direct commit",
            SecureFieldDetector.isDirectCommit(field),
        )
    }

    @Test
    fun `test normal text field is not direct commit`() {
        val field =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_NORMAL,
            )

        Assert.assertFalse(
            "Normal text field should not be detected as direct commit",
            SecureFieldDetector.isDirectCommit(field),
        )
    }

    @Test
    fun `test no suggestions alone is not direct commit`() {
        val field =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_NORMAL,
                inputFlags = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            )

        Assert.assertFalse(
            "NO_SUGGESTIONS alone should not trigger direct commit",
            SecureFieldDetector.isDirectCommit(field),
        )
    }

    @Test
    fun `test visible password alone is not direct commit`() {
        val field =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            )

        Assert.assertFalse(
            "VISIBLE_PASSWORD without NO_SUGGESTIONS should not trigger direct commit",
            SecureFieldDetector.isDirectCommit(field),
        )
    }

    @Test
    fun `test number class is not direct commit`() {
        val field =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_NUMBER,
                inputVariation = EditorInfo.TYPE_NUMBER_VARIATION_NORMAL,
            )

        Assert.assertFalse(
            "Number class should not be detected as direct commit",
            SecureFieldDetector.isDirectCommit(field),
        )
    }

    @Test
    fun `test null returns false for direct commit`() {
        Assert.assertFalse(
            "Null EditorInfo should not be detected as direct commit",
            SecureFieldDetector.isDirectCommit(null),
        )
    }

    @Test
    fun `test password fields are not direct commit`() {
        val passwordField =
            createEditorInfo(
                inputClass = EditorInfo.TYPE_CLASS_TEXT,
                inputVariation = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
            )

        Assert.assertFalse(
            "Password field should not be detected as direct commit",
            SecureFieldDetector.isDirectCommit(passwordField),
        )
    }
}
