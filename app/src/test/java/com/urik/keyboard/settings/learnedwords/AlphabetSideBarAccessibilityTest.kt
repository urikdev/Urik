package com.urik.keyboard.settings.learnedwords

import android.view.MotionEvent
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

    @Test
    fun `sidebar uses live region for accessibility announcements`() {
        val context = RuntimeEnvironment.getApplication()
        val sidebar = AlphabetSideBar(context)
        assertEquals(
            "AlphabetSideBar must use ACCESSIBILITY_LIVE_REGION_POLITE instead of announceForAccessibility",
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            sidebar.accessibilityLiveRegion
        )
    }

    @Test
    fun `selecting a letter updates content description`() {
        val context = RuntimeEnvironment.getApplication()
        val sidebar = AlphabetSideBar(context)
        sidebar.setLetters(listOf("A", "B", "C"))

        sidebar.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
        )
        sidebar.layout(0, 0, 100, 300)

        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 50f, 0f, 0)
        sidebar.onTouchEvent(downEvent)
        downEvent.recycle()

        assertEquals(
            "Content description must reflect the currently selected letter",
            "A",
            sidebar.contentDescription
        )
    }

    @Test
    fun `content description resets to static description on touch release`() {
        val context = RuntimeEnvironment.getApplication()
        val sidebar = AlphabetSideBar(context)
        sidebar.setLetters(listOf("A", "B", "C"))

        sidebar.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
        )
        sidebar.layout(0, 0, 100, 300)

        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 50f, 0f, 0)
        sidebar.onTouchEvent(downEvent)
        downEvent.recycle()

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 50f, 0f, 0)
        sidebar.onTouchEvent(upEvent)
        upEvent.recycle()

        val expectedDescription = context.getString(com.urik.keyboard.R.string.alphabet_sidebar_description)
        assertEquals(
            "Content description must reset to static sidebar description after touch release",
            expectedDescription,
            sidebar.contentDescription
        )
    }
}
