package com.urik.keyboard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentHasherTest {
    @Test
    fun `empty string produces known SHA-256 vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHasher.sha256Hex("")
        )
    }

    @Test
    fun `abc produces known SHA-256 vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHasher.sha256Hex("abc")
        )
    }

    @Test
    fun `hello produces known SHA-256 vector confirming UTF-8 encoding`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ContentHasher.sha256Hex("hello")
        )
    }

    @Test
    fun `output is always 64 lowercase hex characters`() {
        val inputs = listOf("", "a", "abc", "hello world", "1234567890")
        for (input in inputs) {
            val hash = ContentHasher.sha256Hex(input)
            assertEquals("Hash for '$input' must be 64 chars", 64, hash.length)
            assertTrue(
                "Hash for '$input' must be lowercase hex",
                hash.matches(Regex("^[0-9a-f]{64}$"))
            )
        }
    }

    @Test
    fun `same input produces same output deterministically`() {
        val input = "determinism test"
        assertEquals(ContentHasher.sha256Hex(input), ContentHasher.sha256Hex(input))
    }

    @Test
    fun `different inputs produce different outputs`() {
        assertNotEquals(ContentHasher.sha256Hex("a"), ContentHasher.sha256Hex("b"))
    }
}
