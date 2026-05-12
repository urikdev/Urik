package com.urik.keyboard.ui.keyboard.components

import android.content.res.Configuration
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.DevicePosture
import com.urik.keyboard.service.DeviceSizeClass
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.PostureInfo
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * TDD safety tests for [KeyboardLayoutManager] cache null-safety contract.
 *
 * Verifies that consumers of cachedDimensions never NPE regardless of whether
 * adaptiveDimensions is null, which branch of ensureCacheValid() ran, or whether
 * a cache key was manually removed after population.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardLayoutManagerCacheSafetyTest {
    private lateinit var manager: KeyboardLayoutManager

    @Before
    fun setup() {
        manager = createManager()
    }

    private fun createManager(): KeyboardLayoutManager {
        val context = RuntimeEnvironment.getApplication()
        val languageManager = mock<LanguageManager>()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))
        val themeManager = mock<ThemeManager>()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))
        return KeyboardLayoutManager(
            context = context,
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = mock<CharacterVariationService>(),
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = CacheMemoryManager(context)
        )
    }

    /**
     * Builds a layout covering: a number row (triggers isTopNumberRow + numberRowGutter paths)
     * and a standard letter row (triggers remaining dimension paths).
     */
    private fun buildLayout(): KeyboardLayout {
        val numberRow = (0 until 10).map { KeyboardKey.Character("$it", KeyboardKey.KeyType.NUMBER) }
        val letterRow = (0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) }
        return KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf(numberRow, letterRow)
        )
    }

    private fun buildAdaptiveDimensions(): AdaptiveDimensions {
        val postureInfo = PostureInfo(
            sizeClass = DeviceSizeClass.COMPACT,
            posture = DevicePosture.NORMAL,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            isTablet = false,
            orientation = Configuration.ORIENTATION_PORTRAIT
        )
        return AdaptiveDimensions.compute(postureInfo, KeySize.MEDIUM, 2.75f)
    }

    @Test
    fun `createKeyboardView does not throw NPE when adaptiveDimensions is null`() {
        val view = manager.createKeyboardView(buildLayout(), KeyboardState())
        assertNotNull(view)
    }

    @Test
    fun `createKeyboardView does not throw NPE after updateAdaptiveDimensions`() {
        manager.updateAdaptiveDimensions(buildAdaptiveDimensions())
        val view = manager.createKeyboardView(buildLayout(), KeyboardState())
        assertNotNull(view)
    }

    @Test
    fun `cache is repopulated after invalidation between consumer calls`() {
        manager.createKeyboardView(buildLayout(), KeyboardState())
        manager.onDensityChanged()
        val view = manager.createKeyboardView(buildLayout(), KeyboardState())
        assertNotNull(view)
    }

    @Test
    fun `both row construction paths are exercised without NPE`() {
        val numberRow = (0 until 10).map { KeyboardKey.Character("$it", KeyboardKey.KeyType.NUMBER) }
        val nineLetterRow = (0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) }
        val standardRow = (0 until 7).map { KeyboardKey.Character("b", KeyboardKey.KeyType.LETTER) }
        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf(numberRow, nineLetterRow, standardRow)
        )
        val view = manager.createKeyboardView(layout, KeyboardState())
        assertNotNull(view)
    }

    @Test
    fun `missing cache key does not crash consumers`() {
        manager.createKeyboardView(buildLayout(), KeyboardState())

        val cacheField = KeyboardLayoutManager::class.java
            .getDeclaredField("cachedDimensions")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(manager) as MutableMap<String, Int>
        cache.remove("numberRowGutter")

        val validField = KeyboardLayoutManager::class.java
            .getDeclaredField("cacheValid")
            .apply { isAccessible = true }
        validField.setBoolean(manager, true)

        manager.createKeyboardView(buildLayout(), KeyboardState())
    }
}
