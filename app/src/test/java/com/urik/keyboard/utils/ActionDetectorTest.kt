package com.urik.keyboard.utils

import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests ActionDetector IME action detection and mapping.
 */
@RunWith(RobolectricTestRunner::class)
class ActionDetectorTest {
    private fun createEditorInfo(
        imeAction: Int = EditorInfo.IME_ACTION_NONE,
        actionLabel: CharSequence? = null,
    ): EditorInfo {
        val editorInfo = EditorInfo()
        editorInfo.imeOptions = imeAction
        editorInfo.actionLabel = actionLabel
        return editorInfo
    }

    @Test
    fun `SEARCH action detected from IME options`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_SEARCH)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.SEARCH, result)
    }

    @Test
    fun `SEND action detected from IME options`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_SEND)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.SEND, result)
    }

    @Test
    fun `DONE action detected from IME options`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_DONE)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.DONE, result)
    }

    @Test
    fun `GO action detected from IME options`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_GO)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.GO, result)
    }

    @Test
    fun `NEXT action detected from IME options`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_NEXT)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.NEXT, result)
    }

    @Test
    fun `PREVIOUS action detected from IME options`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_PREVIOUS)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.PREVIOUS, result)
    }

    @Test
    fun `NONE action defaults to ENTER`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_NONE)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }

    @Test
    fun `UNSPECIFIED action defaults to ENTER`() {
        val editorInfo = createEditorInfo(imeAction = EditorInfo.IME_ACTION_UNSPECIFIED)

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }

    @Test
    fun `unknown custom label defaults to ENTER`() {
        val editorInfo =
            createEditorInfo(
                imeAction = EditorInfo.IME_ACTION_NONE,
                actionLabel = "submit",
            )

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }

    @Test
    fun `IME action takes precedence over custom label`() {
        val editorInfo =
            createEditorInfo(
                imeAction = EditorInfo.IME_ACTION_SEARCH,
                actionLabel = "send",
            )

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(
            "IME action should win over custom label",
            KeyboardKey.ActionType.SEARCH,
            result,
        )
    }

    @Test
    fun `IME DONE overrides any custom label`() {
        val editorInfo =
            createEditorInfo(
                imeAction = EditorInfo.IME_ACTION_DONE,
                actionLabel = "go",
            )

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.DONE, result)
    }

    @Test
    fun `null EditorInfo returns ENTER`() {
        val result = ActionDetector.detectAction(null)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }

    @Test
    fun `null actionLabel with no IME action returns ENTER`() {
        val editorInfo =
            createEditorInfo(
                imeAction = EditorInfo.IME_ACTION_NONE,
                actionLabel = null,
            )

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }

    @Test
    fun `empty actionLabel returns ENTER`() {
        val editorInfo =
            createEditorInfo(
                imeAction = EditorInfo.IME_ACTION_NONE,
                actionLabel = "",
            )

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }

    @Test
    fun `blank actionLabel returns ENTER`() {
        val editorInfo =
            createEditorInfo(
                imeAction = EditorInfo.IME_ACTION_NONE,
                actionLabel = "   ",
            )

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }

    @Test
    fun `IME_MASK_ACTION correctly extracts action from imeOptions`() {
        val combinedOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION

        val editorInfo = EditorInfo()
        editorInfo.imeOptions = combinedOptions

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(
            "Should extract SEARCH action despite other flags",
            KeyboardKey.ActionType.SEARCH,
            result,
        )
    }

    @Test
    fun `multiple IME flags don't interfere with action detection`() {
        val complexOptions =
            EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI

        val editorInfo = EditorInfo()
        editorInfo.imeOptions = complexOptions

        val result = ActionDetector.detectAction(editorInfo)

        assertEquals(KeyboardKey.ActionType.SEND, result)
    }

    @Test
    fun `search field in browser returns SEARCH`() {
        val searchField = createEditorInfo(imeAction = EditorInfo.IME_ACTION_SEARCH)

        val result = ActionDetector.detectAction(searchField)

        assertEquals(KeyboardKey.ActionType.SEARCH, result)
    }

    @Test
    fun `messaging app returns SEND`() {
        val messageField = createEditorInfo(imeAction = EditorInfo.IME_ACTION_SEND)

        val result = ActionDetector.detectAction(messageField)

        assertEquals(KeyboardKey.ActionType.SEND, result)
    }

    @Test
    fun `form field returns NEXT`() {
        val formField = createEditorInfo(imeAction = EditorInfo.IME_ACTION_NEXT)

        val result = ActionDetector.detectAction(formField)

        assertEquals(KeyboardKey.ActionType.NEXT, result)
    }

    @Test
    fun `last form field returns DONE`() {
        val lastField = createEditorInfo(imeAction = EditorInfo.IME_ACTION_DONE)

        val result = ActionDetector.detectAction(lastField)

        assertEquals(KeyboardKey.ActionType.DONE, result)
    }

    @Test
    fun `URL field returns GO`() {
        val urlField = createEditorInfo(imeAction = EditorInfo.IME_ACTION_GO)

        val result = ActionDetector.detectAction(urlField)

        assertEquals(KeyboardKey.ActionType.GO, result)
    }

    @Test
    fun `regular text field returns ENTER`() {
        val textField = createEditorInfo(imeAction = EditorInfo.IME_ACTION_NONE)

        val result = ActionDetector.detectAction(textField)

        assertEquals(KeyboardKey.ActionType.ENTER, result)
    }
}
