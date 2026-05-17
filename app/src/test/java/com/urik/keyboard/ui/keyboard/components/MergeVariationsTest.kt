package com.urik.keyboard.ui.keyboard.components

import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MergeVariationsTest {
    private lateinit var manager: KeyboardLayoutManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val languageManager = mock<LanguageManager>()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))
        val themeManager = mock<ThemeManager>()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))
        manager = KeyboardLayoutManager(
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

    @Test
    fun `custom prepended before built-ins`() {
        val result = manager.mergeVariations(listOf("ǔ", "ū"), listOf("ù", "ú", "û", "ü"))
        assertEquals(listOf("ǔ", "ū", "ù", "ú", "û", "ü"), result)
    }

    @Test
    fun `custom duplicating built-in deduped, custom kept at front`() {
        val result = manager.mergeVariations(listOf("û"), listOf("ù", "ú", "û", "ü"))
        assertEquals(listOf("û", "ù", "ú", "ü"), result)
    }

    @Test
    fun `empty custom returns built-ins unchanged`() {
        val builtIns = listOf("ù", "ú", "û", "ü")
        val result = manager.mergeVariations(emptyList(), builtIns)
        assertEquals(builtIns, result)
    }

    @Test
    fun `custom only, no built-ins`() {
        val result = manager.mergeVariations(listOf("ǔ"), emptyList())
        assertEquals(listOf("ǔ"), result)
    }

    @Test
    fun `both empty returns empty`() {
        assertEquals(emptyList<String>(), manager.mergeVariations(emptyList(), emptyList()))
    }

    @Test
    fun `NFC dedup across custom and built-in - precomposed vs decomposed`() {
        val precomposed = "é"
        val decomposed = "é"
        val result = manager.mergeVariations(listOf(precomposed), listOf(decomposed, "è"))
        assertEquals(listOf(precomposed, "è"), result)
    }

    @Test
    fun `custom order preserved`() {
        val result = manager.mergeVariations(listOf("ā", "ǎ", "à"), listOf("á", "â"))
        assertEquals(listOf("ā", "ǎ", "à", "á", "â"), result)
    }

    @Test
    fun `built-in order preserved after customs`() {
        val result = manager.mergeVariations(listOf("ǔ"), listOf("ù", "ú", "û", "ü"))
        assertEquals(listOf("ǔ", "ù", "ú", "û", "ü"), result)
    }

    @Test
    fun `multiple custom duplicates of built-ins all deduped`() {
        val result = manager.mergeVariations(listOf("ù", "û"), listOf("ù", "ú", "û", "ü"))
        assertEquals(listOf("ù", "û", "ú", "ü"), result)
    }
}
