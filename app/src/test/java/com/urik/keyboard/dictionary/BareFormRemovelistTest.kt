package com.urik.keyboard.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test

class BareFormRemovelistTest {
    @Test
    fun `english removelist contains contraction bare forms`() {
        val en = BareFormRemovelist.forLanguage("en")
        assertTrue("im" in en)
        assertTrue("ive" in en)
        assertTrue("dont" in en)
        assertTrue("theyd" in en)
    }

    @Test
    fun `unknown language returns empty set`() {
        assertTrue(BareFormRemovelist.forLanguage("xx").isEmpty())
    }

    @Test
    fun `all entries are lowercase`() {
        for (lang in listOf("en", "fr", "de", "el", "cs", "nl", "it")) {
            for (word in BareFormRemovelist.forLanguage(lang)) {
                assertTrue("$lang entry not lowercase: $word", word == word.lowercase())
            }
        }
    }
}
