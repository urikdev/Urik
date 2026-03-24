@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutputBridgeTest {
    private lateinit var mockIc: InputConnection
    private lateinit var mockState: InputStateManager
    private lateinit var mockSwipeDetector: SwipeDetector
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var outputBridge: OutputBridge
    private var keyEventSenderCalls = mutableListOf<Int>()
    private var keyCharEventSenderCalls = mutableListOf<String>()

    @Before
    fun setup() {
        mockIc = mock()
        mockState = mock()
        mockSwipeDetector = mock()
        mockSwipeSpaceManager = mock()
        keyEventSenderCalls = mutableListOf()
        keyCharEventSenderCalls = mutableListOf()
        outputBridge =
            OutputBridge(
                state = mockState,
                swipeDetector = mockSwipeDetector,
                swipeSpaceManager = mockSwipeSpaceManager,
                icProvider = { mockIc },
                keyEventSender = { keyCode -> keyEventSenderCalls.add(keyCode) },
                keyCharEventSender = { char -> keyCharEventSenderCalls.add(char) }
            )
    }

    @Test
    fun `safeGetCursorPosition returns correct position from ExtractedText`() {
        val extracted =
            ExtractedText().apply {
                startOffset = 0
                selectionStart = 42
            }
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(extracted)

        assertEquals(42, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `safeGetCursorPosition returns correct position beyond 1000 chars`() {
        val extracted =
            ExtractedText().apply {
                startOffset = 0
                selectionStart = 1500
            }
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(extracted)

        assertEquals(1500, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `safeGetCursorPosition handles non-zero startOffset`() {
        val extracted =
            ExtractedText().apply {
                startOffset = 500
                selectionStart = 200
            }
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(extracted)

        assertEquals(700, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `safeGetCursorPosition falls back to text length when ExtractedText is null`() {
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(null)
        whenever(mockIc.getTextBeforeCursor(eq(1000), eq(0))).thenReturn("hello world")

        assertEquals(11, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `safeGetCursorPosition falls back when selectionStart is negative`() {
        val extracted =
            ExtractedText().apply {
                startOffset = 0
                selectionStart = -1
            }
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(extracted)
        whenever(mockIc.getTextBeforeCursor(eq(1000), eq(0))).thenReturn("test")

        assertEquals(4, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `safeGetCursorPosition falls back when startOffset is negative`() {
        val extracted =
            ExtractedText().apply {
                startOffset = -1
                selectionStart = 10
            }
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(extracted)
        whenever(mockIc.getTextBeforeCursor(eq(1000), eq(0))).thenReturn("test")

        assertEquals(4, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `safeGetCursorPosition returns zero on exception`() {
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0)))
            .thenThrow(RuntimeException("editor crashed"))

        assertEquals(0, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `safeGetCursorPosition returns zero when IC is null`() {
        val nullIcBridge =
            OutputBridge(
                state = mockState,
                swipeDetector = mockSwipeDetector,
                swipeSpaceManager = mockSwipeSpaceManager,
                icProvider = { null },
                keyEventSender = {},
                keyCharEventSender = {}
            )

        assertEquals(0, nullIcBridge.safeGetCursorPosition())
    }

    @Test
    fun `calculateParagraphBoundedComposingRegion correct indices at high cursor position`() {
        val result =
            outputBridge.calculateParagraphBoundedComposingRegion(
                textBeforeCursor = "the quick brown fox jumps over the lazy world",
                cursorPosition = 1500
            )

        assertNotNull(result)
        val (wordStart, wordEnd, word) = result!!
        assertEquals("world", word)
        assertEquals(1500 - "world".length, wordStart)
        assertEquals(1500, wordEnd)
    }

    @Test
    fun `calculateParagraphBoundedComposingRegion respects paragraph boundary at high position`() {
        val result =
            outputBridge.calculateParagraphBoundedComposingRegion(
                textBeforeCursor = "paragraph one\nword",
                cursorPosition = 2000
            )

        assertNotNull(result)
        val (wordStart, wordEnd, word) = result!!
        assertEquals("word", word)
        assertEquals(2000 - "word".length, wordStart)
        assertEquals(2000, wordEnd)
    }

    @Test
    fun `calculateParagraphBoundedComposingRegion returns null when ending at newline`() {
        val result =
            outputBridge.calculateParagraphBoundedComposingRegion(
                textBeforeCursor = "text\n",
                cursorPosition = 100
            )

        assertNull(result)
    }

    @Test
    fun `calculateParagraphBoundedComposingRegion returns null for empty text`() {
        val result =
            outputBridge.calculateParagraphBoundedComposingRegion(
                textBeforeCursor = "",
                cursorPosition = 100
            )

        assertNull(result)
    }

    @Test
    fun `safeGetCursorPosition with long buffer does not cap at MAX_CURSOR_POSITION_CHARS`() {
        val actualPosition = 5000
        val extracted =
            ExtractedText().apply {
                startOffset = 0
                selectionStart = actualPosition
            }
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(extracted)

        val result = outputBridge.safeGetCursorPosition()

        assertEquals(actualPosition, result)
        assert(result > OutputBridge.MAX_CURSOR_POSITION_CHARS) {
            "Position $result should exceed MAX_CURSOR_POSITION_CHARS (${OutputBridge.MAX_CURSOR_POSITION_CHARS})"
        }
    }

    @Test
    fun `cursor position integrity in backspace scenario with long buffer`() {
        val cursorPos = 1500
        val composingWord = "world"
        val composingRegionStart = cursorPos - composingWord.length

        val extracted =
            ExtractedText().apply {
                startOffset = 0
                selectionStart = cursorPos
            }
        whenever(mockIc.getExtractedText(any<ExtractedTextRequest>(), eq(0))).thenReturn(extracted)

        val absoluteCursorPos = outputBridge.safeGetCursorPosition()
        val cursorPosInWord =
            (absoluteCursorPos - composingRegionStart)
                .coerceIn(0, composingWord.length)

        assertEquals(composingWord.length, cursorPosInWord)
        assertEquals(cursorPos, absoluteCursorPos)
    }

    @Test
    fun `composing region after backspace deletion with large cursor position`() {
        val cursorBeforeDeletion = 2000
        val graphemeLength = 1
        val expectedNewPosition = cursorBeforeDeletion - graphemeLength
        val remainingText = "some context before the world"

        val result =
            outputBridge.calculateParagraphBoundedComposingRegion(
                textBeforeCursor = remainingText,
                cursorPosition = expectedNewPosition
            )

        assertNotNull(result)
        val (wordStart, wordEnd, word) = result!!
        assertEquals("world", word)
        assertEquals(expectedNewPosition, wordEnd)
        assertEquals(expectedNewPosition - "world".length, wordStart)
    }

    @Test
    fun `sendCharacter uses key events for raw key event field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(true)

        outputBridge.sendCharacter("a")

        assertEquals(listOf("a"), keyCharEventSenderCalls)
        verify(mockIc, never()).commitText(any(), any())
    }

    @Test
    fun `sendCharacter uses commitText for normal field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(false)

        outputBridge.sendCharacter("a")

        assertEquals(emptyList<String>(), keyCharEventSenderCalls)
        verify(mockIc).commitText("a", 1)
    }

    @Test
    fun `sendBackspace uses key events for raw key event field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(true)

        outputBridge.sendBackspace()

        assertEquals(listOf(KeyEvent.KEYCODE_DEL), keyEventSenderCalls)
        verify(mockIc, never()).deleteSurroundingText(any(), any())
    }

    @Test
    fun `sendBackspace uses deleteSurroundingText for normal field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(false)
        whenever(mockIc.getTextBeforeCursor(eq(1), eq(0))).thenReturn("a")

        outputBridge.sendBackspace()

        assertEquals(emptyList<Int>(), keyEventSenderCalls)
        verify(mockIc).deleteSurroundingText(1, 0)
    }

    @Test
    fun `sendBackspace no-ops for normal field with empty text before cursor`() {
        whenever(mockState.isRawKeyEventField).thenReturn(false)
        whenever(mockIc.getTextBeforeCursor(eq(1), eq(0))).thenReturn("")

        outputBridge.sendBackspace()

        assertEquals(emptyList<Int>(), keyEventSenderCalls)
        verify(mockIc, never()).deleteSurroundingText(any(), any())
    }

    @Test
    fun `sendSpace uses key events for raw key event field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(true)

        outputBridge.sendSpace()

        assertEquals(listOf(KeyEvent.KEYCODE_SPACE), keyEventSenderCalls)
        verify(mockIc, never()).commitText(any(), any())
    }

    @Test
    fun `sendSpace uses commitText for normal field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(false)

        outputBridge.sendSpace()

        assertEquals(emptyList<Int>(), keyEventSenderCalls)
        verify(mockIc).commitText(" ", 1)
    }

    @Test
    fun `sendEnter uses key events for raw key event field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(true)

        outputBridge.sendEnter()

        assertEquals(listOf(KeyEvent.KEYCODE_ENTER), keyEventSenderCalls)
        verify(mockIc, never()).commitText(any(), any())
    }

    @Test
    fun `sendEnter uses commitText for normal field`() {
        whenever(mockState.isRawKeyEventField).thenReturn(false)

        outputBridge.sendEnter()

        assertEquals(emptyList<Int>(), keyEventSenderCalls)
        verify(mockIc).commitText("\n", 1)
    }
}
