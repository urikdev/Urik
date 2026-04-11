package com.urik.keyboard.ui.keyboard.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class BackspaceControllerTest {
    private val backspaceEvents = mutableListOf<Unit>()
    private val accelerationStates = mutableListOf<Boolean>()
    private val vibrationEffects = mutableListOf<android.os.VibrationEffect>()
    private var vibrationCancelled = false
    private var hapticEnabled = true
    private var hapticAmplitude = 170
    private var supportsAmplitude = true
    private lateinit var scope: CoroutineScope
    private lateinit var controller: BackspaceController

    @Before
    fun setup() {
        backspaceEvents.clear()
        accelerationStates.clear()
        vibrationEffects.clear()
        vibrationCancelled = false
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        controller = BackspaceController(
            onBackspaceKey = { backspaceEvents.add(Unit) },
            onAcceleratedDeletionChanged = { accelerationStates.add(it) },
            vibrateEffect = { vibrationEffects.add(it) },
            cancelVibration = { vibrationCancelled = true },
            getHapticEnabled = { hapticEnabled },
            getHapticAmplitude = { hapticAmplitude },
            getSupportsAmplitudeControl = { supportsAmplitude },
            getBackgroundScope = { scope }
        )
    }

    @After
    fun teardown() {
        controller.cleanup()
    }

    @Test
    fun `start signals accelerated deletion enabled`() {
        controller.start()
        assertTrue(accelerationStates.contains(true))
    }

    @Test
    fun `stop signals accelerated deletion disabled`() {
        controller.start()
        controller.stop()
        assertTrue(accelerationStates.contains(false))
    }

    @Test
    fun `start fires backspace events after delay`() {
        controller.start()
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("Expected at least one backspace event", backspaceEvents.isNotEmpty())
    }

    @Test
    fun `stop cancels pending backspace events`() {
        controller.start()
        controller.stop()
        val countAfterStop = backspaceEvents.size
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("No new events after stop", countAfterStop, backspaceEvents.size)
    }

    @Test
    fun `calculateInterval returns start speed at time zero`() {
        assertEquals(100L, controller.calculateInterval(0))
    }

    @Test
    fun `calculateInterval returns end speed after full acceleration`() {
        assertEquals(15L, controller.calculateInterval(2000))
        assertEquals(15L, controller.calculateInterval(5000))
    }

    @Test
    fun `calculateInterval decreases monotonically`() {
        var previous = controller.calculateInterval(0)
        for (elapsed in 100L..2000L step 100) {
            val current = controller.calculateInterval(elapsed)
            assertTrue(
                "Interval should decrease: $previous -> $current at ${elapsed}ms",
                current <= previous
            )
            previous = current
        }
    }

    @Test
    fun `cleanup stops all activity without crash`() {
        controller.start()
        controller.cleanup()
        // Verify no crash and acceleration is disabled
        assertTrue(!accelerationStates.last())
    }

    @Test
    fun `start after stop works correctly`() {
        controller.start()
        controller.stop()
        accelerationStates.clear()
        controller.start()
        assertTrue(accelerationStates.contains(true))
    }

    @Test
    fun `no vibration when haptics disabled`() {
        hapticEnabled = false
        controller.start()
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("No vibration effects when haptics disabled", vibrationEffects.isEmpty())
    }

    @Test
    fun `no vibration when amplitude is zero`() {
        hapticAmplitude = 0
        controller.start()
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("No vibration effects when amplitude is 0", vibrationEffects.isEmpty())
    }

    @Test
    fun `stop called from onBackspaceKey callback does not orphan the runnable`() {
        var stopIssuedFromCallback = false
        lateinit var selfStoppingController: BackspaceController
        val events = mutableListOf<Unit>()

        selfStoppingController = BackspaceController(
            onBackspaceKey = {
                events.add(Unit)
                if (!stopIssuedFromCallback) {
                    stopIssuedFromCallback = true
                    selfStoppingController.stop()
                }
            },
            onAcceleratedDeletionChanged = {},
            vibrateEffect = {},
            cancelVibration = {},
            getHapticEnabled = { false },
            getHapticAmplitude = { 0 },
            getSupportsAmplitudeControl = { false },
            getBackgroundScope = { scope }
        )

        selfStoppingController.start()
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals(
            "Orphaned runnable must not fire after stop() was called from within onBackspaceKey callback",
            1,
            events.size
        )
    }

    @Test
    fun `ACTION_UP stop after in-callback stop does not re-enable repeat`() {
        var stopIssuedFromCallback = false
        lateinit var selfStoppingController: BackspaceController
        val events = mutableListOf<Unit>()

        selfStoppingController = BackspaceController(
            onBackspaceKey = {
                events.add(Unit)
                if (!stopIssuedFromCallback) {
                    stopIssuedFromCallback = true
                    selfStoppingController.stop()
                }
            },
            onAcceleratedDeletionChanged = {},
            vibrateEffect = {},
            cancelVibration = {},
            getHapticEnabled = { false },
            getHapticAmplitude = { 0 },
            getSupportsAmplitudeControl = { false },
            getBackgroundScope = { scope }
        )

        selfStoppingController.start()
        ShadowLooper.idleMainLooper(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        selfStoppingController.stop()
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals(
            "No further backspace events after ACTION_UP stop following in-callback stop",
            1,
            events.size
        )
    }
}
