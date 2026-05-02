@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FakeInputConnectionTest {
    private lateinit var fakeIc: FakeInputConnection

    @Before
    fun setup() {
        fakeIc = FakeInputConnection()
    }

    @Test
    fun `commitText appends to buffer and advances selectionStart`() {
        fakeIc.textBuffer.append("hel")
        fakeIc.selectionStart = 3
        fakeIc.commitText("lo", 1)
        assertEquals("hello", fakeIc.textBuffer.toString())
        assertEquals(5, fakeIc.selectionStart)
        assertEquals(listOf("lo"), fakeIc.committedTexts)
    }

    @Test
    fun `commitText clears composing region`() {
        fakeIc.textBuffer.append("hel")
        fakeIc.composingStart = 0
        fakeIc.composingEnd = 3
        fakeIc.selectionStart = 3
        fakeIc.commitText("hello", 1)
        assertEquals(-1, fakeIc.composingStart)
        assertEquals(-1, fakeIc.composingEnd)
    }

    @Test
    fun `setComposingText replaces existing composing region`() {
        fakeIc.textBuffer.append("hel")
        fakeIc.composingStart = 0
        fakeIc.composingEnd = 3
        fakeIc.selectionStart = 3
        fakeIc.setComposingText("help", 1)
        assertEquals("help", fakeIc.textBuffer.toString())
        assertEquals(4, fakeIc.selectionStart)
    }

    @Test
    fun `setComposingText records call in composingTexts`() {
        fakeIc.setComposingText("ab", 1)
        fakeIc.setComposingText("abc", 1)
        assertEquals(2, fakeIc.composingTexts.size)
    }

    @Test
    fun `getTextBeforeCursor returns correct substring`() {
        fakeIc.textBuffer.append("hello")
        fakeIc.selectionStart = 4
        val result = fakeIc.getTextBeforeCursor(2, 0)
        assertEquals("ll", result.toString())
    }

    @Test
    fun `getTextAfterCursor returns correct substring`() {
        fakeIc.textBuffer.append("hello")
        fakeIc.selectionStart = 2
        val result = fakeIc.getTextAfterCursor(3, 0)
        assertEquals("llo", result.toString())
    }

    @Test
    fun `getExtractedText returns selectionStart`() {
        fakeIc.selectionStart = 7
        val result = fakeIc.getExtractedText(null, 0)
        assertEquals(7, result.selectionStart)
        assertEquals(0, result.startOffset)
    }

    @Test
    fun `finishComposingText clears composing region`() {
        fakeIc.composingStart = 1
        fakeIc.composingEnd = 4
        fakeIc.finishComposingText()
        assertEquals(-1, fakeIc.composingStart)
        assertEquals(-1, fakeIc.composingEnd)
    }

    @Test
    fun `beginBatchEdit and endBatchEdit track nesting depth`() {
        fakeIc.beginBatchEdit()
        fakeIc.beginBatchEdit()
        fakeIc.endBatchEdit()
        assertEquals(1, fakeIc.batchDepth)
        fakeIc.endBatchEdit()
        assertEquals(0, fakeIc.batchDepth)
    }

    @Test
    fun `batchDepth is 0 after balanced begin and end`() {
        fakeIc.beginBatchEdit()
        fakeIc.endBatchEdit()
        assertEquals(0, fakeIc.batchDepth)
    }

    @Test
    fun `deleteSurroundingText removes characters before cursor`() {
        fakeIc.textBuffer.append("hello")
        fakeIc.selectionStart = 5
        fakeIc.deleteSurroundingText(2, 0)
        assertEquals("hel", fakeIc.textBuffer.toString())
        assertEquals(3, fakeIc.selectionStart)
    }

    @Test
    fun `setSelection updates selectionStart and records values`() {
        fakeIc.setSelection(3, 3)
        assertEquals(3, fakeIc.selectionStart)
        assertEquals(3, fakeIc.lastSetSelectionStart)
        assertEquals(3, fakeIc.lastSetSelectionEnd)
    }

    @Test
    fun `setComposingRegion sets composingStart and composingEnd`() {
        fakeIc.setComposingRegion(1, 4)
        assertEquals(1, fakeIc.composingStart)
        assertEquals(4, fakeIc.composingEnd)
    }

    @Test
    fun `sendKeyEvent records the event`() {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        fakeIc.sendKeyEvent(event)
        assertEquals(1, fakeIc.keyEventsSent.size)
        assertEquals(event, fakeIc.keyEventsSent[0])
    }

    @Test
    fun `performEditorAction returns true`() {
        assertTrue(fakeIc.performEditorAction(EditorInfo.IME_ACTION_DONE))
    }

    @Test
    fun `getSelectedText returns null`() {
        assertNull(fakeIc.getSelectedText(0))
    }
}
