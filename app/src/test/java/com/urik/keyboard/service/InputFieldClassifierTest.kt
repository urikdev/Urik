package com.urik.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests InputFieldClassifier field-type derivation from EditorInfo.
 */
@RunWith(RobolectricTestRunner::class)
class InputFieldClassifierTest {
    private fun createEditorInfo(
        inputClass: Int = EditorInfo.TYPE_CLASS_TEXT,
        inputVariation: Int = EditorInfo.TYPE_TEXT_VARIATION_NORMAL,
        inputFlags: Int = 0,
        imeOptions: Int = 0,
        hintText: String? = null,
        label: String? = null
    ): EditorInfo {
        val editorInfo = EditorInfo()
        editorInfo.inputType = inputClass or inputVariation or inputFlags
        editorInfo.imeOptions = imeOptions
        editorInfo.hintText = hintText
        editorInfo.label = label
        return editorInfo
    }

    @Test
    fun `classify null info returns all defaults`() {
        val result = InputFieldClassifier.classify(null)

        assertFalse(result.isSecureField)
        assertFalse(result.isDirectCommitField)
        assertFalse(result.isRawKeyEventField)
        assertFalse(result.isTerminalField)
        assertFalse(result.isUrlOrEmailField)
        assertEquals(KeyboardKey.ActionType.ENTER, result.currentInputAction)
    }

    @Test
    fun `classify url variation sets isUrlOrEmailField`() {
        val info = createEditorInfo(
            inputClass = EditorInfo.TYPE_CLASS_TEXT,
            inputVariation = EditorInfo.TYPE_TEXT_VARIATION_URI
        )

        val result = InputFieldClassifier.classify(info)

        assertTrue(result.isUrlOrEmailField)
    }

    @Test
    fun `classify email address variation sets isUrlOrEmailField`() {
        val info = createEditorInfo(
            inputClass = EditorInfo.TYPE_CLASS_TEXT,
            inputVariation = EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )

        val result = InputFieldClassifier.classify(info)

        assertTrue(result.isUrlOrEmailField)
    }

    @Test
    fun `classify web email address variation sets isUrlOrEmailField`() {
        val info = createEditorInfo(
            inputClass = EditorInfo.TYPE_CLASS_TEXT,
            inputVariation = EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )

        val result = InputFieldClassifier.classify(info)

        assertTrue(result.isUrlOrEmailField)
    }

    @Test
    fun `classify normal text isUrlOrEmailField false`() {
        val info = createEditorInfo(
            inputClass = EditorInfo.TYPE_CLASS_TEXT,
            inputVariation = EditorInfo.TYPE_TEXT_VARIATION_NORMAL
        )

        val result = InputFieldClassifier.classify(info)

        assertFalse(result.isUrlOrEmailField)
    }

    @Test
    fun `classify password field isSecureField true`() {
        val info = createEditorInfo(
            inputClass = EditorInfo.TYPE_CLASS_TEXT,
            inputVariation = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        )

        val result = InputFieldClassifier.classify(info)

        assertTrue(result.isSecureField)
    }

    @Test
    fun `classify visible password field isSecureField true`() {
        val info = createEditorInfo(
            inputClass = EditorInfo.TYPE_CLASS_TEXT,
            inputVariation = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )

        val result = InputFieldClassifier.classify(info)

        assertTrue(result.isSecureField)
    }

    @Test
    fun `classify search ime action returns search action type`() {
        val info = createEditorInfo(
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        )

        val result = InputFieldClassifier.classify(info)

        assertEquals(KeyboardKey.ActionType.SEARCH, result.currentInputAction)
    }

    @Test
    fun `classify terminal field isTerminalField true`() {
        val info = EditorInfo()
        info.inputType = InputType.TYPE_NULL

        val result = InputFieldClassifier.classify(info)

        assertTrue(result.isTerminalField)
    }
}
