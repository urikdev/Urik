package com.urik.keyboard.service

import android.content.res.Configuration
import android.graphics.Rect
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyboardModeManagerTest {
    private lateinit var manager: KeyboardModeManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val settingsRepository: SettingsRepository = mock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        manager = KeyboardModeManager(context, settingsRepository, scope)
    }

    private fun phonePortrait() = PostureInfo(
        sizeClass = DeviceSizeClass.COMPACT,
        posture = DevicePosture.NORMAL,
        screenWidthPx = 1080,
        screenHeightPx = 2400,
        isTablet = false,
        orientation = Configuration.ORIENTATION_PORTRAIT
    )

    private fun phoneLandscape() = PostureInfo(
        sizeClass = DeviceSizeClass.COMPACT,
        posture = DevicePosture.NORMAL,
        screenWidthPx = 2400,
        screenHeightPx = 1080,
        isTablet = false,
        orientation = Configuration.ORIENTATION_LANDSCAPE
    )

    private fun tabletPortrait() = PostureInfo(
        sizeClass = DeviceSizeClass.EXPANDED,
        posture = DevicePosture.NORMAL,
        screenWidthPx = 1600,
        screenHeightPx = 2560,
        isTablet = true,
        orientation = Configuration.ORIENTATION_PORTRAIT
    )

    private fun tabletLandscape() = PostureInfo(
        sizeClass = DeviceSizeClass.EXPANDED,
        posture = DevicePosture.NORMAL,
        screenWidthPx = 2560,
        screenHeightPx = 1600,
        isTablet = true,
        orientation = Configuration.ORIENTATION_LANDSCAPE
    )

    private fun foldableHalfOpened() = PostureInfo(
        sizeClass = DeviceSizeClass.MEDIUM,
        posture = DevicePosture.HALF_OPENED,
        hingeBounds = Rect(540, 0, 560, 2400),
        screenWidthPx = 1100,
        screenHeightPx = 2400,
        isTablet = false,
        orientation = Configuration.ORIENTATION_PORTRAIT
    )

    private fun foldableFlat() = PostureInfo(
        sizeClass = DeviceSizeClass.MEDIUM,
        posture = DevicePosture.FLAT,
        hingeBounds = Rect(540, 0, 560, 2400),
        screenWidthPx = 1100,
        screenHeightPx = 2400,
        isTablet = false,
        orientation = Configuration.ORIENTATION_PORTRAIT
    )

    private fun defaultSettings() = KeyboardSettings()

    private fun settingsWithOneHandedLeft() = KeyboardSettings(
        oneHandedModeEnabled = true,
        keyboardDisplayMode = KeyboardDisplayMode.ONE_HANDED_LEFT
    )

    private fun settingsWithOneHandedRight() = KeyboardSettings(
        oneHandedModeEnabled = true,
        keyboardDisplayMode = KeyboardDisplayMode.ONE_HANDED_RIGHT
    )

    private fun settingsWithAdaptiveDisabled() = KeyboardSettings(
        adaptiveKeyboardModesEnabled = false
    )

    @Test
    fun `phone portrait produces standard mode`() {
        val config = manager.determineMode(defaultSettings(), phonePortrait())

        assertEquals(KeyboardDisplayMode.STANDARD, config.mode)
        assertNotNull(config.adaptiveDimensions)
    }

    @Test
    fun `phone landscape produces standard mode not split`() {
        val config = manager.determineMode(defaultSettings(), phoneLandscape())

        assertEquals(KeyboardDisplayMode.STANDARD, config.mode)
    }

    @Test
    fun `phone landscape dimensions have reduced key height`() {
        val portrait = manager.determineMode(defaultSettings(), phonePortrait())
        val landscape = manager.determineMode(defaultSettings(), phoneLandscape())

        assertTrue(
            landscape.adaptiveDimensions!!.keyHeightPx < portrait.adaptiveDimensions!!.keyHeightPx
        )
    }

    @Test
    fun `tablet portrait auto-splits when adaptive enabled`() {
        val config = manager.determineMode(defaultSettings(), tabletPortrait())

        assertEquals(KeyboardDisplayMode.SPLIT, config.mode)
        assertTrue(config.splitGapPx > 0)
    }

    @Test
    fun `tablet does not auto-split when adaptive disabled`() {
        val config = manager.determineMode(settingsWithAdaptiveDisabled(), tabletPortrait())

        assertEquals(KeyboardDisplayMode.STANDARD, config.mode)
    }

    @Test
    fun `tablet landscape auto-splits`() {
        val config = manager.determineMode(defaultSettings(), tabletLandscape())

        assertEquals(KeyboardDisplayMode.SPLIT, config.mode)
    }

    @Test
    fun `tablet mode config uses full screen width`() {
        val config = manager.determineMode(defaultSettings(), tabletPortrait())
        val dims = config.adaptiveDimensions!!

        assertEquals(1600, dims.maxKeyboardWidthPx)
    }

    @Test
    fun `foldable half-opened auto-splits`() {
        val config = manager.determineMode(defaultSettings(), foldableHalfOpened())

        assertEquals(KeyboardDisplayMode.SPLIT, config.mode)
        assertTrue(config.splitGapPx > 0)
    }

    @Test
    fun `foldable flat auto-splits`() {
        val config = manager.determineMode(defaultSettings(), foldableFlat())

        assertEquals(KeyboardDisplayMode.SPLIT, config.mode)
    }

    @Test
    fun `foldable split gap comes from hinge bounds`() {
        val config = manager.determineMode(defaultSettings(), foldableHalfOpened())

        assertEquals(20, config.adaptiveDimensions!!.splitGapPx)
    }

    @Test
    fun `one-handed left overrides adaptive`() {
        val config = manager.determineMode(settingsWithOneHandedLeft(), tabletPortrait())

        assertEquals(KeyboardDisplayMode.ONE_HANDED_LEFT, config.mode)
        assertNotNull(config.adaptiveDimensions)
    }

    @Test
    fun `one-handed right overrides adaptive`() {
        val config = manager.determineMode(settingsWithOneHandedRight(), phonePortrait())

        assertEquals(KeyboardDisplayMode.ONE_HANDED_RIGHT, config.mode)
        assertNotNull(config.adaptiveDimensions)
    }

    @Test
    fun `all mode configs carry adaptive dimensions`() {
        val formFactors = listOf(
            phonePortrait(),
            phoneLandscape(),
            tabletPortrait(),
            tabletLandscape(),
            foldableHalfOpened()
        )
        val settingsVariants = listOf(
            defaultSettings(),
            settingsWithOneHandedLeft(),
            settingsWithAdaptiveDisabled()
        )

        for (posture in formFactors) {
            for (settings in settingsVariants) {
                val config = manager.determineMode(settings, posture)
                assertNotNull(
                    "Config for ${posture.sizeClass}/${posture.orientation} must have dimensions",
                    config.adaptiveDimensions
                )
            }
        }
    }
}
