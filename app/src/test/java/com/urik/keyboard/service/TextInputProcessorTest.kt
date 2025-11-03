@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import com.ibm.icu.lang.UScript
import com.ibm.icu.util.ULocale
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests [TextInputProcessor] character processing, spell checking, suggestions, and caching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextInputProcessorTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var languageManager: LanguageManager
    private lateinit var spellCheckManager: SpellCheckManager
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var settingsFlow: MutableStateFlow<KeyboardSettings>

    private lateinit var processor: TextInputProcessor

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        languageManager = mock()
        spellCheckManager = mock()
        settingsRepository = mock()

        settingsFlow = MutableStateFlow(KeyboardSettings())
        whenever(settingsRepository.settings).thenReturn(settingsFlow)

        processor =
            TextInputProcessor(
                spellCheckManager = spellCheckManager,
                settingsRepository = settingsRepository,
            )

        testDispatcher.scheduler.advanceUntilIdle()

        processor.updateScriptContext(ULocale.ENGLISH, UScript.LATIN)
    }

    @After
    fun teardown() {
        processor.cleanup()
        Dispatchers.resetMain()
    }

    @Test
    fun `processCharacterInput with valid letter returns success`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("hello")).thenReturn(true)
            whenever(spellCheckManager.generateSuggestions("hello", maxSuggestions = 3)).thenReturn(listOf("hello", "hells"))

            val result = processor.processCharacterInput("o", "hello", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertEquals("hello", success.wordState.buffer)
            assertEquals("hello", success.wordState.normalizedBuffer)
            assertTrue(success.wordState.isValid)
            assertFalse(success.wordState.isFromSwipe)
        }

    @Test
    fun `processCharacterInput with invalid word generates suggestions`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("helllo")).thenReturn(false)
            whenever(spellCheckManager.generateSuggestions("helllo", maxSuggestions = 3))
                .thenReturn(listOf("hello", "hell", "hallo"))

            val result = processor.processCharacterInput("o", "helllo", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertFalse(success.wordState.isValid)
            assertEquals(3, success.wordState.suggestions.size)
            assertEquals("hello", success.wordState.suggestions[0])
            assertTrue(success.shouldHighlight)
        }

    @Test
    fun `processCharacterInput with empty char returns error`() =
        runTest {
            val result = processor.processCharacterInput("", "word", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Error)
        }

    @Test
    fun `processCharacterInput with numbers returns error`() =
        runTest {
            val result = processor.processCharacterInput("5", "word", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Error)
        }

    @Test
    fun `processWordInput with swipe generates suggestions even for valid words`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("hello")).thenReturn(true)
            whenever(spellCheckManager.generateSuggestions("hello", maxSuggestions = 3))
                .thenReturn(listOf("hello", "hallo", "hullo"))

            val result = processor.processWordInput("hello", InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertTrue(success.wordState.isFromSwipe)
            assertEquals(3, success.wordState.suggestions.size)
            assertTrue(success.wordState.isValid)
        }

    @Test
    fun `processWordInput with very long word returns error`() =
        runTest {
            val longWord = "a".repeat(100)

            val result = processor.processWordInput(longWord, InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Error)
        }

    @Test
    fun `processWordInput with blank word returns error`() =
        runTest {
            val result = processor.processWordInput("   ", InputMethod.SWIPED)

            assertTrue(result is ProcessingResult.Error)
        }

    @Test
    fun `second call with same word uses cached processing`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("test")).thenReturn(true)
            whenever(spellCheckManager.generateSuggestions("test", maxSuggestions = 3)).thenReturn(emptyList())

            processor.processCharacterInput("t", "test", InputMethod.TYPED)

            processor.processCharacterInput("t", "test", InputMethod.TYPED)

            verify(spellCheckManager, times(1)).isWordInDictionary("test")
            verify(spellCheckManager, times(1)).generateSuggestions("test", maxSuggestions = 3)
        }

    @Test
    fun `invalidateWord clears both processing and suggestion caches`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("test")).thenReturn(true)
            processor.processCharacterInput("t", "test", InputMethod.TYPED)

            processor.invalidateWord("test")

            processor.processCharacterInput("t", "test", InputMethod.TYPED)

            verify(spellCheckManager, times(2)).isWordInDictionary("test")
        }

    @Test
    fun `clearCaches removes all cached data`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary(any())).thenReturn(true)
            processor.processCharacterInput("o", "hello", InputMethod.TYPED)
            processor.processCharacterInput("d", "world", InputMethod.TYPED)

            processor.clearCaches()

            processor.processCharacterInput("o", "hello", InputMethod.TYPED)
            processor.processCharacterInput("d", "world", InputMethod.TYPED)

            verify(spellCheckManager, times(2)).isWordInDictionary("hello")
            verify(spellCheckManager, times(2)).isWordInDictionary("world")
        }

    @Test
    fun `disabling spell check prevents spell checking`() =
        runTest {
            val settingsWithSpellCheckOff = KeyboardSettings(spellCheckEnabled = false)
            settingsFlow.emit(settingsWithSpellCheckOff)
            testDispatcher.scheduler.advanceUntilIdle()

            val result = processor.processCharacterInput("o", "hello", InputMethod.TYPED)

            verify(spellCheckManager, never()).isWordInDictionary(any())
            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertFalse(success.wordState.requiresSpellCheck)
        }

    @Test
    fun `disabling suggestions prevents suggestion generation`() =
        runTest {
            val settingsWithSuggestionsOff = KeyboardSettings(showSuggestions = false)
            settingsFlow.emit(settingsWithSuggestionsOff)
            testDispatcher.scheduler.advanceUntilIdle()

            whenever(spellCheckManager.isWordInDictionary("helllo")).thenReturn(false)
            val result = processor.processCharacterInput("o", "helllo", InputMethod.TYPED)

            verify(spellCheckManager, never()).generateSuggestions(any(), any())
            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertTrue(success.wordState.suggestions.isEmpty())
        }

    @Test
    fun `changing suggestion count limits returned suggestions`() =
        runTest {
            val settingsWithLimitedSuggestions = KeyboardSettings(suggestionCount = 2)
            settingsFlow.emit(settingsWithLimitedSuggestions)
            testDispatcher.scheduler.advanceUntilIdle()

            whenever(spellCheckManager.isWordInDictionary("helllo")).thenReturn(false)
            whenever(spellCheckManager.generateSuggestions("helllo", maxSuggestions = 2))
                .thenReturn(listOf("hello", "hell"))

            val result = processor.processCharacterInput("o", "helllo", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Success)
            val success = result as ProcessingResult.Success
            assertEquals(2, success.wordState.suggestions.size)
        }

    @Test
    fun `settings change clears cache when spell check settings change`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("test")).thenReturn(true)
            processor.processCharacterInput("t", "test", InputMethod.TYPED)

            val newSettings = KeyboardSettings(spellCheckEnabled = false)
            settingsFlow.emit(newSettings)
            testDispatcher.scheduler.advanceUntilIdle()

            processor.processCharacterInput("t", "test", InputMethod.TYPED)

            verify(spellCheckManager, times(1)).isWordInDictionary("test")
        }

    @Test
    fun `getCurrentSettings returns current settings`() =
        runTest {
            val customSettings =
                KeyboardSettings(
                    spellCheckEnabled = false,
                    showSuggestions = false,
                )
            settingsFlow.emit(customSettings)
            testDispatcher.scheduler.advanceUntilIdle()

            val currentSettings = processor.getCurrentSettings()

            assertFalse(currentSettings.spellCheckEnabled)
            assertFalse(currentSettings.showSuggestions)
        }

    @Test
    fun `updateScriptContext clears caches`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("hello")).thenReturn(true)
            processor.processCharacterInput("o", "hello", InputMethod.TYPED)

            processor.updateScriptContext(ULocale.forLanguageTag("ar"), UScript.ARABIC)

            processor.processCharacterInput("o", "hello", InputMethod.TYPED)

            verify(spellCheckManager, times(2)).isWordInDictionary(any())
        }

    @Test
    fun `getSuggestions returns suggestions when enabled`() =
        runTest {
            whenever(spellCheckManager.generateSuggestions("hel", maxSuggestions = 3))
                .thenReturn(listOf("hello", "help", "hell"))

            val suggestions = processor.getSuggestions("hel")

            assertEquals(3, suggestions.size)
            assertEquals("hello", suggestions[0])
        }

    @Test
    fun `getSuggestions returns empty when disabled`() =
        runTest {
            settingsFlow.emit(KeyboardSettings(showSuggestions = false))
            testDispatcher.scheduler.advanceUntilIdle()

            val suggestions = processor.getSuggestions("hel")

            assertTrue(suggestions.isEmpty())
            verify(spellCheckManager, never()).generateSuggestions(any(), any())
        }

    @Test
    fun `getSuggestions respects min query length`() =
        runTest {
            whenever(spellCheckManager.generateSuggestions("h", maxSuggestions = 3))
                .thenReturn(listOf("he", "hi", "ha"))
            val suggestions = processor.getSuggestions("h")

            assertEquals(3, suggestions.size)
        }

    @Test
    fun `getSuggestions caches results`() =
        runTest {
            whenever(spellCheckManager.generateSuggestions("hel", maxSuggestions = 3))
                .thenReturn(listOf("hello", "help", "hell"))
            processor.getSuggestions("hel")

            processor.getSuggestions("hel")

            verify(spellCheckManager, times(1)).generateSuggestions("hel", maxSuggestions = 3)
        }

    @Test
    fun `validateWord returns true for dictionary word`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("hello")).thenReturn(true)

            val isValid = processor.validateWord("hello")

            assertTrue(isValid)
        }

    @Test
    fun `validateWord returns false for non-dictionary word`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary("helllo")).thenReturn(false)

            val isValid = processor.validateWord("helllo")

            assertFalse(isValid)
        }

    @Test
    fun `validateWord returns true when spell check disabled`() =
        runTest {
            settingsFlow.emit(KeyboardSettings(spellCheckEnabled = false))
            testDispatcher.scheduler.advanceUntilIdle()

            val isValid = processor.validateWord("anyword")

            assertTrue(isValid)
            verify(spellCheckManager, never()).isWordInDictionary(any())
        }

    @Test
    fun `processing handles spell check manager exception gracefully`() =
        runTest {
            whenever(spellCheckManager.isWordInDictionary(any()))
                .thenThrow(RuntimeException("Dictionary error"))

            val result = processor.processCharacterInput("o", "hello", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Error)
        }

    @Test
    fun `getSuggestions handles exception gracefully`() =
        runTest {
            whenever(spellCheckManager.generateSuggestions(any(), any()))
                .thenThrow(RuntimeException("Suggestion error"))

            val suggestions = processor.getSuggestions("hello")

            assertTrue(suggestions.isEmpty())
        }

    @Test
    fun `processing empty word returns error`() =
        runTest {
            val result = processor.processWordInput("", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Error)
        }
}
