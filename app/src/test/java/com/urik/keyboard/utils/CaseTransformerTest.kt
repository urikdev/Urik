package com.urik.keyboard.utils

import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.SpellingSuggestion
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CaseTransformerTest {
    private lateinit var caseTransformer: CaseTransformer

    @Before
    fun setup() {
        caseTransformer = CaseTransformer()
    }

    @Test
    fun `caps lock forces all uppercase`() {
        val suggestion =
            SpellingSuggestion(
                word = "iPhone",
                confidence = 0.95,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = true,
                isShiftPressed = false,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("IPHONE", result)
    }

    @Test
    fun `manual shift capitalizes first letter`() {
        val suggestion =
            SpellingSuggestion(
                word = "iPhone",
                confidence = 0.95,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("IPhone", result)
    }

    @Test
    fun `auto shift with preserveCase true keeps natural case`() {
        val suggestion =
            SpellingSuggestion(
                word = "iPhone",
                confidence = 0.95,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("iPhone", result)
    }

    @Test
    fun `auto shift with preserveCase false capitalizes first letter`() {
        val suggestion =
            SpellingSuggestion(
                word = "the",
                confidence = 0.80,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("The", result)
    }

    @Test
    fun `no shift returns natural case`() {
        val suggestion =
            SpellingSuggestion(
                word = "iPhone",
                confidence = 0.95,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = false,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("iPhone", result)
    }

    @Test
    fun `capitalizes first grapheme cluster correctly for emoji`() {
        val suggestion =
            SpellingSuggestion(
                word = "hello👨‍👩‍👧",
                confidence = 0.90,
                ranking = 0,
                source = "learned",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("Hello👨‍👩‍👧", result)
    }

    @Test
    fun `handles empty string`() {
        val suggestion =
            SpellingSuggestion(
                word = "",
                confidence = 0.0,
                ranking = 0,
                source = "unknown",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("", result)
    }

    @Test
    fun `applies casing to multiple suggestions`() {
        val suggestions =
            listOf(
                SpellingSuggestion("iPhone", 0.95, 0, "learned", preserveCase = true),
                SpellingSuggestion("ipad", 0.90, 1, "learned", preserveCase = true),
                SpellingSuggestion("the", 0.80, 2, "symspell", preserveCase = false),
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val result = caseTransformer.applyCasingToSuggestions(suggestions, state)

        assertEquals(listOf("iPhone", "ipad", "The"), result)
    }

    @Test
    fun `priority caps lock over manual shift`() {
        val suggestion =
            SpellingSuggestion(
                word = "test",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = true,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("TEST", result)
    }

    @Test
    fun `priority manual shift over auto shift`() {
        val suggestion =
            SpellingSuggestion(
                word = "test",
                confidence = 0.90,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val state2 = state.copy(isAutoShift = false)
        val result = caseTransformer.applyCasing(suggestion, state2)

        assertEquals("Test", result)
    }

    @Test
    fun `ASCII fast path capitalizes correctly`() {
        val suggestion =
            SpellingSuggestion(
                word = "hello",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("Hello", result)
    }

    @Test
    fun `non-ASCII word uses ICU for capitalization`() {
        val suggestion =
            SpellingSuggestion(
                word = "über",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("Über", result)
    }

    @Test
    fun `swipe word from dictionary capitalizes at sentence start`() {
        val suggestion =
            SpellingSuggestion(
                word = "hello",
                confidence = 1.0,
                ranking = 0,
                source = "swipe",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("Hello", result)
    }

    @Test
    fun `swipe word from learned preserves case at sentence start`() {
        val suggestion =
            SpellingSuggestion(
                word = "iPhone",
                confidence = 1.0,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("iPhone", result)
    }

    @Test
    fun `lowercase learned word preserved mid-sentence`() {
        val suggestion =
            SpellingSuggestion(
                word = "myapi",
                confidence = 1.0,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = false,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("myapi", result)
    }

    @Test
    fun `lowercase learned word preserved even with auto-shift`() {
        val suggestion =
            SpellingSuggestion(
                word = "myapi",
                confidence = 1.0,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("myapi", result)
    }

    @Test
    fun `cyrillic word capitalizes correctly`() {
        val suggestion =
            SpellingSuggestion(
                word = "привет",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("Привет", result)
    }

    @Test
    fun `arabic word unchanged by shift`() {
        val suggestion =
            SpellingSuggestion(
                word = "مرحبا",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("مرحبا", result)
    }

    @Test
    fun `sentence start capitalizes dictionary suggestion without shift state`() {
        val suggestion =
            SpellingSuggestion(
                word = "hello",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = false,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state, isSentenceStart = true)

        assertEquals("Hello", result)
    }

    @Test
    fun `sentence start preserves learned word casing`() {
        val suggestion =
            SpellingSuggestion(
                word = "iPhone",
                confidence = 0.95,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = false,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state, isSentenceStart = true)

        assertEquals("iPhone", result)
    }

    @Test
    fun `sentence start with caps lock still uppercases`() {
        val suggestion =
            SpellingSuggestion(
                word = "hello",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = true,
                isShiftPressed = false,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state, isSentenceStart = true)

        assertEquals("HELLO", result)
    }

    @Test
    fun `mid-sentence no capitalization without shift`() {
        val suggestion =
            SpellingSuggestion(
                word = "hello",
                confidence = 0.90,
                ranking = 0,
                source = "symspell",
                preserveCase = false,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = false,
                isAutoShift = false,
            )

        val result = caseTransformer.applyCasing(suggestion, state, isSentenceStart = false)

        assertEquals("hello", result)
    }

    @Test
    fun `mixed case learned word like McLaren preserved`() {
        val suggestion =
            SpellingSuggestion(
                word = "McLaren",
                confidence = 1.0,
                ranking = 0,
                source = "learned",
                preserveCase = true,
            )
        val state =
            KeyboardState(
                isCapsLockOn = false,
                isShiftPressed = true,
                isAutoShift = true,
            )

        val result = caseTransformer.applyCasing(suggestion, state)

        assertEquals("McLaren", result)
    }
}
