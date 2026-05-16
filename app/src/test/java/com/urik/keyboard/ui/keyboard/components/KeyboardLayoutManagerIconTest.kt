package com.urik.keyboard.ui.keyboard.components

import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyboardLayoutManagerIconTest {
    private lateinit var layoutManager: KeyboardLayoutManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val themeManager: ThemeManager = mock()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))

        val languageManager: LanguageManager = mock()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("ja"))
        whenever(languageManager.currentLanguage).thenReturn(MutableStateFlow("ja"))
        whenever(languageManager.activeLanguages).thenReturn(MutableStateFlow(listOf("ja")))

        layoutManager = KeyboardLayoutManager(
            context = context,
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            onLanguageSwitch = {},
            onShowInputMethodPicker = {},
            characterVariationService = mock<CharacterVariationService>(),
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = mock<CacheMemoryManager>()
        )
    }

    private fun buildViewWithRow(vararg keys: KeyboardKey): ViewGroup {
        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf(keys.toList()),
            script = "Hira",
            isRTL = false
        )
        return layoutManager.createKeyboardView(layout, KeyboardState()) as ViewGroup
    }

    private fun buildViewWithKey(key: KeyboardKey): ViewGroup = buildViewWithRow(key)

    private fun findAllButtons(container: ViewGroup): List<Button> {
        val buttons = mutableListOf<Button>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is Button) buttons.add(child)
            if (child is ViewGroup) buttons.addAll(findAllButtons(child))
        }
        return buttons
    }

    @Test
    fun `BACKSPACE in flick row has same weight as other action keys in that row`() {
        val row = listOf(
            KeyboardKey.Action(KeyboardKey.ActionType.EMOJI),
            KeyboardKey.FlickKey(
                "わ",
                up = null,
                right = "ん",
                down = null,
                left = "を",
                type = KeyboardKey.KeyType.LETTER
            ),
            KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        )
        val view = buildViewWithRow(*row.toTypedArray())
        val buttons = findAllButtons(view)
        val emojiButton = buttons.firstOrNull {
            (it.getTag(com.urik.keyboard.R.id.key_data) as? KeyboardKey.Action)?.action ==
                KeyboardKey.ActionType.EMOJI
        }
        val backspaceButton = buttons.firstOrNull {
            (it.getTag(com.urik.keyboard.R.id.key_data) as? KeyboardKey.Action)?.action ==
                KeyboardKey.ActionType.BACKSPACE
        }
        assertNotNull("emoji button must exist", emojiButton)
        assertNotNull("backspace button must exist", backspaceButton)
        val emojiWeight = (emojiButton!!.layoutParams as LinearLayout.LayoutParams).weight
        val backspaceWeight = (backspaceButton!!.layoutParams as LinearLayout.LayoutParams).weight
        assertEquals(
            "backspace weight must equal other action key weight in flick row",
            emojiWeight,
            backspaceWeight,
            0.001f
        )
    }

    @Test
    fun `EMOJI action key renders with empty text label`() {
        val view = buildViewWithKey(KeyboardKey.Action(KeyboardKey.ActionType.EMOJI))
        val button = findAllButtons(view).firstOrNull()
        assertNotNull("EMOJI button must exist", button)
        assertEquals(
            "EMOJI key must use icon (empty text), not native emoji character",
            "",
            button!!.text.toString()
        )
    }
}
