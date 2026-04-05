package com.urik.keyboard.ui.keyboard.components

import android.view.View
import android.widget.Button
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
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
class ClipboardPanelAccessibilityTest {
    private lateinit var panel: ClipboardPanel

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val themeManager: ThemeManager = mock()
        val themeFlow = MutableStateFlow<KeyboardTheme>(Default)
        whenever(themeManager.currentTheme).thenReturn(themeFlow)
        panel = ClipboardPanel(context, themeManager)
    }

    private fun findButtonByText(text: String): Button? {
        fun search(view: View): Button? {
            if (view is Button && view.text?.toString() == text) return view
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    val found = search(view.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }
        return search(panel)
    }

    @Test
    fun `pinned tab has tab role description`() {
        val pinnedTab = findButtonByText("Pinned")
        val nodeInfo = AccessibilityNodeInfoCompat.wrap(pinnedTab!!.createAccessibilityNodeInfo())
        assertEquals("tab", nodeInfo.roleDescription?.toString())
    }

    @Test
    fun `recent tab has tab role description`() {
        val recentTab = findButtonByText("Recent")
        val nodeInfo = AccessibilityNodeInfoCompat.wrap(recentTab!!.createAccessibilityNodeInfo())
        assertEquals("tab", nodeInfo.roleDescription?.toString())
    }

    @Test
    fun `recent tab is selected by default`() {
        val recentTab = findButtonByText("Recent")
        val nodeInfo = AccessibilityNodeInfoCompat.wrap(recentTab!!.createAccessibilityNodeInfo())
        assertEquals("selected", nodeInfo.stateDescription?.toString())
    }

    @Test
    fun `pinned tab is not selected by default`() {
        val pinnedTab = findButtonByText("Pinned")
        val nodeInfo = AccessibilityNodeInfoCompat.wrap(pinnedTab!!.createAccessibilityNodeInfo())
        assertEquals("not selected", nodeInfo.stateDescription?.toString())
    }
}
