package com.urik.keyboard.service

import android.content.res.Configuration
import android.graphics.Rect
import com.urik.keyboard.settings.KeySize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdaptiveDimensionsTest {
    private val phoneDensity = 2.75f
    private val tabletDensity = 2.0f

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

    @Test
    fun `phone portrait uses baseline dimensions`() {
        val dims = AdaptiveDimensions.compute(phonePortrait(), KeySize.MEDIUM, phoneDensity)

        assertEquals(1080, dims.maxKeyboardWidthPx)
        assertEquals((40f * phoneDensity).toInt(), dims.keyHeightPx)
        assertEquals(0, dims.splitGapPx)
        assertEquals(35f, dims.swipeActivationDp)
        assertEquals(20f, dims.gestureThresholdDp)
    }

    @Test
    fun `phone landscape reduces key height`() {
        val portrait = AdaptiveDimensions.compute(phonePortrait(), KeySize.MEDIUM, phoneDensity)
        val landscape = AdaptiveDimensions.compute(phoneLandscape(), KeySize.MEDIUM, phoneDensity)

        assertTrue(
            "Landscape key height (${landscape.keyHeightPx}) should be less than portrait (${portrait.keyHeightPx})",
            landscape.keyHeightPx < portrait.keyHeightPx
        )
        assertEquals(30f, landscape.swipeActivationDp)
        assertEquals(16f, landscape.gestureThresholdDp)
    }

    @Test
    fun `phone landscape reduces suggestion text range`() {
        val dims = AdaptiveDimensions.compute(phoneLandscape(), KeySize.MEDIUM, phoneDensity)

        assertEquals(13f, dims.suggestionTextMinSp)
        assertEquals(15f, dims.suggestionTextMaxSp)
    }

    @Test
    fun `phone landscape reduces key margins`() {
        val portrait = AdaptiveDimensions.compute(phonePortrait(), KeySize.MEDIUM, phoneDensity)
        val landscape = AdaptiveDimensions.compute(phoneLandscape(), KeySize.MEDIUM, phoneDensity)

        assertTrue(
            "Landscape key margin (${landscape.keyMarginHorizontalPx}) should be " +
                "less than portrait (${portrait.keyMarginHorizontalPx})",
            landscape.keyMarginHorizontalPx < portrait.keyMarginHorizontalPx
        )
    }

    @Test
    fun `tablet portrait uses full screen width`() {
        val dims = AdaptiveDimensions.compute(tabletPortrait(), KeySize.MEDIUM, tabletDensity)

        assertEquals(1600, dims.maxKeyboardWidthPx)
    }

    @Test
    fun `tablet landscape uses full screen width`() {
        val dims = AdaptiveDimensions.compute(tabletLandscape(), KeySize.MEDIUM, tabletDensity)

        assertEquals(2560, dims.maxKeyboardWidthPx)
    }

    @Test
    fun `tablet increases key height over phone`() {
        val phone = AdaptiveDimensions.compute(phonePortrait(), KeySize.MEDIUM, phoneDensity)
        val tablet = AdaptiveDimensions.compute(tabletPortrait(), KeySize.MEDIUM, tabletDensity)

        val phoneHeightDp = phone.keyHeightPx / phoneDensity
        val tabletHeightDp = tablet.keyHeightPx / tabletDensity

        assertTrue(
            "Tablet key height DP ($tabletHeightDp) should be greater than phone ($phoneHeightDp)",
            tabletHeightDp > phoneHeightDp
        )
    }

    @Test
    fun `tablet increases swipe and gesture thresholds`() {
        val dims = AdaptiveDimensions.compute(tabletPortrait(), KeySize.MEDIUM, tabletDensity)

        assertEquals(40f, dims.swipeActivationDp)
        assertEquals(24f, dims.gestureThresholdDp)
    }

    @Test
    fun `tablet increases suggestion text range`() {
        val dims = AdaptiveDimensions.compute(tabletPortrait(), KeySize.MEDIUM, tabletDensity)

        assertEquals(17f, dims.suggestionTextMinSp)
        assertEquals(21f, dims.suggestionTextMaxSp)
    }

    @Test
    fun `tablet gets split gap`() {
        val dims = AdaptiveDimensions.compute(tabletPortrait(), KeySize.MEDIUM, tabletDensity)

        assertTrue(dims.splitGapPx > 0)
    }

    @Test
    fun `foldable half-opened gets split gap from hinge bounds`() {
        val dims = AdaptiveDimensions.compute(foldableHalfOpened(), KeySize.MEDIUM, phoneDensity)

        assertEquals(20, dims.splitGapPx)
    }

    @Test
    fun `foldable half-opened does not cap max width`() {
        val dims = AdaptiveDimensions.compute(foldableHalfOpened(), KeySize.MEDIUM, phoneDensity)

        assertEquals(1100, dims.maxKeyboardWidthPx)
    }

    @Test
    fun `key size setting scales key height`() {
        val small = AdaptiveDimensions.compute(phonePortrait(), KeySize.SMALL, phoneDensity)
        val medium = AdaptiveDimensions.compute(phonePortrait(), KeySize.MEDIUM, phoneDensity)
        val large = AdaptiveDimensions.compute(phonePortrait(), KeySize.LARGE, phoneDensity)
        val extraLarge = AdaptiveDimensions.compute(phonePortrait(), KeySize.EXTRA_LARGE, phoneDensity)

        assertTrue(small.keyHeightPx < medium.keyHeightPx)
        assertTrue(medium.keyHeightPx < large.keyHeightPx)
        assertTrue(large.keyHeightPx < extraLarge.keyHeightPx)
    }

    @Test
    fun `key size setting scales touch targets`() {
        val small = AdaptiveDimensions.compute(phonePortrait(), KeySize.SMALL, phoneDensity)
        val large = AdaptiveDimensions.compute(phonePortrait(), KeySize.LARGE, phoneDensity)

        assertTrue(large.minimumTouchTargetPx > small.minimumTouchTargetPx)
    }

    @Test
    fun `all dimensions are positive across all form factors and key sizes`() {
        val formFactors = listOf(
            phonePortrait() to phoneDensity,
            phoneLandscape() to phoneDensity,
            tabletPortrait() to tabletDensity,
            tabletLandscape() to tabletDensity,
            foldableHalfOpened() to phoneDensity
        )

        for ((posture, density) in formFactors) {
            for (keySize in KeySize.entries) {
                val dims = AdaptiveDimensions.compute(posture, keySize, density)

                assertTrue("maxKeyboardWidthPx must be > 0 for $posture/$keySize", dims.maxKeyboardWidthPx > 0)
                assertTrue("keyHeightPx must be > 0 for $posture/$keySize", dims.keyHeightPx > 0)
                assertTrue("minimumTouchTargetPx must be > 0 for $posture/$keySize", dims.minimumTouchTargetPx > 0)
                assertTrue("keyMarginHorizontalPx must be >= 0 for $posture/$keySize", dims.keyMarginHorizontalPx >= 0)
                assertTrue("keyMarginVerticalPx must be >= 0 for $posture/$keySize", dims.keyMarginVerticalPx >= 0)
                assertTrue("swipeActivationDp must be > 0 for $posture/$keySize", dims.swipeActivationDp > 0f)
                assertTrue("gestureThresholdDp must be > 0 for $posture/$keySize", dims.gestureThresholdDp > 0f)
                assertTrue("suggestionTextMinSp must be > 0 for $posture/$keySize", dims.suggestionTextMinSp > 0f)
                assertTrue(
                    "suggestionTextMaxSp must be > min for $posture/$keySize",
                    dims.suggestionTextMaxSp > dims.suggestionTextMinSp
                )
            }
        }
    }

    @Test
    fun `max width never exceeds screen width`() {
        val formFactors = listOf(
            phonePortrait() to phoneDensity,
            phoneLandscape() to phoneDensity,
            tabletPortrait() to tabletDensity,
            tabletLandscape() to tabletDensity,
            foldableHalfOpened() to phoneDensity
        )

        for ((posture, density) in formFactors) {
            val dims = AdaptiveDimensions.compute(posture, KeySize.MEDIUM, density)
            assertTrue(
                "maxKeyboardWidthPx (${dims.maxKeyboardWidthPx}) must not " +
                    "exceed screenWidthPx (${posture.screenWidthPx})",
                dims.maxKeyboardWidthPx <= posture.screenWidthPx
            )
        }
    }

    @Test
    fun `tablet landscape key height is between phone landscape and tablet portrait`() {
        val phoneLand = AdaptiveDimensions.compute(phoneLandscape(), KeySize.MEDIUM, phoneDensity)
        val tabletPort = AdaptiveDimensions.compute(tabletPortrait(), KeySize.MEDIUM, tabletDensity)
        val tabletLand = AdaptiveDimensions.compute(tabletLandscape(), KeySize.MEDIUM, tabletDensity)

        val phoneLandDp = phoneLand.keyHeightPx / phoneDensity
        val tabletPortDp = tabletPort.keyHeightPx / tabletDensity
        val tabletLandDp = tabletLand.keyHeightPx / tabletDensity

        assertTrue(
            "Tablet landscape key height DP ($tabletLandDp) should be > phone landscape ($phoneLandDp)",
            tabletLandDp > phoneLandDp
        )
        assertTrue(
            "Tablet landscape key height DP ($tabletLandDp) should be < tablet portrait ($tabletPortDp)",
            tabletLandDp < tabletPortDp
        )
    }
}
