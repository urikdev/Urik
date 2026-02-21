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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
                    activeLanguages = listOf("en"),
                    primaryLanguage = "en",
                ),
            )
        whenever(settingsRepository.settings).thenReturn(settingsFlow)

        languageManager = LanguageManager(settingsRepository, testDispatcher)
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
            assertEquals("en", languageManager.currentLanguage.value)
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
                    activeLanguages = listOf("sv"),
                    primaryLanguage = "sv",
                )

            assertEquals("sv", languageManager.currentLanguage.value)
        }

    @Test
    fun `switching language updates current language`() =
        runTest {
            languageManager.initialize()

            assertEquals("en", languageManager.currentLanguage.value)

            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("sv"),
                    primaryLanguage = "sv",
                )

            assertEquals("sv", languageManager.currentLanguage.value)
        }

    @Test
    fun `cleanup is idempotent`() =
        runTest {
            languageManager.initialize()

            languageManager.cleanup()
            languageManager.cleanup()

            assertNotNull(languageManager.currentLanguage.value)
        }

    @Test
    fun `switchLayoutLanguage succeeds with valid language`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "es"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                )
            languageManager.initialize()

            val result = languageManager.switchLayoutLanguage("es")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `switchLayoutLanguage fails with invalid language`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "es"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                )
            languageManager.initialize()

            val result = languageManager.switchLayoutLanguage("de")

            assertTrue(result.isFailure)
        }

    @Test
    fun `getNextLayoutLanguage cycles between two languages`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "es"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                )
            languageManager.initialize()

            val next1 = languageManager.getNextLayoutLanguage()
            assertEquals("es", next1)
        }

    @Test
    fun `getNextLayoutLanguage cycles through three languages`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "es", "de"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                )
            languageManager.initialize()

            val next1 = languageManager.getNextLayoutLanguage()
            assertEquals("es", next1)
        }

    @Test
    fun `getNextLayoutLanguage returns same for single language`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                )
            languageManager.initialize()

            val next = languageManager.getNextLayoutLanguage()
            assertEquals("en", next)
        }

    @Test
    fun `effectiveDictionaryLanguages returns all active when merged`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "es", "fr"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                    mergedDictionaries = true,
                )
            languageManager.initialize()

            assertEquals(listOf("en", "es", "fr"), languageManager.effectiveDictionaryLanguages.value)
        }

    @Test
    fun `effectiveDictionaryLanguages returns only layout language when not merged`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "es", "fr"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "es",
                    mergedDictionaries = false,
                )
            languageManager.initialize()

            assertEquals(listOf("es"), languageManager.effectiveDictionaryLanguages.value)
        }

    @Test
    fun `effectiveDictionaryLanguages updates when mergedDictionaries toggled`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "es"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                    mergedDictionaries = true,
                )
            languageManager.initialize()

            assertEquals(listOf("en", "es"), languageManager.effectiveDictionaryLanguages.value)

            settingsFlow.value =
                settingsFlow.value.copy(mergedDictionaries = false)

            assertEquals(listOf("en"), languageManager.effectiveDictionaryLanguages.value)
        }

    @Test
    fun `effectiveDictionaryLanguages updates when layout language switches while not merged`() =
        runTest {
            settingsFlow.value =
                KeyboardSettings(
                    activeLanguages = listOf("en", "fr"),
                    primaryLanguage = "en",
                    primaryLayoutLanguage = "en",
                    mergedDictionaries = false,
                )
            languageManager.initialize()

            assertEquals(listOf("en"), languageManager.effectiveDictionaryLanguages.value)

            settingsFlow.value =
                settingsFlow.value.copy(primaryLayoutLanguage = "fr")

            assertEquals(listOf("fr"), languageManager.effectiveDictionaryLanguages.value)
        }

    @Test
    fun `mergedDictionaries defaults to true`() =
        runTest {
            languageManager.initialize()

            assertTrue(languageManager.mergedDictionaries.value)
        }
}
