package com.urik.keyboard.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardThemeTest {
    @Test
    fun `fromId returns Default theme`() {
        val theme = KeyboardTheme.fromId("default")
        assertEquals(Default, theme)
    }

    @Test
    fun `fromId returns Light theme`() {
        val theme = KeyboardTheme.fromId("light")
        assertEquals(Light, theme)
    }

    @Test
    fun `fromId returns Abyss theme`() {
        val theme = KeyboardTheme.fromId("abyss")
        assertEquals(Abyss, theme)
    }

    @Test
    fun `fromId returns Crimson theme`() {
        val theme = KeyboardTheme.fromId("crimson")
        assertEquals(Crimson, theme)
    }

    @Test
    fun `fromId returns Forest theme`() {
        val theme = KeyboardTheme.fromId("forest")
        assertEquals(Forest, theme)
    }

    @Test
    fun `fromId returns Sunset theme`() {
        val theme = KeyboardTheme.fromId("sunset")
        assertEquals(Sunset, theme)
    }

    @Test
    fun `fromId returns Ocean theme`() {
        val theme = KeyboardTheme.fromId("ocean")
        assertEquals(Ocean, theme)
    }

    @Test
    fun `fromId returns Lavender theme`() {
        val theme = KeyboardTheme.fromId("lavender")
        assertEquals(Lavender, theme)
    }

    @Test
    fun `fromId returns Mocha theme`() {
        val theme = KeyboardTheme.fromId("mocha")
        assertEquals(Mocha, theme)
    }

    @Test
    fun `fromId returns Slate theme`() {
        val theme = KeyboardTheme.fromId("slate")
        assertEquals(Slate, theme)
    }

    @Test
    fun `fromId returns Peach theme`() {
        val theme = KeyboardTheme.fromId("peach")
        assertEquals(Peach, theme)
    }

    @Test
    fun `fromId returns Mint theme`() {
        val theme = KeyboardTheme.fromId("mint")
        assertEquals(Mint, theme)
    }

    @Test
    fun `fromId returns Neon theme`() {
        val theme = KeyboardTheme.fromId("neon")
        assertEquals(Neon, theme)
    }

    @Test
    fun `fromId returns Ember theme`() {
        val theme = KeyboardTheme.fromId("ember")
        assertEquals(Ember, theme)
    }

    @Test
    fun `fromId returns Steel theme`() {
        val theme = KeyboardTheme.fromId("steel")
        assertEquals(Steel, theme)
    }

    @Test
    fun `fromId returns Default for unknown id`() {
        val theme = KeyboardTheme.fromId("unknown_theme")
        assertEquals(Default, theme)
    }

    @Test
    fun `fromId returns Default for empty string`() {
        val theme = KeyboardTheme.fromId("")
        assertEquals(Default, theme)
    }

    @Test
    fun `all returns list with 15 themes`() {
        val themes = KeyboardTheme.all()
        assertEquals(15, themes.size)
    }

    @Test
    fun `all returns list containing Default theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Default))
    }

    @Test
    fun `all returns list containing Light theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Light))
    }

    @Test
    fun `all returns list containing Abyss theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Abyss))
    }

    @Test
    fun `all returns list containing Crimson theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Crimson))
    }

    @Test
    fun `all returns list containing Forest theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Forest))
    }

    @Test
    fun `all returns list containing Sunset theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Sunset))
    }

    @Test
    fun `all returns list containing Ocean theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Ocean))
    }

    @Test
    fun `all returns list containing Lavender theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Lavender))
    }

    @Test
    fun `all returns list containing Mocha theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Mocha))
    }

    @Test
    fun `all returns list containing Slate theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Slate))
    }

    @Test
    fun `all returns list containing Peach theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Peach))
    }

    @Test
    fun `all returns list containing Mint theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Mint))
    }

    @Test
    fun `all returns list containing Neon theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Neon))
    }

    @Test
    fun `all returns list containing Ember theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Ember))
    }

    @Test
    fun `all returns list containing Steel theme`() {
        val themes = KeyboardTheme.all()
        assertTrue(themes.contains(Steel))
    }

    @Test
    fun `all themes have unique ids`() {
        val themes = KeyboardTheme.all()
        val ids = themes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `Default theme is first in list`() {
        val themes = KeyboardTheme.all()
        assertEquals(Default, themes.first())
    }
}
