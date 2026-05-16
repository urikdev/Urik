package com.urik.keyboard.service

import android.graphics.PointF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FatFingerExpanderTest {
    private lateinit var expander: FatFingerExpander

    private val qwertyPositions: Map<Char, PointF> by lazy {
        linkedMapOf(
            'q' to PointF(50f, 40f), 'w' to PointF(150f, 40f), 'e' to PointF(250f, 40f),
            'r' to PointF(350f, 40f), 't' to PointF(450f, 40f), 'y' to PointF(550f, 40f),
            'u' to PointF(650f, 40f), 'i' to PointF(750f, 40f), 'o' to PointF(850f, 40f),
            'p' to PointF(950f, 40f),
            'a' to PointF(100f, 120f), 's' to PointF(200f, 120f), 'd' to PointF(300f, 120f),
            'f' to PointF(400f, 120f), 'g' to PointF(500f, 120f), 'h' to PointF(600f, 120f),
            'j' to PointF(700f, 120f), 'k' to PointF(800f, 120f), 'l' to PointF(900f, 120f),
            'z' to PointF(150f, 200f), 'x' to PointF(250f, 200f), 'c' to PointF(350f, 200f),
            'v' to PointF(450f, 200f), 'b' to PointF(550f, 200f), 'n' to PointF(650f, 200f),
            'm' to PointF(750f, 200f)
        )
    }

    @Before
    fun setup() {
        expander = FatFingerExpander()
    }

    @Test
    fun `buildAdjacentKeyMap includes g as adjacent to b`() {
        val map = expander.buildAdjacentKeyMap(qwertyPositions, 100.0)
        assertTrue(map['b']?.contains('g') == true)
    }

    @Test
    fun `buildAdjacentKeyMap excludes distant keys`() {
        val map = expander.buildAdjacentKeyMap(qwertyPositions, 100.0)
        assertFalse(map['q']?.contains('p') == true)
    }

    @Test
    fun `buildAdjacentKeyMap excludes self`() {
        val map = expander.buildAdjacentKeyMap(qwertyPositions, 100.0)
        for (c in qwertyPositions.keys) {
            assertFalse(map[c]?.contains(c) == true)
        }
    }

    @Test
    fun `generateVariants for bave includes gave`() {
        val map = expander.buildAdjacentKeyMap(qwertyPositions, 100.0)
        val variants = expander.generateVariants("bave", map)
        assertTrue(variants.contains("gave"))
    }

    @Test
    fun `generateVariants does not include original word`() {
        val map = expander.buildAdjacentKeyMap(qwertyPositions, 100.0)
        val variants = expander.generateVariants("bave", map)
        assertFalse(variants.contains("bave"))
    }

    @Test
    fun `generateVariants with empty adjacency returns empty list`() {
        val variants = expander.generateVariants("hello", emptyMap())
        assertTrue(variants.isEmpty())
    }

    @Test
    fun `generateVariants substitutes each position independently`() {
        val adjacentKeyMap = mapOf(
            'a' to setOf('s'),
            'b' to setOf('g', 'v')
        )
        val variants = expander.generateVariants("ab", adjacentKeyMap)
        assertTrue(variants.contains("sb"))
        assertTrue(variants.contains("ag"))
        assertTrue(variants.contains("av"))
    }
}
