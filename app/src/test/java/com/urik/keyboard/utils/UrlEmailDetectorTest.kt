package com.urik.keyboard.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlEmailDetectorTest {
    @Test
    fun `detect email with at symbol next`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "example21",
                textBeforeCursor = "Type your email: ",
                nextChar = "@",
            )
        assertTrue("Should detect email when @ is next character", result)
    }

    @Test
    fun `detect email context with at symbol in text`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "example",
                textBeforeCursor = "user@",
                nextChar = ".",
            )
        assertTrue("Should detect email when @ is in text before cursor", result)
    }

    @Test
    fun `detect email with at symbol in current word`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "user@example",
                textBeforeCursor = "",
                nextChar = ".",
            )
        assertTrue("Should detect email when @ is in current word", result)
    }

    @Test
    fun `detect URL with protocol`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "example",
                textBeforeCursor = "Visit https://",
                nextChar = ".",
            )
        assertTrue("Should detect URL when :// is in text before cursor", result)
    }

    @Test
    fun `detect www prefix`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "www",
                textBeforeCursor = "Go to ",
                nextChar = ".",
            )
        assertTrue("Should detect URL when current word is 'www' and next is '.'", result)
    }

    @Test
    fun `detect http protocol being typed`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "http",
                textBeforeCursor = "",
                nextChar = ":",
            )
        assertTrue("Should detect URL when typing http:", result)
    }

    @Test
    fun `detect https protocol being typed`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "https",
                textBeforeCursor = "",
                nextChar = ":",
            )
        assertTrue("Should detect URL when typing https:", result)
    }

    @Test
    fun `detect ftp protocol being typed`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "ftp",
                textBeforeCursor = "",
                nextChar = ":",
            )
        assertTrue("Should detect URL when typing ftp:", result)
    }

    @Test
    fun `detect protocol in combined text`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "google",
                textBeforeCursor = "https://www.",
                nextChar = ".",
            )
        assertTrue("Should detect URL when protocol is in combined text", result)
    }

    @Test
    fun `not detect normal word with space next`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "hello",
                textBeforeCursor = "Say ",
                nextChar = " ",
            )
        assertFalse("Should not detect URL/email for normal word", result)
    }

    @Test
    fun `not detect normal word with period next`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "sentence",
                textBeforeCursor = "End of ",
                nextChar = ".",
            )
        assertFalse("Should not detect URL/email for normal sentence ending", result)
    }

    @Test
    fun `not detect word after email with space`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "thanks",
                textBeforeCursor = "Send to user@example.com ",
                nextChar = " ",
            )
        assertFalse("Should not detect URL/email after completed email with space", result)
    }

    @Test
    fun `detect www dot pattern in text before cursor`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "google",
                textBeforeCursor = "www.",
                nextChar = ".",
            )
        assertTrue("Should detect URL when www. is in text before cursor", result)
    }

    @Test
    fun `not detect normal colon usage`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "time",
                textBeforeCursor = "Current ",
                nextChar = ":",
            )
        assertFalse("Should not detect URL for normal colon usage", result)
    }

    @Test
    fun `detect email domain after at`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "example",
                textBeforeCursor = "test@",
                nextChar = ".",
            )
        assertTrue("Should detect email context for domain part after @", result)
    }

    @Test
    fun `case insensitive www detection`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "WWW",
                textBeforeCursor = "",
                nextChar = ".",
            )
        assertTrue("Should detect www in any case", result)
    }

    @Test
    fun `not detect at symbol in separate sentence`() {
        val result =
            UrlEmailDetector.isUrlOrEmailContext(
                currentWord = "hello",
                textBeforeCursor = "I emailed user@example.com yesterday. Now I say ",
                nextChar = " ",
            )
        assertFalse("Should not detect email context when @ is in different sentence", result)
    }
}
