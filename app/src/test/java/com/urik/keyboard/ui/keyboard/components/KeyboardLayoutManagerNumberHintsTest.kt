package com.urik.keyboard.ui.keyboard.components

import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyboardLayoutManagerNumberHintsTest {
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

    private fun tenLetterRow(): List<KeyboardKey> =
        (1..10).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) }

    private fun layoutWithRows(vararg rows: List<KeyboardKey>): KeyboardLayout =
        KeyboardLayout(mode = KeyboardMode.LETTERS, rows = rows.toList())

    // ── isNumberHintEnabled — setting gate ────────────────────────────────────

    @Test
    fun `isNumberHintEnabled returns false by default before updateNumberHints is called`() {
        manager.createKeyboardView(layoutWithRows(tenLetterRow()), KeyboardState())
        assertFalse(manager.isNumberHintEnabled())
    }

    @Test
    fun `isNumberHintEnabled returns false when showNumberHints is false even with 10-letter first row`() {
        manager.createKeyboardView(layoutWithRows(tenLetterRow()), KeyboardState())
        manager.updateNumberHints(false)
        assertFalse(manager.isNumberHintEnabled())
    }

    @Test
    fun `isNumberHintEnabled returns false when no layout is set even if enabled`() {
        manager.updateNumberHints(true)
        assertFalse(manager.isNumberHintEnabled())
    }

    @Test
    fun `isNumberHintEnabled returns true when enabled and first row has exactly 10 LETTER keys`() {
        manager.createKeyboardView(layoutWithRows(tenLetterRow()), KeyboardState())
        manager.updateNumberHints(true)
        assertTrue(manager.isNumberHintEnabled())
    }

    @Test
    fun `isNumberHintEnabled returns false when enabled but first row has fewer than 10 LETTER keys`() {
        val shortRow = (1..8).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) }
        manager.createKeyboardView(layoutWithRows(shortRow), KeyboardState())
        manager.updateNumberHints(true)
        assertFalse(manager.isNumberHintEnabled())
    }

    // ── isNumberHintRow — propagates setting gate ─────────────────────────────

    @Test
    fun `isNumberHintRow returns false when showNumberHints is false`() {
        val firstRow = tenLetterRow()
        manager.createKeyboardView(layoutWithRows(firstRow), KeyboardState())
        manager.updateNumberHints(false)
        assertFalse(manager.isNumberHintRow(firstRow))
    }

    @Test
    fun `isNumberHintRow returns true for first row when enabled and layout has 10 LETTER keys`() {
        val firstRow = tenLetterRow()
        manager.createKeyboardView(layoutWithRows(firstRow), KeyboardState())
        manager.updateNumberHints(true)
        assertTrue(manager.isNumberHintRow(firstRow))
    }

    @Test
    fun `isNumberHintRow returns false for non-first row even when enabled`() {
        val firstRow = tenLetterRow()
        val secondRow = listOf(KeyboardKey.Character("b", KeyboardKey.KeyType.LETTER))
        manager.createKeyboardView(layoutWithRows(firstRow, secondRow), KeyboardState())
        manager.updateNumberHints(true)
        assertFalse(manager.isNumberHintRow(secondRow))
    }
}
