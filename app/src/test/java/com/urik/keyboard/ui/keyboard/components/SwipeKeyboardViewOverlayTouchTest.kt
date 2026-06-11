package com.urik.keyboard.ui.keyboard.components

import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.RecentEmojiProvider
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SwipeKeyboardViewOverlayTouchTest {
    private lateinit var view: SwipeKeyboardView
    private lateinit var swipeDetector: SwipeDetector

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()

        val languageManager = mock<LanguageManager>()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))

        val themeManager = mock<ThemeManager>()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))

        val cacheMemoryManager = CacheMemoryManager(context)
        val jobField = CacheMemoryManager::class.java.getDeclaredField("memoryMonitoringJob")
        jobField.isAccessible = true
        (jobField.get(cacheMemoryManager) as? Job)?.cancel()

        val layoutManager = KeyboardLayoutManager(
            context = context,
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = mock<CharacterVariationService>(),
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
        layoutManager.updateHapticSettings(enabled = false, amplitude = 0)

        swipeDetector = mock<SwipeDetector>()

        view = SwipeKeyboardView(context)
        view.initialize(
            layoutManager,
            swipeDetector,
            mock<SpellCheckManager>(),
            mock<WordLearningEngine>(),
            themeManager,
            languageManager,
            mock<EmojiSearchManager>(),
            mock<RecentEmojiProvider>()
        )

        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf((0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) })
        )
        view.updateKeyboard(layout, KeyboardState())
        view.updateSuggestions(listOf("badword"))
    }

    private fun findSuggestionView(): TextView {
        val suggestionBarField = SwipeKeyboardView::class.java.getDeclaredField("suggestionBar")
        suggestionBarField.isAccessible = true
        val bar = suggestionBarField.get(view) as android.widget.LinearLayout
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child is TextView && child.getTag(R.id.suggestion_text) == "badword") {
                return child
            }
        }
        error("suggestion view for 'badword' not found")
    }

    private fun confirmationOverlay(): android.widget.FrameLayout? {
        val field = SwipeKeyboardView::class.java.getDeclaredField("confirmationOverlay")
        field.isAccessible = true
        return field.get(view) as? android.widget.FrameLayout
    }

    private fun showRemovalConfirmation() {
        findSuggestionView().performLongClick()
        assertNotNull("confirmationOverlay should be set after long press", confirmationOverlay())
    }

    private fun motionEvent(action: Int, x: Float = 50f, y: Float = 50f): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    @Test
    fun `onInterceptTouchEvent returns false and does not feed swipeDetector when overlay shown`() {
        showRemovalConfirmation()
        clearInvocations(swipeDetector)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        val move = motionEvent(MotionEvent.ACTION_MOVE)
        val up = motionEvent(MotionEvent.ACTION_UP)
        try {
            assertFalse(view.onInterceptTouchEvent(down))
            assertFalse(view.onInterceptTouchEvent(move))
            assertFalse(view.onInterceptTouchEvent(up))
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }

        verify(swipeDetector, never()).handleTouchEvent(any(), any())
    }

    @Test
    fun `onTouchEvent returns false when overlay shown`() {
        showRemovalConfirmation()

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        try {
            assertFalse(view.onTouchEvent(down))
        } finally {
            down.recycle()
        }
    }

    @Test
    fun `dispatchTouchEvent routes to overlay children even with stale gesture state`() {
        showRemovalConfirmation()

        val isSwipeActiveField = SwipeKeyboardView::class.java.getDeclaredField("isSwipeActive")
        isSwipeActiveField.isAccessible = true
        isSwipeActiveField.setBoolean(view, true)

        val windowManager = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        windowManager.addView(view, WindowManager.LayoutParams(1080, 2400))
        shadowOf(Looper.getMainLooper()).idle()

        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, 1080, 2400)

        val overlay = confirmationOverlay()!!
        val removeButton = findButtonByText(overlay, "Remove")

        val viewLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        val buttonLocation = IntArray(2)
        removeButton.getLocationOnScreen(buttonLocation)
        val x = buttonLocation[0] - viewLocation[0] + removeButton.width / 2f
        val y = buttonLocation[1] - viewLocation[1] + removeButton.height / 2f

        val down = motionEvent(MotionEvent.ACTION_DOWN, x, y)
        val up = motionEvent(MotionEvent.ACTION_UP, x, y)
        try {
            view.dispatchTouchEvent(down)
            assertTrue("Remove button should receive the DOWN through the overlay", removeButton.isPressed)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("confirmationOverlay should be cleared after Remove is tapped", confirmationOverlay())
    }

    @Test
    fun `Cancel button click hides overlay`() {
        showRemovalConfirmation()

        val overlay = confirmationOverlay()!!
        val cancelButton = findButtonByText(overlay, "Cancel")

        cancelButton.performClick()

        assertNull("confirmationOverlay should be cleared after Cancel is clicked", confirmationOverlay())
    }

    @Test
    fun `showRemovalConfirmation flushes in-flight gesture state`() {
        val down = motionEvent(MotionEvent.ACTION_DOWN)
        try {
            view.onTouchEvent(down)
        } finally {
            down.recycle()
        }

        showRemovalConfirmation()

        verify(swipeDetector).handleTouchEvent(
            argThat { action == MotionEvent.ACTION_CANCEL },
            any()
        )

        val isSwipeActiveField = SwipeKeyboardView::class.java.getDeclaredField("isSwipeActive")
        isSwipeActiveField.isAccessible = true
        assertFalse(isSwipeActiveField.getBoolean(view))

        val hasTouchStartField = SwipeKeyboardView::class.java.getDeclaredField("hasTouchStart")
        hasTouchStartField.isAccessible = true
        assertFalse(hasTouchStartField.getBoolean(view))
    }

    @Test
    fun `updateKeyboard while overlay shown removes overlay and resets pending suggestion`() {
        showRemovalConfirmation()

        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf((0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) })
        )
        view.updateKeyboard(layout, KeyboardState())

        assertNull("confirmationOverlay should be cleared after keyboard rebuild", confirmationOverlay())

        val pendingField = SwipeKeyboardView::class.java.getDeclaredField("pendingRemovalSuggestion")
        pendingField.isAccessible = true
        assertNull(pendingField.get(view))

        val swipeOverlayField = SwipeKeyboardView::class.java.getDeclaredField("swipeOverlay")
        swipeOverlayField.isAccessible = true
        val swipeOverlay = swipeOverlayField.get(view) as android.view.View
        assertTrue("swipeOverlay must remain attached after rebuild", swipeOverlay.parent === view)
    }

    @Test
    fun `showRemovalConfirmation works again after keyboard rebuild`() {
        showRemovalConfirmation()

        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf((0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) })
        )
        view.updateKeyboard(layout, KeyboardState())
        view.updateSuggestions(listOf("badword"))

        showRemovalConfirmation()
    }

    private fun findButtonByText(group: android.view.ViewGroup, text: String): android.widget.Button {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.widget.Button && child.text.toString() == text) {
                return child
            }
            if (child is android.view.ViewGroup) {
                val found = runCatching { findButtonByText(child, text) }.getOrNull()
                if (found != null) return found
            }
        }
        error("Button with text '$text' not found")
    }
}
