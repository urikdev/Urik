package com.urik.keyboard.ui.keyboard.components

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
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
class KeyboardLayoutManagerAccessibilityTest {
    private lateinit var layoutManager: KeyboardLayoutManager
    private lateinit var context: android.content.Context
    private var lastClickedKey: KeyboardKey? = null

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val themeManager: ThemeManager = mock()
        val themeFlow = MutableStateFlow<KeyboardTheme>(Default)
        whenever(themeManager.currentTheme).thenReturn(themeFlow)

        val languageManager: LanguageManager = mock()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))
        whenever(languageManager.currentLanguage).thenReturn(MutableStateFlow("en"))
        whenever(languageManager.activeLanguages).thenReturn(MutableStateFlow(listOf("en")))

        val characterVariationService: CharacterVariationService = mock()
        val cacheMemoryManager: CacheMemoryManager = mock()

        layoutManager = KeyboardLayoutManager(
            context = context,
            onKeyClick = { key -> lastClickedKey = key },
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            onLanguageSwitch = {},
            onShowInputMethodPicker = {},
            characterVariationService = characterVariationService,
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
    }

    private fun buildKeyboardView(state: KeyboardState = KeyboardState()): ViewGroup {
        val layout = KeyboardLayout(
            mode = com.urik.keyboard.model.KeyboardMode.LETTERS,
            rows = listOf(
                listOf(
                    KeyboardKey.Character("q", KeyboardKey.KeyType.LETTER),
                    KeyboardKey.Character("w", KeyboardKey.KeyType.LETTER)
                ),
                listOf(
                    KeyboardKey.Action(KeyboardKey.ActionType.SHIFT),
                    KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
                ),
                listOf(
                    KeyboardKey.Action(KeyboardKey.ActionType.SPACE)
                )
            ),
            script = "Latn",
            isRTL = false
        )
        return layoutManager.createKeyboardView(layout, state) as ViewGroup
    }

    private fun findButtonByContentDescription(container: ViewGroup, descriptionSubstring: String): Button? {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is Button && child.contentDescription?.contains(descriptionSubstring) == true) {
                return child
            }
            if (child is ViewGroup) {
                val found = findButtonByContentDescription(child, descriptionSubstring)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findAllButtons(container: ViewGroup): List<Button> {
        val buttons = mutableListOf<Button>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is Button) {
                buttons.add(child)
            }
            if (child is ViewGroup) {
                buttons.addAll(findAllButtons(child))
            }
        }
        return buttons
    }

    @Test
    fun `character key buttons have key role description`() {
        val keyboardView = buildKeyboardView()
        val qButton = findButtonByContentDescription(keyboardView, "q")
        assertNotNull("Should find q key button", qButton)

        val nodeInfo = AccessibilityNodeInfoCompat.wrap(qButton!!.createAccessibilityNodeInfo())
        assertEquals("key", nodeInfo.roleDescription?.toString())
    }

    @Test
    fun `action key buttons have action key role description`() {
        val keyboardView = buildKeyboardView()
        val shiftButton = findButtonByContentDescription(keyboardView, "Shift")
        assertNotNull("Should find shift key button", shiftButton)

        val nodeInfo = AccessibilityNodeInfoCompat.wrap(shiftButton!!.createAccessibilityNodeInfo())
        assertEquals("action key", nodeInfo.roleDescription?.toString())
    }

    @Test
    fun `shift key has inactive state description when not pressed`() {
        val state = KeyboardState(isShiftPressed = false, isCapsLockOn = false)
        val keyboardView = buildKeyboardView(state)
        val shiftButton = findButtonByContentDescription(keyboardView, "Shift")
        assertNotNull("Should find shift key button", shiftButton)

        val nodeInfo = AccessibilityNodeInfoCompat.wrap(shiftButton!!.createAccessibilityNodeInfo())
        assertEquals("inactive", nodeInfo.stateDescription?.toString())
    }

    @Test
    fun `shift key has active state description when pressed`() {
        val state = KeyboardState(isShiftPressed = true, isCapsLockOn = false)
        val keyboardView = buildKeyboardView(state)
        val shiftButton = findButtonByContentDescription(keyboardView, "active")
        assertNotNull("Should find shift active button", shiftButton)

        val nodeInfo = AccessibilityNodeInfoCompat.wrap(shiftButton!!.createAccessibilityNodeInfo())
        assertEquals("active", nodeInfo.stateDescription?.toString())
    }

    @Test
    fun `caps lock key has on state description`() {
        val state = KeyboardState(isShiftPressed = false, isCapsLockOn = true)
        val keyboardView = buildKeyboardView(state)
        val capsButton = findButtonByContentDescription(keyboardView, "Caps lock")
        assertNotNull("Should find caps lock button", capsButton)

        val nodeInfo = AccessibilityNodeInfoCompat.wrap(capsButton!!.createAccessibilityNodeInfo())
        assertEquals("on", nodeInfo.stateDescription?.toString())
    }

    @Test
    fun `backspace key has delete word custom action`() {
        val keyboardView = buildKeyboardView()
        val backspaceButton = findButtonByContentDescription(keyboardView, "Delete")
        assertNotNull("Should find backspace button", backspaceButton)

        val nodeInfo = AccessibilityNodeInfoCompat.wrap(backspaceButton!!.createAccessibilityNodeInfo())
        val deleteWordAction = nodeInfo.actionList.find {
            it.id == R.id.action_delete_word
        }
        assertNotNull(
            "Backspace button must have delete word custom action",
            deleteWordAction
        )
        assertEquals(
            "Delete word",
            deleteWordAction?.label?.toString()
        )
    }

    @Test
    fun `keyboard row containers are not important for accessibility`() {
        val keyboardView = buildKeyboardView()

        for (i in 0 until keyboardView.childCount) {
            val row = keyboardView.getChildAt(i) as? LinearLayout ?: continue
            assertEquals(
                "Row $i must not be important for accessibility",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                row.importantForAccessibility
            )
        }
    }
}
