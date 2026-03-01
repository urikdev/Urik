package com.urik.keyboard.service

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.InputConnection
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.BackspaceUtils
import com.urik.keyboard.utils.CursorEditingUtils

class OutputBridge(
    private val state: InputStateManager,
    private val swipeDetector: SwipeDetector,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val icProvider: () -> InputConnection?,
) {
    private val ic: InputConnection?
        get() = icProvider()

    fun safeGetTextBeforeCursor(
        length: Int,
        flags: Int = 0,
    ): String =
        try {
            ic?.getTextBeforeCursor(length, flags)
                ?.toString()
                ?.take(length)
                ?: ""
        } catch (_: Exception) {
            ""
        }

    fun safeGetTextAfterCursor(
        length: Int,
        flags: Int = 0,
    ): String =
        try {
            ic?.getTextAfterCursor(length, flags)
                ?.toString()
                ?.take(length)
                ?: ""
        } catch (_: Exception) {
            ""
        }

    fun safeGetCursorPosition(maxChars: Int = MAX_CURSOR_POSITION_CHARS): Int =
        try {
            ic?.getTextBeforeCursor(maxChars, 0)
                ?.take(maxChars)
                ?.length
                ?: 0
        } catch (_: Exception) {
            0
        }

    fun highlightCurrentWord() {
        try {
            if (state.displayBuffer.isNotEmpty()) {
                val spannableString = SpannableString(state.displayBuffer)

                spannableString.setSpan(
                    BackgroundColorSpan(Color.RED),
                    0,
                    state.displayBuffer.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                spannableString.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    0,
                    state.displayBuffer.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                ic?.beginBatchEdit()
                try {
                    ic?.setComposingText(spannableString, 1)
                } finally {
                    ic?.endBatchEdit()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun reassertComposingRegion(newSelStart: Int): Boolean {
        if (state.displayBuffer.isEmpty() || state.composingRegionStart == -1) return false
        if (state.composingReassertionCount >= MAX_COMPOSING_REASSERTIONS) return false

        val expectedStart = state.composingRegionStart
        val expectedEnd = state.composingRegionStart + state.displayBuffer.length
        if (newSelStart < expectedStart || newSelStart > expectedEnd) return false

        state.onComposingReasserted()

        ic?.beginBatchEdit()
        try {
            ic?.setComposingText(state.displayBuffer, 1)
            if (state.composingRegionStart != -1) {
                ic?.setSelection(newSelStart, newSelStart)
            }
        } finally {
            ic?.endBatchEdit()
        }
        return true
    }

    fun attemptRecompositionAtCursor(cursorPosition: Int) {
        if (state.requiresDirectCommit || state.isUrlOrEmailField) return
        if (state.displayBuffer.isNotEmpty()) return

        val textBefore = safeGetTextBeforeCursor(WORD_BOUNDARY_CONTEXT_LENGTH)
        val textAfter = safeGetTextAfterCursor(WORD_BOUNDARY_CONTEXT_LENGTH)

        if (textBefore.isNotEmpty() && (textBefore.last().isWhitespace() || textBefore.last() == '\n')) {
            return
        }

        val wordBeforeInfo =
            if (textBefore.isNotEmpty()) {
                CursorEditingUtils.extractWordBoundedByParagraph(textBefore)
            } else {
                null
            }

        if (wordBeforeInfo != null && wordBeforeInfo.first.isNotEmpty()) {
            val wordAfterStart =
                textAfter.indexOfFirst { char ->
                    char.isWhitespace() || char == '\n' || CursorEditingUtils.isPunctuation(char)
                }
            val wordAfter = if (wordAfterStart >= 0) textAfter.take(wordAfterStart) else textAfter
            val trimmedWordAfter = if (wordAfter.isNotEmpty() && CursorEditingUtils.isValidTextInput(wordAfter)) wordAfter else ""

            val fullWord = wordBeforeInfo.first + trimmedWordAfter
            val wordStart = cursorPosition - wordBeforeInfo.first.length

            if (wordStart >= 0 && fullWord.length >= 2) {
                ic?.setComposingRegion(wordStart, wordStart + fullWord.length)
                state.onRecompositionSucceeded(fullWord, wordStart)
            }
        }
    }

    fun commitPreviousSwipeAndInsertSpace() {
        if (!state.wordState.isFromSwipe || state.displayBuffer.isEmpty()) return

        swipeDetector.updateLastCommittedWord(state.displayBuffer)

        ic?.beginBatchEdit()
        try {
            ic?.finishComposingText()
            val textBefore = safeGetTextBeforeCursor(1)
            if (!swipeSpaceManager.isWhitespace(textBefore)) {
                ic?.commitText(" ", 1)
                swipeSpaceManager.markAutoSpaceInserted()
            }
        } finally {
            ic?.endBatchEdit()
        }
        state.onSwipeCommitted()
    }

    fun autoCapitalizePronounI(languageProvider: () -> String) {
        try {
            val currentLanguage = languageProvider().split("-").first()
            if (currentLanguage != "en") return
            if (state.displayBuffer.isEmpty()) return

            val capitalizedVersion = EnglishPronounI.capitalize(state.displayBuffer.lowercase())

            if (capitalizedVersion != null && capitalizedVersion != state.displayBuffer) {
                state.onPronounCapitalized(capitalizedVersion)
                ic?.setComposingText(capitalizedVersion, 1)
            }
        } catch (_: Exception) {
        }
    }

    fun clearSpellConfirmationState() {
        state.clearSpellConfirmationFields()

        try {
            if (state.displayBuffer.isNotEmpty()) {
                ic?.setComposingText(state.displayBuffer, 1)
            } else {
                ic?.finishComposingText()
            }
        } catch (_: Exception) {
        }
    }

    fun finishComposingText() {
        ic?.finishComposingText()
    }

    fun commitText(
        text: String,
        cursorPosition: Int = 1,
    ) {
        ic?.commitText(text, cursorPosition)
    }

    fun beginBatchEdit() {
        ic?.beginBatchEdit()
    }

    fun endBatchEdit() {
        ic?.endBatchEdit()
    }

    fun setComposingText(text: CharSequence, newCursorPosition: Int = 1) {
        ic?.setComposingText(text, newCursorPosition)
    }

    fun setComposingRegion(start: Int, end: Int) {
        ic?.setComposingRegion(start, end)
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        ic?.deleteSurroundingText(beforeLength, afterLength)
    }

    fun setSelection(start: Int, end: Int) {
        ic?.setSelection(start, end)
    }

    fun performEditorAction(actionCode: Int): Boolean =
        ic?.performEditorAction(actionCode) ?: false

    fun getSelectedText(flags: Int = 0): CharSequence? =
        try {
            ic?.getSelectedText(flags)
        } catch (_: Exception) {
            null
        }

    fun updateLastCommittedWord(word: String) {
        swipeDetector.updateLastCommittedWord(word)
    }

    fun coordinateStateClear() {
        state.clearInternalStateOnly()
        ic?.finishComposingText()
    }

    fun invalidateComposingStateOnCursorJump() {
        state.invalidateComposingState()
        ic?.finishComposingText()
    }

    fun calculateParagraphBoundedComposingRegion(
        textBeforeCursor: String,
        cursorPosition: Int,
    ): Triple<Int, Int, String>? {
        if (textBeforeCursor.isEmpty()) return null

        val paragraphBoundary = textBeforeCursor.lastIndexOf('\n')
        val textInParagraph =
            if (paragraphBoundary >= 0) {
                textBeforeCursor.substring(paragraphBoundary + 1)
            } else {
                textBeforeCursor
            }

        if (textInParagraph.isEmpty()) return null

        val wordInfo = BackspaceUtils.extractWordBeforeCursor(textInParagraph) ?: return null
        val (word, _) = wordInfo

        if (word.isEmpty()) return null

        val wordStart = cursorPosition - word.length
        if (wordStart < 0) return null

        if (paragraphBoundary >= 0) {
            val absoluteParagraphBoundary = cursorPosition - textInParagraph.length
            if (wordStart < absoluteParagraphBoundary) {
                return null
            }
        }

        return Triple(wordStart, cursorPosition, word)
    }

    companion object {
        const val MAX_CURSOR_POSITION_CHARS = 1000
        const val MAX_COMPOSING_REASSERTIONS = 2
        const val WORD_BOUNDARY_CONTEXT_LENGTH = 64
    }
}

object EnglishPronounI {
    fun capitalize(lowercaseWord: String): String? =
        when (lowercaseWord) {
            "i" -> "I"
            "i'm" -> "I'm"
            "i'll" -> "I'll"
            "i've" -> "I've"
            "i'd" -> "I'd"
            else -> null
        }
}
