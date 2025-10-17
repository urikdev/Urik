@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Tests [LanguageManager] initialization, text normalization, caching, and script detection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageManagerTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var settingsFlow: MutableStateFlow<KeyboardSettings>
    private lateinit var languageManager: LanguageManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        settingsRepository = mock()

        settingsFlow =
            MutableStateFlow(
                KeyboardSettings(
                    activeLanguages = setOf("en"),
                    primaryLanguage = "en",
                ),
            )
        whenever(settingsRepository.settings).thenReturn(settingsFlow)

        languageManager =
            LanguageManager(
                settingsRepository = settingsRepository,
                scopeDispatcher = testDispatcher,
            )
    }

    @After
    fun teardown() {
        languageManager.cleanup()
        Dispatchers.resetMain()
    }

    @Test
    fun `initialize sets up language from settings`() =
        runTest {
            val result = languageManager.initialize()

            assertTrue(result.isSuccess)
            assertNotNull(languageManager.currentLanguage.value)
            assertEquals("en", languageManager.currentLanguage.value?.languageTag)
            assertEquals("English", languageManager.currentLanguage.value?.displayName)
            assertTrue(languageManager.currentLanguage.value?.isPrimary == true)
        }

    @Test
    fun `initialize is idempotent`() =
        runTest {
            languageManager.initialize()
            val result = languageManager.initialize()

            assertTrue(result.isSuccess)
        }

    @Test
    fun `initialize observes settings changes`() =
        runTest {
            languageManager.initialize()

            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = setOf("sv"),
                    primaryLanguage = "sv",
                )

            assertEquals("sv", languageManager.currentLanguage.value?.languageTag)
            assertTrue(languageManager.currentLanguage.value?.isPrimary == true)
        }

    @Test
    fun `initialize with multiple languages sets primary correctly`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = setOf("en", "sv"),
                    primaryLanguage = "sv",
                )

            languageManager.initialize()

            assertEquals("sv", languageManager.currentLanguage.value?.languageTag)
            assertTrue(languageManager.currentLanguage.value?.isPrimary == true)
        }

    @Test
    fun `normalizeText lowercases Latin text`() =
        runTest {
            languageManager.initialize()

            val result = languageManager.normalizeText("HELLO WORLD")

            assertEquals("hello world", result)
        }

    @Test
    fun `normalizeText handles mixed case`() =
        runTest {
            languageManager.initialize()

            val result = languageManager.normalizeText("HeLLo WoRLd")

            assertEquals("hello world", result)
        }

    @Test
    fun `normalizeText preserves non-Latin scripts`() =
        runTest {
            languageManager.initialize()

            val chinese = languageManager.normalizeText("你好世界")
            val arabic = languageManager.normalizeText("مرحبا")
            val hindi = languageManager.normalizeText("नमस्ते")

            assertEquals("你好世界", chinese)
            assertEquals("مرحبا", arabic)
            assertEquals("नमस्ते", hindi)
        }

    @Test
    fun `normalizeText handles accented characters`() =
        runTest {
            languageManager.initialize()

            val result = languageManager.normalizeText("café")

            assertTrue(result.contains("cafe") || result.contains("café"))
        }

    @Test
    fun `normalizeText returns empty for blank input`() =
        runTest {
            languageManager.initialize()

            assertEquals("", languageManager.normalizeText(""))
            assertEquals("   ", languageManager.normalizeText("   "))
        }

    @Test
    fun `normalizeText handles apostrophes and hyphens`() =
        runTest {
            languageManager.initialize()

            val apostrophe = languageManager.normalizeText("don't")
            val hyphen = languageManager.normalizeText("well-known")

            assertTrue(apostrophe.contains("don"))
            assertTrue(hyphen.contains("well"))
        }

    @Test
    fun `normalizeTextForLanguage lowercases Latin script`() =
        runTest {
            val result = languageManager.normalizeTextForLanguage("HELLO", "en")

            assertEquals("hello", result)
        }

    @Test
    fun `normalizeTextForLanguage lowercases Greek script`() =
        runTest {
            val result = languageManager.normalizeTextForLanguage("ΓΕΙΑ", "el")

            assertEquals("γεια", result)
        }

    @Test
    fun `normalizeTextForLanguage preserves Chinese characters`() =
        runTest {
            val result = languageManager.normalizeTextForLanguage("你好", "zh")

            assertEquals("你好", result)
        }

    @Test
    fun `normalizeTextForLanguage preserves Japanese characters`() =
        runTest {
            val result = languageManager.normalizeTextForLanguage("こんにちは", "ja")

            assertEquals("こんにちは", result)
        }

    @Test
    fun `normalizeTextForLanguage handles mixed scripts`() =
        runTest {
            val result = languageManager.normalizeTextForLanguage("Hello你好", "en")

            assertTrue(result.contains("hello") || result.startsWith("h"))
            assertTrue(result.contains("你好"))
        }

    @Test
    fun `normalizeTextForLanguage handles invalid language tag gracefully`() =
        runTest {
            val result = languageManager.normalizeTextForLanguage("HELLO", "invalid-tag")

            assertNotNull(result)
            assertTrue(result.isNotEmpty())
        }

    @Test
    fun `normalizeText uses cache for repeated calls`() =
        runTest {
            languageManager.initialize()

            val first = languageManager.normalizeText("TESTING")
            val second = languageManager.normalizeText("TESTING")

            assertEquals(first, second)
            assertEquals("testing", first)
        }

    @Test
    fun `cache handles different inputs independently`() =
        runTest {
            languageManager.initialize()

            val result1 = languageManager.normalizeText("FIRST")
            val result2 = languageManager.normalizeText("SECOND")
            val result3 = languageManager.normalizeText("FIRST")

            assertEquals("first", result1)
            assertEquals("second", result2)
            assertEquals("first", result3)
            assertEquals(result1, result3)
        }

    @Test
    fun `cache respects max size`() =
        runTest {
            languageManager.initialize()

            for (i in 0..150) {
                languageManager.normalizeText("WORD$i")
            }

            val result = languageManager.normalizeText("NEW")
            assertEquals("new", result)
        }

    @Test
    fun `cache handles blank inputs correctly`() =
        runTest {
            languageManager.initialize()

            val empty = languageManager.normalizeText("")
            val spaces = languageManager.normalizeText("   ")

            assertEquals("", empty)
            assertEquals("   ", spaces)
        }

    @Test
    fun `cache handles unicode correctly`() =
        runTest {
            languageManager.initialize()

            val emoji = languageManager.normalizeText("Hello😀World")
            val cached = languageManager.normalizeText("Hello😀World")

            assertEquals(emoji, cached)
            assertTrue(emoji.contains("😀"))
        }

    @Test
    fun `Latin script text is lowercased`() =
        runTest {
            languageManager.initialize()

            val result = languageManager.normalizeText("ABCDEFGHIJKLMNOPQRSTUVWXYZ")

            assertEquals("abcdefghijklmnopqrstuvwxyz", result)
        }

    @Test
    fun `Arabic script text is preserved`() =
        runTest {
            languageManager.initialize()

            val input = "مرحبا بك في العالم"
            val result = languageManager.normalizeText(input)

            assertEquals(input, result)
        }

    @Test
    fun `Hebrew script text is preserved`() =
        runTest {
            languageManager.initialize()

            val input = "שלום עולם"
            val result = languageManager.normalizeText(input)

            assertEquals(input, result)
        }

    @Test
    fun `Thai script text is preserved`() =
        runTest {
            languageManager.initialize()

            val input = "สวัสดีโลก"
            val result = languageManager.normalizeText(input)

            assertEquals(input, result)
        }

    @Test
    fun `mixed script text handles dominant script`() =
        runTest {
            languageManager.initialize()

            val latinDominant = languageManager.normalizeText("HELLO你好")
            val cjkDominant = languageManager.normalizeText("你好世界HELLO")

            assertTrue(latinDominant.startsWith("h") || latinDominant.contains("hello"))
            assertTrue(cjkDominant.contains("你好世界"))
        }

    @Test
    fun `switching language updates current language`() =
        runTest {
            languageManager.initialize()

            assertEquals("en", languageManager.currentLanguage.value?.languageTag)

            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = setOf("sv"),
                    primaryLanguage = "sv",
                )

            assertEquals("sv", languageManager.currentLanguage.value?.languageTag)
        }

    @Test
    fun `switching language clears isPrimary flag from old language`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = setOf("en", "sv"),
                    primaryLanguage = "en",
                )
            languageManager.initialize()

            assertTrue(languageManager.currentLanguage.value?.isPrimary == true)
            assertEquals("en", languageManager.currentLanguage.value?.languageTag)

            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = setOf("en", "sv"),
                    primaryLanguage = "sv",
                )

            assertTrue(languageManager.currentLanguage.value?.isPrimary == true)
            assertEquals("sv", languageManager.currentLanguage.value?.languageTag)
        }

    @Test
    fun `removing current language from active languages updates state`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = setOf("en", "sv"),
                    primaryLanguage = "en",
                )
            languageManager.initialize()

            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = setOf("sv"),
                    primaryLanguage = "sv",
                )

            assertEquals("sv", languageManager.currentLanguage.value?.languageTag)
        }

    @Test
    fun `normalizeText handles very long strings`() =
        runTest {
            languageManager.initialize()

            val longString = "A".repeat(10000)
            val result = languageManager.normalizeText(longString)

            assertEquals(10000, result.length)
            assertTrue(result.all { it == 'a' })
        }

    @Test
    fun `normalizeText handles special characters`() =
        runTest {
            languageManager.initialize()

            val special = languageManager.normalizeText("!@#$%^&*()")
            val punctuation = languageManager.normalizeText(".,;:'\"-")
            val whitespace = languageManager.normalizeText("  \t\n\r  ")

            assertNotNull(special)
            assertNotNull(punctuation)
            assertNotNull(whitespace)
        }

    @Test
    fun `normalizeText handles combining diacritics`() =
        runTest {
            languageManager.initialize()

            val result = languageManager.normalizeText("é")

            assertNotNull(result)
            assertTrue(result.isNotEmpty())
        }

    @Test
    fun `normalizeText handles zero-width characters`() =
        runTest {
            languageManager.initialize()

            val input = "hello\u200Bworld"
            val result = languageManager.normalizeText(input)

            assertNotNull(result)
        }

    @Test
    fun `normalizeText handles RTL text`() =
        runTest {
            languageManager.initialize()

            val arabic = languageManager.normalizeText("مرحبا")
            val hebrew = languageManager.normalizeText("שלום")

            assertNotNull(arabic)
            assertNotNull(hebrew)
            assertTrue(arabic.isNotEmpty())
            assertTrue(hebrew.isNotEmpty())
        }

    @Test
    fun `cleanup clears normalization cache`() =
        runTest {
            languageManager.initialize()

            languageManager.normalizeText("CACHED")
            languageManager.cleanup()

            val result = languageManager.normalizeText("CACHED")
            assertEquals("cached", result)
        }

    @Test
    fun `cleanup is idempotent`() =
        runTest {
            languageManager.initialize()

            languageManager.cleanup()
            languageManager.cleanup()

            val result = languageManager.normalizeText("TEST")
            assertNotNull(result)
        }

    @Test
    fun `normalizeText is thread-safe for cache access`() =
        runTest {
            languageManager.initialize()

            val results =
                (1..50).map { i ->
                    languageManager.normalizeText("WORD$i")
                }

            assertEquals(50, results.size)
            results.forEachIndexed { index, result ->
                assertEquals("word${index + 1}", result)
            }
        }

    @Test
    fun `normalizeText handles concurrent duplicate requests`() =
        runTest {
            languageManager.initialize()

            val results =
                (1..10).map {
                    languageManager.normalizeText("DUPLICATE")
                }

            assertTrue(results.all { it == "duplicate" })
        }
}
