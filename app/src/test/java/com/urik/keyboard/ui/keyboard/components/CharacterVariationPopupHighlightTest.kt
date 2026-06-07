package com.urik.keyboard.ui.keyboard.components

import android.graphics.drawable.GradientDrawable
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class CharacterVariationPopupHighlightTest {
    private lateinit var popup: CharacterVariationPopup
    private lateinit var theme: KeyboardTheme

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val themeManager: ThemeManager = mock()
        theme = Default
        val themeFlow = MutableStateFlow(theme)
        whenever(themeManager.currentTheme).thenReturn(themeFlow)

        popup = CharacterVariationPopup(context, themeManager)
    }

    @Test
    fun `primary variation is pre-highlighted immediately on construction without drag`() {
        popup.setCharacterVariations("", listOf("ö", "ò", "ó")) {}

        assertEquals("ö", popup.getHighlightedCharacter())
    }

    @Test
    fun `pre-highlighted button has the same highlighted background as setHighlighted applies`() {
        popup.setCharacterVariations("", listOf("ö", "ò", "ó")) {}

        val scrollView = popup.contentView as HorizontalScrollView
        val container = scrollView.getChildAt(0) as LinearLayout
        val highlightedButton = container.getChildAt(0)

        val bg = highlightedButton.background as GradientDrawable
        val color = bg.color?.defaultColor

        assertEquals(theme.colors.statePressed, color)
    }

    @Test
    fun `empty variations list pre-highlights nothing and throws no exception`() {
        popup.setCharacterVariations("", emptyList()) {}

        assertNull(popup.getHighlightedCharacter())
    }

    @Test
    fun `dragging to a different variation overrides the pre-highlight`() {
        popup.setCharacterVariations("", listOf("ö", "ò", "ó")) {}

        popup.setHighlighted("ó")

        assertEquals("ó", popup.getHighlightedCharacter())
    }
}
