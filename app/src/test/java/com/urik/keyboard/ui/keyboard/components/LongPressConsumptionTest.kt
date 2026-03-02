package com.urik.keyboard.ui.keyboard.components

import android.widget.Button
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class LongPressConsumptionTest {
    private lateinit var characterLongPressFired: MutableSet<Button>
    private lateinit var longPressConsumedButtons: MutableSet<Button>
    private lateinit var button: Button

    @Before
    fun setup() {
        characterLongPressFired = ConcurrentHashMap.newKeySet()
        longPressConsumedButtons = ConcurrentHashMap.newKeySet()
        button = Button(RuntimeEnvironment.getApplication())
    }

    private fun simulateLongPressFired() {
        characterLongPressFired.add(button)
        longPressConsumedButtons.add(button)
    }

    private fun simulateCleanupButton() {
        characterLongPressFired.remove(button)
    }

    private fun resolveConsumed(): Boolean =
        characterLongPressFired.remove(button) || longPressConsumedButtons.contains(button)

    @Test
    fun `long press consumed on normal ACTION_UP`() {
        simulateLongPressFired()

        assertTrue(resolveConsumed())
    }

    @Test
    fun `long press consumed survives keyboard rebuild`() {
        simulateLongPressFired()
        simulateCleanupButton()

        assertFalse(characterLongPressFired.contains(button))
        assertTrue(longPressConsumedButtons.contains(button))
        assertTrue(resolveConsumed())
    }

    @Test
    fun `fresh ACTION_DOWN clears stale consumed state`() {
        simulateLongPressFired()
        simulateCleanupButton()

        characterLongPressFired.remove(button)
        longPressConsumedButtons.remove(button)

        assertFalse(resolveConsumed())
    }

    @Test
    fun `click listener gate blocks after rebuild`() {
        simulateLongPressFired()
        simulateCleanupButton()

        val clickBlocked = longPressConsumedButtons.remove(button)
        assertTrue(clickBlocked)
    }

    @Test
    fun `rapid release without long press fires click`() {
        assertFalse(resolveConsumed())
    }

    @Test
    fun `shifted long press with rebuild - touch consumes and click gate remains armed`() {
        simulateLongPressFired()
        simulateCleanupButton()

        val touchConsumed = resolveConsumed()
        assertTrue(touchConsumed)

        assertTrue(longPressConsumedButtons.contains(button))
    }

    @Test
    fun `click gate fires as fallback when touch listener missed`() {
        simulateLongPressFired()
        simulateCleanupButton()

        val touchMissed = characterLongPressFired.remove(button)
        assertFalse(touchMissed)

        val clickBlocked = longPressConsumedButtons.remove(button)
        assertTrue(clickBlocked)
    }
}
