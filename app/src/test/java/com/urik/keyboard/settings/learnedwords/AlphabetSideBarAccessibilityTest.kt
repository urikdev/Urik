package com.urik.keyboard.settings.learnedwords

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AlphabetSideBarAccessibilityTest {
    @Test
    fun `sidebar is important for accessibility`() {
        val context = RuntimeEnvironment.getApplication()
        val sidebar = AlphabetSideBar(context)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_YES,
            sidebar.importantForAccessibility
        )
    }

    @Test
    fun `sidebar has content description`() {
        val context = RuntimeEnvironment.getApplication()
        val sidebar = AlphabetSideBar(context)
        assertNotNull(
            "AlphabetSideBar must have a content description",
            sidebar.contentDescription
        )
    }
}
