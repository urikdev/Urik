package com.urik.keyboard.ui.keyboard.components

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies AccessibilityNodeInfo properties of CharacterVariationPopup to prevent
 * system auto-labeler from projecting "Type ." overlays on Android 10+ devices.
 */
@RunWith(RobolectricTestRunner::class)
class CharacterVariationPopupAccessibilityTest {
    private lateinit var popup: CharacterVariationPopup
    private lateinit var scrollView: HorizontalScrollView
    private lateinit var variationContainer: LinearLayout

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val themeManager: ThemeManager = mock()
        val themeFlow = MutableStateFlow<KeyboardTheme>(Default)
        whenever(themeManager.currentTheme).thenReturn(themeFlow)

        popup = CharacterVariationPopup(context, themeManager)
        popup.setCharacterVariations(".", listOf("!", "?", ","), {})

        scrollView = popup.contentView as HorizontalScrollView
        variationContainer = scrollView.getChildAt(0) as LinearLayout
    }

    @Test
    fun `popup window is not focusable to prevent focus shift`() {
        assertFalse(
            "PopupWindow must not be focusable to avoid triggering a11y node hints",
            popup.isFocusable,
        )
    }

    @Test
    fun `popup window is outside touchable for dismiss behavior`() {
        assertTrue(
            "PopupWindow must be outside touchable for proper dismiss",
            popup.isOutsideTouchable,
        )
    }

    @Test
    fun `scrollView is not important for accessibility`() {
        assertEquals(
            "ScrollView container must not be reported to accessibility services",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            scrollView.importantForAccessibility,
        )
    }

    @Test
    fun `scrollView node is not editable`() {
        val nodeInfo = AccessibilityNodeInfoCompat.wrap(scrollView.createAccessibilityNodeInfo())

        assertFalse(
            "ScrollView node must not be editable to prevent Type hint",
            nodeInfo.isEditable,
        )
    }

    @Test
    fun `scrollView node has no ACTION_SET_TEXT`() {
        val nodeInfo = AccessibilityNodeInfoCompat.wrap(scrollView.createAccessibilityNodeInfo())
        val hasSetText = nodeInfo.actionList.any {
            it.id == AccessibilityNodeInfoCompat.ACTION_SET_TEXT
        }

        assertFalse(
            "ScrollView node must not expose ACTION_SET_TEXT",
            hasSetText,
        )
    }

    @Test
    fun `variationContainer is not important for accessibility`() {
        assertEquals(
            "Variation container must not be reported to accessibility services",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            variationContainer.importantForAccessibility,
        )
    }

    @Test
    fun `buttons have character option role description`() {
        assertTrue(
            "Popup must have at least one button",
            variationContainer.childCount > 0,
        )

        for (i in 0 until variationContainer.childCount) {
            val button = variationContainer.getChildAt(i) as Button
            val nodeInfo = AccessibilityNodeInfoCompat.wrap(button.createAccessibilityNodeInfo())

            assertEquals(
                "Button '${ button.text }' must have 'character option' role",
                "character option",
                nodeInfo.roleDescription?.toString(),
            )
        }
    }

    @Test
    fun `buttons are not editable`() {
        for (i in 0 until variationContainer.childCount) {
            val button = variationContainer.getChildAt(i) as Button
            val nodeInfo = AccessibilityNodeInfoCompat.wrap(button.createAccessibilityNodeInfo())

            assertFalse(
                "Button '${button.text}' must not be editable",
                nodeInfo.isEditable,
            )
        }
    }

    @Test
    fun `buttons have no ACTION_SET_TEXT`() {
        for (i in 0 until variationContainer.childCount) {
            val button = variationContainer.getChildAt(i) as Button
            val nodeInfo = AccessibilityNodeInfoCompat.wrap(button.createAccessibilityNodeInfo())
            val hasSetText = nodeInfo.actionList.any {
                it.id == AccessibilityNodeInfoCompat.ACTION_SET_TEXT
            }

            assertFalse(
                "Button '${button.text}' must not expose ACTION_SET_TEXT",
                hasSetText,
            )
        }
    }

    @Test
    fun `buttons have content descriptions with position info`() {
        for (i in 0 until variationContainer.childCount) {
            val button = variationContainer.getChildAt(i) as Button

            assertNotNull(
                "Button '${button.text}' must have content description",
                button.contentDescription,
            )
            assertTrue(
                "Content description must contain position info",
                button.contentDescription.contains((i + 1).toString()),
            )
        }
    }

    @Test
    fun `buttons remain clickable and focusable for TalkBack`() {
        for (i in 0 until variationContainer.childCount) {
            val button = variationContainer.getChildAt(i) as Button

            assertTrue(
                "Button '${button.text}' must remain clickable",
                button.isClickable,
            )
            assertTrue(
                "Button '${button.text}' must remain focusable",
                button.isFocusable,
            )
        }
    }

    @Test
    fun `container children are accessible despite container being non-important`() {
        val containerImportance = variationContainer.importantForAccessibility
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, containerImportance)

        for (i in 0 until variationContainer.childCount) {
            val button = variationContainer.getChildAt(i) as Button
            val nodeInfo = button.createAccessibilityNodeInfo()

            assertNotNull(
                "Button '${button.text}' must still produce AccessibilityNodeInfo",
                nodeInfo,
            )
        }
    }

    @Test
    fun `punctuation popup has correct button count`() {
        assertEquals(
            "Base char '.' + 3 variations = 4 buttons",
            4,
            variationContainer.childCount,
        )
    }

    @Test
    fun `empty base char popup omits base button`() {
        val context = RuntimeEnvironment.getApplication()
        val themeManager: ThemeManager = mock()
        val themeFlow = MutableStateFlow<KeyboardTheme>(Default)
        whenever(themeManager.currentTheme).thenReturn(themeFlow)

        val emptyBasePopup = CharacterVariationPopup(context, themeManager)
        emptyBasePopup.setCharacterVariations("", listOf("!", "?"), {})

        val container = (emptyBasePopup.contentView as ViewGroup).getChildAt(0) as LinearLayout

        assertEquals(
            "Empty base char should produce only variation buttons",
            2,
            container.childCount,
        )

        for (i in 0 until container.childCount) {
            val button = container.getChildAt(i) as Button
            val nodeInfo = AccessibilityNodeInfoCompat.wrap(button.createAccessibilityNodeInfo())

            assertEquals(
                "character option",
                nodeInfo.roleDescription?.toString(),
            )
            assertFalse(nodeInfo.isEditable)
        }
    }
}
