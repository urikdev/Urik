package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.RecentEmojiProvider
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.theme.ThemeManager
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SwipeKeyboardViewInitTest {
    @Mock private lateinit var layoutManager: KeyboardLayoutManager

    @Mock private lateinit var swipeDetector: SwipeDetector

    @Mock private lateinit var spellCheckManager: SpellCheckManager

    @Mock private lateinit var wordLearningEngine: WordLearningEngine

    @Mock private lateinit var themeManager: ThemeManager

    @Mock private lateinit var languageManager: LanguageManager

    @Mock private lateinit var emojiSearchManager: EmojiSearchManager

    @Mock private lateinit var recentEmojiProvider: RecentEmojiProvider

    private lateinit var view: SwipeKeyboardView
    private lateinit var closeable: AutoCloseable

    @Before
    fun setup() {
        closeable = MockitoAnnotations.openMocks(this)
        val context = ApplicationProvider.getApplicationContext<Context>()
        view = SwipeKeyboardView(context)
    }

    @After
    fun teardown() {
        closeable.close()
    }

    @Test
    fun `updateKeyboard throws IllegalStateException when not initialized`() {
        val layout = mock(KeyboardLayout::class.java)
        val state = mock(KeyboardState::class.java)
        assertThrows(IllegalStateException::class.java) {
            view.updateKeyboard(layout, state)
        }
    }

    @Test
    fun `onTouchEvent throws IllegalStateException when not initialized`() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        assertThrows(IllegalStateException::class.java) {
            view.onTouchEvent(event)
        }
        event.recycle()
    }

    @Test
    fun `updateSuggestions throws IllegalStateException when not initialized`() {
        assertThrows(IllegalStateException::class.java) {
            view.updateSuggestions(emptyList())
        }
    }

    @Test
    fun `updateKeyboard does not throw after initialize is called`() {
        view.initialize(
            layoutManager,
            swipeDetector,
            spellCheckManager,
            wordLearningEngine,
            themeManager,
            languageManager,
            emojiSearchManager,
            recentEmojiProvider
        )
        val layout = mock(KeyboardLayout::class.java)
        val state = mock(KeyboardState::class.java)
        view.updateKeyboard(layout, state)
    }
}
