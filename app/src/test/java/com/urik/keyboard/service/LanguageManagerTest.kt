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
                    activeLanguages = setOf("en"),
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
                    activeLanguages = setOf("sv"),
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
                    activeLanguages = setOf("sv"),
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
}
