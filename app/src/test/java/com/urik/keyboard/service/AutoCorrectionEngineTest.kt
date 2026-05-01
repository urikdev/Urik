package com.urik.keyboard.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AutoCorrectionEngineTest {
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var engine: AutoCorrectionEngine

    @Before
    fun setup() {
        mockTextInputProcessor = mock()
        engine = AutoCorrectionEngine(mockTextInputProcessor)
    }

    @Test
    fun `decide returns None when spellCheckEnabled is false`() = runTest {
        val result = engine.decide(
            buffer = "teh",
            spellCheckEnabled = false,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.None)
    }

    @Test
    fun `decide returns None when buffer is shorter than minimum length`() = runTest {
        val result = engine.decide(
            buffer = "a",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.None)
    }

    @Test
    fun `decide returns None when URL or email context detected`() = runTest {
        val result = engine.decide(
            buffer = "test",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "user",
            nextChar = "@"
        )
        assert(result is AutocorrectDecision.None)
    }

    @Test
    fun `decide returns None when word is valid and no contraction bypass`() = runTest {
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(false)

        val result = engine.decide(
            buffer = "hello",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.None)
    }

    @Test
    fun `decide returns ContractionBypass when valid word has dominant contraction form`() = runTest {
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(true)

        val result = engine.decide(
            buffer = "dont",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.ContractionBypass)
    }

    @Test
    fun `decide returns Pause when invalid word and pauseOnMisspelledWord is true`() = runTest {
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(emptyList())

        val result = engine.decide(
            buffer = "teh",
            spellCheckEnabled = true,
            autocorrectionEnabled = false,
            pauseOnMisspelledWord = true,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.Pause)
    }

    @Test
    fun `decide returns Correct when invalid word and safe autocorrect candidate exists`() = runTest {
        val suggestion = SpellingSuggestion("the", 1.0, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))

        val result = engine.decide(
            buffer = "teh",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.Correct) { "Expected Correct but got $result" }
        assertEquals("the", (result as AutocorrectDecision.Correct).suggestion)
    }

    @Test
    fun `decide returns Suggestions when invalid word and autocorrect disabled`() = runTest {
        val suggestion = SpellingSuggestion("hello", 1.0, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))

        val result = engine.decide(
            buffer = "helo",
            spellCheckEnabled = true,
            autocorrectionEnabled = false,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.Suggestions)
    }

    @Test
    fun `decide returns None when autocorrect top suggestion is not safe (contains digit)`() = runTest {
        val unsafeSuggestion = SpellingSuggestion("h3llo", 1.0, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(unsafeSuggestion))

        val result = engine.decide(
            buffer = "helo",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result !is AutocorrectDecision.Correct) { "Unsafe suggestion should not become Correct" }
    }
}
