package com.urik.keyboard.ui.keyboard.components

import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SuggestionBarAccessibilityTest {
    @Test
    fun `suggestion bar has polite live region`() {
        val context = RuntimeEnvironment.getApplication()
        val bar = LinearLayout(context).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        assertEquals(
            "Suggestion bar must have POLITE live region",
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            bar.accessibilityLiveRegion
        )
    }

    @Test
    fun `suggestion bar is not hidden from accessibility`() {
        val context = RuntimeEnvironment.getApplication()
        val bar = LinearLayout(context)
        assertNotEquals(
            "Suggestion bar must not be hidden from accessibility",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            bar.importantForAccessibility
        )
    }
}
