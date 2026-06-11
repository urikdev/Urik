package com.urik.keyboard.dictionary

import com.urik.keyboard.service.TestUrikBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrikDictionaryTest {
    private lateinit var dict: UrikDictionary

    @Before
    fun setup() {
        val words = listOf(
            "cant" to 3936L,
            "don't" to 1_500_000L,
            "hello" to 1_000_000L,
            "help" to 500_000L,
            "test" to 300_000L,
            "word" to 200_000L,
            "world" to 800_000L,
            "кот" to 50_000L
        )
        dict = UrikDictionary(buildTestUrik(words).inputStream())
    }

    @Test
    fun `lookup finds exact word`() {
        assertTrue(dict.lookup("hello"))
        assertTrue(dict.lookup("world"))
        assertTrue(dict.lookup("don't"))
    }

    @Test
    fun `lookup is case sensitive`() {
        assertTrue(dict.lookup("hello"))
        assertFalse(dict.lookup("Hello"))
    }

    @Test
    fun `lookup returns false for unknown word`() {
        assertFalse(dict.lookup("xyz"))
        assertFalse(dict.lookup("helo"))
    }

    @Test
    fun `getFrequency returns nonzero for known word`() {
        assertTrue(dict.getFrequency("hello") > 0L)
    }

    @Test
    fun `getFrequency preserves relative ordering`() {
        assertTrue(dict.getFrequency("hello") > dict.getFrequency("help"))
    }

    @Test
    fun `getFrequency returns 0 for unknown word`() {
        assertEquals(0L, dict.getFrequency("xyz"))
    }

    @Test
    fun `getCandidates finds deletion at distance 1`() {
        val candidates = dict.getCandidates("helo", maxEditDistance = 2)
        assertTrue("hello" in candidates.map { it.first })
    }

    @Test
    fun `getCandidates finds substitution at distance 1`() {
        val candidates = dict.getCandidates("hellp", maxEditDistance = 2)
        assertTrue("hello" in candidates.map { it.first })
    }

    @Test
    fun `getCandidates finds insertion at distance 1`() {
        val candidates = dict.getCandidates("helllo", maxEditDistance = 2)
        assertTrue("hello" in candidates.map { it.first })
    }

    @Test
    fun `getCandidates finds contraction at distance 1`() {
        val candidates = dict.getCandidates("dont", maxEditDistance = 2)
        assertTrue("don't" in candidates.map { it.first })
    }

    @Test
    fun `getCandidates returns correct distance`() {
        val candidates = dict.getCandidates("hellp", maxEditDistance = 2)
        val entry = candidates.find { it.first == "hello" }
        assertNotNull(entry)
        assertEquals(1, entry!!.second)
    }

    @Test
    fun `getCandidates sorted by distance ascending`() {
        val candidates = dict.getCandidates("hello", maxEditDistance = 2)
        val distances = candidates.map { it.second }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `getWordsWithPrefix returns words starting with prefix`() {
        val results = dict.getWordsWithPrefix("hel", maxResults = 10)
        val words = results.map { it.first }
        assertTrue("hello" in words)
        assertTrue("help" in words)
    }

    @Test
    fun `getWordsWithPrefix returns non-trivial frequency for common word`() {
        val results = dict.getWordsWithPrefix("hel", maxResults = 10)
        val hello = results.find { it.first == "hello" }
        assertNotNull(hello)
        assertTrue("Frequency should be > 1000, got ${hello!!.second}", hello.second > 1000L)
    }

    @Test
    fun `getWordsWithPrefix unknown prefix returns empty`() {
        assertTrue(dict.getWordsWithPrefix("xyz", maxResults = 10).isEmpty())
    }

    @Test
    fun `unicode word lookup works`() {
        assertTrue(dict.lookup("кот"))
    }

    private fun buildRemovelistDict(removedWords: Set<String>): UrikDictionary {
        val words = listOf(
            "cant" to 3936L,
            "can't" to 1510L,
            "canto" to 100L,
            "hello" to 1_000_000L
        )
        return UrikDictionary(buildTestUrik(words).inputStream(), removedWords)
    }

    @Test
    fun `removed word lookup returns false`() {
        val filtered = buildRemovelistDict(setOf("cant"))
        assertFalse(filtered.lookup("cant"))
        assertTrue(filtered.lookup("can't"))
        assertTrue(filtered.lookup("hello"))
    }

    @Test
    fun `removed word getFrequency returns 0`() {
        val filtered = buildRemovelistDict(setOf("cant"))
        assertEquals(0L, filtered.getFrequency("cant"))
        assertTrue(filtered.getFrequency("can't") > 0L)
    }

    @Test
    fun `removed word excluded from candidates`() {
        val filtered = buildRemovelistDict(setOf("cant"))
        val candidates = filtered.getCandidates("cant", maxEditDistance = 2).map { it.first }
        assertFalse("cant" in candidates)
        assertTrue("can't" in candidates)
    }

    @Test
    fun `removed word excluded from prefix completions`() {
        val filtered = buildRemovelistDict(setOf("cant"))
        val words = filtered.getWordsWithPrefix("can", maxResults = 10).map { it.first }
        assertFalse("cant" in words)
        assertTrue("can't" in words)
        assertTrue("canto" in words)
    }

    @Test
    fun `removal matches case insensitively`() {
        val cased = UrikDictionary(
            buildTestUrik(listOf("Cant" to 100L)).inputStream(),
            setOf("cant")
        )
        assertFalse(cased.lookup("Cant"))
    }

    @Test
    fun `empty removelist leaves dictionary untouched`() {
        val unfiltered = buildRemovelistDict(emptySet())
        assertTrue(unfiltered.lookup("cant"))
    }

    @Test
    fun `getCandidates unicode substitution`() {
        val candidates = dict.getCandidates("кат", maxEditDistance = 1)
        assertTrue("кот" in candidates.map { it.first })
    }
}

private fun buildTestUrik(wordsAndFreqs: List<Pair<String, Long>>): ByteArray = TestUrikBuilder.buildUrik(wordsAndFreqs)
