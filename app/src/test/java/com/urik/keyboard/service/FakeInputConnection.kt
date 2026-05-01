package com.urik.keyboard.service

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

class FakeInputConnection : InputConnection {
    val textBuffer = StringBuilder()
    var selectionStart = 0
    var batchDepth = 0
    var composingStart = -1
    var composingEnd = -1
    var lastSetSelectionStart = -1
    var lastSetSelectionEnd = -1

    val committedTexts = mutableListOf<String>()
    val composingTexts = mutableListOf<CharSequence>()
    val keyEventsSent = mutableListOf<KeyEvent>()

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        textBuffer.substring(maxOf(0, selectionStart - n), selectionStart)

    override fun performContextMenuAction(p0: Int): Boolean = false

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        textBuffer.substring(selectionStart, minOf(textBuffer.length, selectionStart + n))

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
        startOffset = 0
        selectionStart = this@FakeInputConnection.selectionStart
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        committedTexts.add(text.toString())
        val insertAt = if (composingStart >= 0 && composingEnd >= 0) {
            textBuffer.delete(composingStart, composingEnd)
            composingStart
        } else {
            selectionStart
        }
        textBuffer.insert(insertAt, text)
        selectionStart = insertAt + text.length
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        composingTexts.add(text)
        if (composingStart >= 0 && composingEnd >= 0) {
            textBuffer.delete(composingStart, composingEnd)
        } else {
            composingStart = selectionStart
        }
        textBuffer.insert(composingStart, text)
        composingEnd = composingStart + text.length
        selectionStart = composingEnd
        return true
    }

    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }
    override fun endBatchEdit(): Boolean {
        batchDepth--
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val deleteFrom = maxOf(0, selectionStart - beforeLength)
        val deleteTo = minOf(textBuffer.length, selectionStart + afterLength)
        if (afterLength > 0) textBuffer.delete(selectionStart, deleteTo)
        textBuffer.delete(deleteFrom, selectionStart)
        selectionStart = deleteFrom
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        lastSetSelectionStart = start
        lastSetSelectionEnd = end
        selectionStart = start
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composingStart = start
        composingEnd = end
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean = true
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        keyEventsSent.add(event)
        return true
    }

    override fun clearMetaKeyStates(states: Int): Boolean = true
    override fun reportFullscreenMode(enabled: Boolean): Boolean = true
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
    override fun getHandler(): Handler? = null
    override fun closeConnection() {}
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
    override fun commitCompletion(text: CompletionInfo?): Boolean = false
}
