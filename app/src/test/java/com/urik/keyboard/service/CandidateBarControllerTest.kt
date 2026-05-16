package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.ui.keyboard.components.SwipeKeyboardView
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class CandidateBarControllerTest {
    private lateinit var mockView: SwipeKeyboardView
    private lateinit var controller: CandidateBarController

    @Before
    fun setup() {
        mockView = mock()
        controller = CandidateBarController(viewProvider = { mockView })
    }

    @Test
    fun `updateSuggestions delegates to view`() {
        val suggestions = listOf("hello", "world")
        controller.updateSuggestions(suggestions)
        verify(mockView).updateSuggestions(suggestions)
    }

    @Test
    fun `clearSuggestions delegates to view`() {
        controller.clearSuggestions()
        verify(mockView).clearSuggestions()
    }

    @Test
    fun `forceClearAllSuggestions delegates to view`() {
        controller.forceClearAllSuggestions()
        verify(mockView).forceClearAllSuggestions()
    }

    @Test
    fun `hideEmojiPicker delegates to view`() {
        controller.hideEmojiPicker()
        verify(mockView).hideEmojiPicker()
    }

    @Test
    fun `handleSearchInput delegates to view and returns true when view returns true`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        org.mockito.kotlin.whenever(mockView.handleSearchInput(key)).thenReturn(true)
        val result = controller.handleSearchInput(key)
        assert(result) { "Expected true when view returns true" }
        verify(mockView).handleSearchInput(key)
    }

    @Test
    fun `clearAutofillIfShowing delegates to view and returns view result`() {
        org.mockito.kotlin.whenever(mockView.clearAutofillIfShowing()).thenReturn(true)
        val result = controller.clearAutofillIfShowing()
        assert(result) { "Expected true when view returns true" }
        verify(mockView).clearAutofillIfShowing()
    }

    @Test
    fun `all methods are no-ops when view is null`() {
        val nullController = CandidateBarController(viewProvider = { null })
        nullController.clearSuggestions()
        nullController.updateSuggestions(listOf("x"))
        nullController.forceClearAllSuggestions()
        nullController.hideEmojiPicker()
        val noopResult = nullController.handleSearchInput(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        assert(!noopResult) { "handleSearchInput should return false when view is null" }
        verify(mockView, never()).clearSuggestions()
        verify(mockView, never()).updateSuggestions(org.mockito.kotlin.any())
    }

    @Test
    fun `updateInlineAutofillSuggestions delegates to view`() {
        val views = listOf<android.view.View>()
        controller.updateInlineAutofillSuggestions(views, animate = true)
        verify(mockView).updateInlineAutofillSuggestions(views, true)
    }
}
