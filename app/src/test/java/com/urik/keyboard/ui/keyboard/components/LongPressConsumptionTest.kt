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
class LongPressConsumptionTest {
    private lateinit var manager: KeyboardLayoutManager
    private val clickedKeys = mutableListOf<KeyboardKey>()

    @Before
    fun setup() {
        clickedKeys.clear()
        val context = RuntimeEnvironment.getApplication()
        val languageManager = mock<LanguageManager>()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))
        val themeManager = mock<ThemeManager>()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))
        val cacheMemoryManager = CacheMemoryManager(context)
        val jobField = CacheMemoryManager::class.java.getDeclaredField("memoryMonitoringJob")
        jobField.isAccessible = true
        (jobField.get(cacheMemoryManager) as? Job)?.cancel()
        manager = KeyboardLayoutManager(
            context = context,
            onKeyClick = { key -> clickedKeys.add(key) },
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = mock<CharacterVariationService>(),
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
        manager.updateHapticSettings(enabled = false, amplitude = 0)
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

    @Test
    fun `long press consumed on ACTION_UP - no click fires`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        val durationMs = LongPressDuration.MEDIUM.durationMs
        manager.pressStartTimes[button] = SystemClock.uptimeMillis() - durationMs

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        val consumed = manager.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertTrue("ACTION_UP must be consumed when elapsed >= threshold", consumed)
        assertTrue("onKeyClick must NOT fire when long press consumed", clickedKeys.isEmpty())
    }

    @Test
    fun `long press consumed survives keyboard rebuild - click gate blocks click`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        val characterLongPressFired = getSet("characterLongPressFired")
        val longPressConsumedButtons = getSet("longPressConsumedButtons")
        characterLongPressFired.add(button)
        longPressConsumedButtons.add(button)

        manager.cleanupButton(button)

        assertFalse("cleanupButton must remove from characterLongPressFired", characterLongPressFired.contains(button))
        assertTrue(
            "cleanupButton must NOT remove from longPressConsumedButtons",
            longPressConsumedButtons.contains(button)
        )

        manager.keyClickListener.onClick(button)

        assertTrue(
            "onKeyClick must NOT fire — longPressConsumedButtons gate must block click after rebuild",
            clickedKeys.isEmpty()
        )
        assertFalse(
            "longPressConsumedButtons must be cleared after click gate fires",
            longPressConsumedButtons.contains(button)
        )
    }

    @Test
    fun `short press not consumed - click fires`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        val durationMs = LongPressDuration.MEDIUM.durationMs
        manager.pressStartTimes[button] = SystemClock.uptimeMillis() - (durationMs - 50L)

        val upEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        val consumed = manager.characterLongPressTouchListener.onTouch(button, upEvent)
        upEvent.recycle()

        assertFalse("ACTION_UP must NOT be consumed when elapsed is below threshold", consumed)

        manager.keyClickListener.onClick(button)

        assertTrue("onKeyClick must fire when short press (elapsed below threshold)", clickedKeys.isNotEmpty())
    }

    @Test
    fun `fresh ACTION_DOWN clears stale consumed state`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        val longPressConsumedButtons = getSet("longPressConsumedButtons")
        longPressConsumedButtons.add(button)

        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        manager.characterLongPressTouchListener.onTouch(button, downEvent)
        downEvent.recycle()

        assertFalse(
            "ACTION_DOWN must clear stale longPressConsumedButtons entry",
            longPressConsumedButtons.contains(button)
        )
    }

    @Test
    fun `click listener gate blocks after rebuild even without characterLongPressFired`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)
        val button = buttonFor(key)
        val longPressConsumedButtons = getSet("longPressConsumedButtons")
        longPressConsumedButtons.add(button)

        manager.keyClickListener.onClick(button)

        assertTrue("onKeyClick must NOT fire — longPressConsumedButtons alone must block click", clickedKeys.isEmpty())
        assertFalse(
            "longPressConsumedButtons must be cleared after click gate fires",
            longPressConsumedButtons.contains(button)
        )
    }
}
