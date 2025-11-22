package com.urik.keyboard.theme

import com.urik.keyboard.settings.KeyLabelSize
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.settings.SpaceBarSize
import com.urik.keyboard.settings.VibrationStrength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeManagerTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var themeManager: ThemeManager
    private lateinit var settingsFlow: MutableStateFlow<KeyboardSettings>
    private lateinit var closeable: AutoCloseable

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        closeable = MockitoAnnotations.openMocks(this)

        settingsFlow = MutableStateFlow(createDefaultSettings())
        whenever(settingsRepository.settings).thenReturn(settingsFlow)

        themeManager = ThemeManager(settingsRepository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        closeable.close()
    }

    @Test
    fun `currentTheme emits Default theme initially`() = runTest {
        val theme = themeManager.currentTheme.first()
        assertEquals(Default, theme)
    }

    @Test
    fun `currentTheme emits Light theme when settings change`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "light")

        val theme = themeManager.currentTheme.first()
        assertEquals(Light, theme)
    }

    @Test
    fun `currentTheme emits Abyss theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "abyss")

        val theme = themeManager.currentTheme.first()
        assertEquals(Abyss, theme)
    }

    @Test
    fun `currentTheme emits Crimson theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "crimson")

        val theme = themeManager.currentTheme.first()
        assertEquals(Crimson, theme)
    }

    @Test
    fun `currentTheme emits Forest theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "forest")

        val theme = themeManager.currentTheme.first()
        assertEquals(Forest, theme)
    }

    @Test
    fun `currentTheme emits Sunset theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "sunset")

        val theme = themeManager.currentTheme.first()
        assertEquals(Sunset, theme)
    }

    @Test
    fun `currentTheme emits Ocean theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "ocean")

        val theme = themeManager.currentTheme.first()
        assertEquals(Ocean, theme)
    }

    @Test
    fun `currentTheme emits Lavender theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "lavender")

        val theme = themeManager.currentTheme.first()
        assertEquals(Lavender, theme)
    }

    @Test
    fun `currentTheme emits Mocha theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "mocha")

        val theme = themeManager.currentTheme.first()
        assertEquals(Mocha, theme)
    }

    @Test
    fun `currentTheme emits Slate theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "slate")

        val theme = themeManager.currentTheme.first()
        assertEquals(Slate, theme)
    }

    @Test
    fun `currentTheme emits Peach theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "peach")

        val theme = themeManager.currentTheme.first()
        assertEquals(Peach, theme)
    }

    @Test
    fun `currentTheme emits Mint theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "mint")

        val theme = themeManager.currentTheme.first()
        assertEquals(Mint, theme)
    }

    @Test
    fun `currentTheme emits Neon theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "neon")

        val theme = themeManager.currentTheme.first()
        assertEquals(Neon, theme)
    }

    @Test
    fun `currentTheme emits Ember theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "ember")

        val theme = themeManager.currentTheme.first()
        assertEquals(Ember, theme)
    }

    @Test
    fun `currentTheme emits Steel theme`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "steel")

        val theme = themeManager.currentTheme.first()
        assertEquals(Steel, theme)
    }

    @Test
    fun `currentTheme falls back to Default for unknown theme ID`() = runTest {
        settingsFlow.value = createDefaultSettings().copy(keyboardTheme = "unknown_theme")

        val theme = themeManager.currentTheme.first()
        assertEquals(Default, theme)
    }

    @Test
    fun `setTheme updates repository with theme ID`() = runTest {
        themeManager.setTheme(Neon)

        verify(settingsRepository).updateKeyboardTheme("neon")
    }

    @Test
    fun `setTheme updates with Default theme ID`() = runTest {
        themeManager.setTheme(Default)

        verify(settingsRepository).updateKeyboardTheme("default")
    }

    @Test
    fun `setTheme updates with Light theme ID`() = runTest {
        themeManager.setTheme(Light)

        verify(settingsRepository).updateKeyboardTheme("light")
    }

    private fun createDefaultSettings() = KeyboardSettings(
        showSuggestions = true,
        suggestionCount = 3,
        learnNewWords = true,
        activeLanguages = setOf("en"),
        primaryLanguage = "en",
        hapticFeedback = true,
        vibrationStrength = VibrationStrength.MEDIUM,
        doubleSpacePeriod = true,
        longPressDuration = LongPressDuration.MEDIUM,
        showNumberRow = true,
        spaceBarSize = SpaceBarSize.STANDARD,
        keySize = KeySize.MEDIUM,
        keyLabelSize = KeyLabelSize.MEDIUM,
        keyboardTheme = "default",
        favoriteThemes = emptySet()
    )
}
