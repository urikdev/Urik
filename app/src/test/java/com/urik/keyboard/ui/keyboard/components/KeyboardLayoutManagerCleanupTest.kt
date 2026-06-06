package com.urik.keyboard.ui.keyboard.components

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import java.lang.reflect.Field
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardLayoutManagerCleanupTest {
    private lateinit var manager: KeyboardLayoutManager
    private val clickedKeys = mutableListOf<KeyboardKey>()

    @Before
    fun setup() {
        clickedKeys.clear()
        manager = createManager()
        manager.updateHapticSettings(enabled = false, amplitude = 0)
    }

    private fun createManager(): KeyboardLayoutManager {
        val context = RuntimeEnvironment.getApplication()
        val languageManager = mock<LanguageManager>()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))
        val themeManager = mock<ThemeManager>()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))
        val cacheMemoryManager = CacheMemoryManager(context)
        val jobField = CacheMemoryManager::class.java.getDeclaredField("memoryMonitoringJob")
        jobField.isAccessible = true
        (jobField.get(cacheMemoryManager) as? Job)?.cancel()
        return KeyboardLayoutManager(
            context = context,
            onKeyClick = { key -> clickedKeys.add(key) },
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = mock<CharacterVariationService>(),
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
    }

    private fun buttonFor(key: KeyboardKey): Button {
        val button = Button(RuntimeEnvironment.getApplication())
        button.setTag(R.id.key_data, key)
        return button
    }

    private fun getSet(fieldName: String): MutableSet<Button> {
        val field: Field = KeyboardLayoutManager::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(manager) as MutableSet<Button>
    }

    // ── cleanupButton invariants ──────────────────────────────────────────────

    @Test
    fun `cleanupButton removes from symbolsLongPressFired`() {
        val button = buttonFor(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        val set = getSet("symbolsLongPressFired")
        set.add(button)

        manager.cleanupButton(button)

        assertFalse("cleanupButton must remove from symbolsLongPressFired", set.contains(button))
    }

    @Test
    fun `cleanupButton removes from characterLongPressFired`() {
        val button = buttonFor(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        val set = getSet("characterLongPressFired")
        set.add(button)

        manager.cleanupButton(button)

        assertFalse("cleanupButton must remove from characterLongPressFired", set.contains(button))
    }

    @Test
    fun `cleanupButton removes from customMappingLongPressFired`() {
        val button = buttonFor(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        val set = getSet("customMappingLongPressFired")
        set.add(button)

        manager.cleanupButton(button)

        assertFalse("cleanupButton must remove from customMappingLongPressFired", set.contains(button))
    }

    @Test
    fun `cleanupButton removes from hapticDownFiredButtons`() {
        val button = buttonFor(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        val set = getSet("hapticDownFiredButtons")
        set.add(button)

        manager.cleanupButton(button)

        assertFalse("cleanupButton must remove from hapticDownFiredButtons", set.contains(button))
    }

    @Test
    fun `cleanupButton does NOT remove from longPressConsumedButtons`() {
        val button = buttonFor(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        val set = getSet("longPressConsumedButtons")
        set.add(button)

        manager.cleanupButton(button)

        assertTrue(
            "cleanupButton must NOT remove from longPressConsumedButtons — click-listener gate depends on it",
            set.contains(button)
        )
    }

    @Test
    fun `cleanupButton removes from pressStartTimes`() {
        val button = buttonFor(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        manager.pressStartTimes[button] = SystemClock.uptimeMillis()

        manager.cleanupButton(button)

        assertFalse("cleanupButton must remove from pressStartTimes", manager.pressStartTimes.containsKey(button))
    }

    // ── Stale pressStartTimes entry on recycled button ────────────────────────

    @Test
    fun `cleanupButton removes stale pressStartTimes - recycled button short press not incorrectly consumed`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)

        val durationMs = LongPressDuration.MEDIUM.durationMs
        manager.pressStartTimes[button] = SystemClock.uptimeMillis() - durationMs - 100L

        manager.cleanupButton(button)

        val shortPressElapsed = LongPressDuration.MEDIUM.durationMs - 50L
        val fakeStart = SystemClock.uptimeMillis() - shortPressElapsed
        manager.pressStartTimes[button] = fakeStart

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        val consumed = manager.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertFalse(
            "Short press on recycled button must not be incorrectly consumed due to stale pressStartTimes",
            consumed
        )
    }
}
