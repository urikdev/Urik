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

    @Test
    fun `classify null info isSuggestionsDisabled false`() {
        assertFalse(InputFieldClassifier.classify(null).isSuggestionsDisabled)
    }

    @Test
    fun `classify TYPE_TEXT_FLAG_NO_SUGGESTIONS isSuggestionsDisabled true`() {
        val info = createEditorInfo(inputFlags = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertTrue(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify TYPE_TEXT_FLAG_AUTO_COMPLETE isSuggestionsDisabled true`() {
        val info = createEditorInfo(inputFlags = InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        assertTrue(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify TYPE_TEXT_VARIATION_FILTER isSuggestionsDisabled true`() {
        val info = createEditorInfo(inputVariation = InputType.TYPE_TEXT_VARIATION_FILTER)
        assertTrue(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify TYPE_CLASS_NUMBER non-password isSuggestionsDisabled true`() {
        val info = createEditorInfo(
            inputClass = InputType.TYPE_CLASS_NUMBER,
            inputVariation = InputType.TYPE_NUMBER_VARIATION_NORMAL
        )
        assertTrue(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify TYPE_CLASS_NUMBER password isSuggestionsDisabled false`() {
        val info = createEditorInfo(
            inputClass = InputType.TYPE_CLASS_NUMBER,
            inputVariation = InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        assertFalse(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify TYPE_CLASS_PHONE isSuggestionsDisabled true`() {
        val info = createEditorInfo(inputClass = InputType.TYPE_CLASS_PHONE, inputVariation = 0)
        assertTrue(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify normal text field isSuggestionsDisabled false`() {
        val info = createEditorInfo(
            inputClass = InputType.TYPE_CLASS_TEXT,
            inputVariation = InputType.TYPE_TEXT_VARIATION_NORMAL
        )
        assertFalse(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify password variation isSuggestionsDisabled false`() {
        val info = createEditorInfo(
            inputClass = InputType.TYPE_CLASS_TEXT,
            inputVariation = InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        assertFalse(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify web password variation isSuggestionsDisabled false`() {
        val info = createEditorInfo(
            inputClass = InputType.TYPE_CLASS_TEXT,
            inputVariation = InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        assertFalse(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify email address variation isSuggestionsDisabled false`() {
        val info = createEditorInfo(
            inputClass = InputType.TYPE_CLASS_TEXT,
            inputVariation = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        assertFalse(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }

    @Test
    fun `classify uri variation isSuggestionsDisabled false`() {
        val info = createEditorInfo(
            inputClass = InputType.TYPE_CLASS_TEXT,
            inputVariation = InputType.TYPE_TEXT_VARIATION_URI
        )
        assertFalse(InputFieldClassifier.classify(info).isSuggestionsDisabled)
    }
}
