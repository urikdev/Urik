package com.urik.keyboard.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AutoCorrectionEngineTest {
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var engine: AutoCorrectionEngine

    @Before
    fun setup() = runBlocking {
        mockTextInputProcessor = mock()
        whenever(mockTextInputProcessor.getDictFrequency(any())).thenReturn(0L)
        whenever(mockTextInputProcessor.getUserFrequency(any())).thenReturn(0)
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
        val suggestion = SpellingSuggestion("hello", 1.0, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))
        whenever(mockTextInputProcessor.getDictFrequency("hello")).thenReturn(50_000L)

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
    fun `decide returns Correct when valid word is overwhelmed by top suggestion frequency`() = runTest {
        val suggestion = SpellingSuggestion("so", 0.9, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))
        whenever(mockTextInputProcessor.getDictFrequency("ao")).thenReturn(398L)
        whenever(mockTextInputProcessor.getDictFrequency("so")).thenReturn(3_434_152L)
        whenever(mockTextInputProcessor.getUserFrequency("ao")).thenReturn(0)

        val result = engine.decide(
            buffer = "ao",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.Correct) { "Expected Correct but got $result" }
        assertEquals("so", (result as AutocorrectDecision.Correct).suggestion)
    }

    @Test
    fun `decide returns None when valid word has top suggestion but frequency gap is small`() = runTest {
        val suggestion = SpellingSuggestion("do", 0.8, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))
        whenever(mockTextInputProcessor.getDictFrequency("go")).thenReturn(500_000L)
        whenever(mockTextInputProcessor.getDictFrequency("do")).thenReturn(1_000_000L)
        whenever(mockTextInputProcessor.getUserFrequency("go")).thenReturn(0)

        val result = engine.decide(
            buffer = "go",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.None) { "Small frequency gap should not override valid word" }
    }

    @Test
    fun `decide returns ContractionBypass when valid word has dominant contraction form`() = runTest {
        val suggestion = SpellingSuggestion("don't", 1.0, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(true)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))

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
        assertEquals(listOf(suggestion), (result as AutocorrectDecision.ContractionBypass).suggestions)
        verify(mockTextInputProcessor, times(1)).getSuggestions(any())
    }

    @Test
    fun `decide returns Pause when invalid word and pauseOnMisspelledWord is true`() = runTest {
        val suggestion = SpellingSuggestion("the", 1.0, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))

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
        assertEquals(listOf(suggestion), (result as AutocorrectDecision.Pause).suggestions)
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
    fun `decide returns None when user frequency boost raises effective freq above override ratio`() = runTest {
        val suggestion = SpellingSuggestion("mao", 0.9, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))
        whenever(mockTextInputProcessor.getDictFrequency("lmao")).thenReturn(22L)
        whenever(mockTextInputProcessor.getDictFrequency("mao")).thenReturn(2428L)
        whenever(mockTextInputProcessor.getUserFrequency("lmao")).thenReturn(3)

        val result = engine.decide(
            buffer = "lmao",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.None) { "User freq boost should prevent autocorrect: $result" }
    }

    @Test
    fun `decide returns Correct when user frequency boost is insufficient`() = runTest {
        val suggestion = SpellingSuggestion("mao", 0.9, 0)
        whenever(mockTextInputProcessor.validateWord(any())).thenReturn(true)
        whenever(mockTextInputProcessor.hasDominantContractionForm(any())).thenReturn(false)
        whenever(mockTextInputProcessor.getSuggestions(any())).thenReturn(listOf(suggestion))
        whenever(mockTextInputProcessor.getDictFrequency("lmao")).thenReturn(22L)
        whenever(mockTextInputProcessor.getDictFrequency("mao")).thenReturn(2428L)
        whenever(mockTextInputProcessor.getUserFrequency("lmao")).thenReturn(0)

        val result = engine.decide(
            buffer = "lmao",
            spellCheckEnabled = true,
            autocorrectionEnabled = true,
            pauseOnMisspelledWord = false,
            lastAutocorrection = null,
            textBeforeCursor = "",
            nextChar = " "
        )
        assert(result is AutocorrectDecision.Correct) { "Without user freq boost, should autocorrect: $result" }
        assertEquals("mao", (result as AutocorrectDecision.Correct).suggestion)
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
