package com.urik.keyboard.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevenshteinAutomatonTest {
    @Test
    fun `exact match has distance 0`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        assertEquals(0, auto.accept("hello"))
    }

    @Test
    fun `deletion gives distance 1`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        assertEquals(1, auto.accept("helo"))
    }

    @Test
    fun `substitution gives distance 1`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        assertEquals(1, auto.accept("hellp"))
        assertEquals(1, auto.accept("xello"))
    }

    @Test
    fun `insertion gives distance 1`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        assertEquals(1, auto.accept("helllo"))
    }

    @Test
    fun `transposition is distance 2 in standard Levenshtein`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        assertEquals(2, auto.accept("hlelo"))
    }

    @Test
    fun `distance 2 accepted`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        assertEquals(2, auto.accept("hlo")) // delete e and one l = dist 2
    }

    @Test
    fun `beyond max distance returns -1`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        assertEquals(-1, auto.accept("xxxxx"))
    }

    @Test
    fun `empty input matches empty word at distance 0`() {
        val auto = LevenshteinAutomaton("", maxDistance = 2)
        assertEquals(0, auto.accept(""))
    }

    @Test
    fun `unicode substitution works`() {
        val auto = LevenshteinAutomaton("кот", maxDistance = 1)
        assertEquals(1, auto.accept("кат"))
    }

    @Test
    fun `canReachFinal returns false when all paths exceed maxDistance`() {
        val auto = LevenshteinAutomaton("ab", maxDistance = 1)
        val state = auto.start()
        val afterX = auto.step(state, 'x')
        val afterXY = auto.step(afterX, 'y')
        val afterXYZ = auto.step(afterXY, 'z')
        assertFalse(auto.canReachFinal(afterXYZ))
    }

    @Test
    fun `canReachFinal returns true for partial match within distance`() {
        val auto = LevenshteinAutomaton("hello", maxDistance = 2)
        val state = auto.start()
        val afterH = auto.step(state, 'h')
        assertTrue(auto.canReachFinal(afterH))
    }
}
