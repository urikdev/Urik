package com.urik.keyboard.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ErrorLoggerTest {
    private lateinit var context: Context
    private lateinit var logFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        logFile = File(context.filesDir, "error_log.json")
    }

    @Test
    fun `sanitizeContext strips forbidden keys from log entries`() {
        ErrorLogger.logException(
            component = "TestComponent",
            severity = ErrorLogger.Severity.HIGH,
            exception = RuntimeException("test"),
            context = mapOf(
                "text" to "user typed this secret",
                "operation" to "testOp"
            )
        )
        Thread.sleep(150)

        val content = logFile.readText()
        assertFalse(
            "Forbidden key 'text' value must not appear in log",
            content.contains("user typed this secret")
        )
        assertTrue(
            "Allowed key 'operation' value must appear in log",
            content.contains("testOp")
        )
    }

    @Test
    fun `sanitizeContext strips all forbidden key variants`() {
        ErrorLogger.logException(
            component = "TestComponent",
            severity = ErrorLogger.Severity.HIGH,
            exception = RuntimeException("test2"),
            context = mapOf(
                "word" to "secretWord",
                "input" to "secretInput",
                "query" to "secretQuery",
                "content" to "secretContent",
                "suggestion" to "secretSuggestion",
                "composing" to "secretComposing",
                "typed" to "secretTyped"
            )
        )
        Thread.sleep(150)

        val content = logFile.readText()
        listOf(
            "secretWord",
            "secretInput",
            "secretQuery",
            "secretContent",
            "secretSuggestion",
            "secretComposing",
            "secretTyped"
        ).forEach { secret ->
            assertFalse("Forbidden value '$secret' must not appear in log", content.contains(secret))
        }
    }
}
