@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutputBridgeWithFakeIcTest {
    private lateinit var fakeIc: FakeInputConnection
    private lateinit var mockState: InputStateManager
    private lateinit var mockSwipeDetector: SwipeDetector
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var outputBridge: OutputBridge
    private var keyEventSenderCalls = mutableListOf<Int>()
    private var keyCharEventSenderCalls = mutableListOf<String>()

    @Before
    fun setup() {
        fakeIc = FakeInputConnection()
        mockState = mock()
        mockSwipeDetector = mock()
        mockSwipeSpaceManager = mock()
        keyEventSenderCalls = mutableListOf()
        keyCharEventSenderCalls = mutableListOf()
        outputBridge = OutputBridge(
            state = mockState,
            swipeDetector = mockSwipeDetector,
            swipeSpaceManager = mockSwipeSpaceManager,
            icProvider = { fakeIc },
            keyEventSender = { keyCode -> keyEventSenderCalls.add(keyCode) },
            keyCharEventSender = { char -> keyCharEventSenderCalls.add(char) }
        )
    }

    @Test
    fun `safeGetTextBeforeCursor returns text from fake buffer`() {
        fakeIc.textBuffer.append("hello world")
        fakeIc.selectionStart = 5
        assertEquals("hello", outputBridge.safeGetTextBeforeCursor(5))
    }

    @Test
    fun `safeGetTextAfterCursor returns text from fake buffer`() {
        fakeIc.textBuffer.append("hello world")
        fakeIc.selectionStart = 5
        assertEquals(" world", outputBridge.safeGetTextAfterCursor(6))
    }

    @Test
    fun `safeGetCursorPosition returns position from fake getExtractedText`() {
        fakeIc.selectionStart = 7
        assertEquals(7, outputBridge.safeGetCursorPosition())
    }

    @Test
    fun `commitText delegates to ic and updates fake buffer`() {
        outputBridge.commitText("hello")
        assertEquals(listOf("hello"), fakeIc.committedTexts)
        assertEquals("hello", fakeIc.textBuffer.toString())
    }

    @Test
    fun `sendBackspace removes last character when text before cursor exists`() {
        fakeIc.textBuffer.append("hello")
        fakeIc.selectionStart = 5
        whenever(mockState.isTerminalField).thenReturn(false)
        outputBridge.sendBackspace()
        assertEquals("hell", fakeIc.textBuffer.toString())
        assertEquals(4, fakeIc.selectionStart)
    }
}
