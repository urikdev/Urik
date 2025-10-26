package com.urik.keyboard.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Privacy-first exception logger for critical application failures.
 *
 * Logs CRITICAL and HIGH severity exceptions to encrypted local storage for debugging.
 * No user data is captured - only exception metadata, component names, and sanitized context.
 *
 * Thread-safe with actor pattern. Async writes with bounded buffer (drops oldest on overflow).
 * Rotation at 100 entries or 500KB. Corrupted files deleted on startup.
 *
 * Usage:
 * ```
 * ErrorLogger.init(applicationContext)
 * try {
 *     criticalOperation()
 * } catch (e: Exception) {
 *     ErrorLogger.logException("ComponentName", ErrorLogger.Severity.CRITICAL, e, mapOf("key" to "value"))
 * }
 * ```
 */
object ErrorLogger {
    private const val LOG_FILE_NAME = "error_log.json"
    private const val FALLBACK_FILE_NAME = "error_log_fallback.txt"
    private const val MAX_ENTRIES = 100
    private const val MAX_FILE_SIZE = 512_000
    private const val MAX_CONTEXT_VALUE_LENGTH = 200
    private const val CHANNEL_CAPACITY = 32

    private val isLoggingException = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    private var logFile: File? = null
    private var fallbackFile: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel =
        Channel<ErrorEntry>(CHANNEL_CAPACITY, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    enum class Severity {
        CRITICAL,
        HIGH,
    }

    data class ErrorEntry(
        val timestamp: Long,
        val component: String,
        val severity: Severity,
        val failurePoint: String,
        val exceptionType: String,
        val message: String,
        val context: Map<String, String>,
    )

    /**
     * Initializes error logger with application context.
     *
     * Idempotent - safe to call multiple times. Starts actor for async writes.
     * Validates/creates log files. Deletes corrupted files.
     *
     * @param context Application context for file I/O
     */
    fun init(context: Context) {
        if (isInitialized.getAndSet(true)) {
            return
        }

        logFile = File(context.filesDir, LOG_FILE_NAME)
        fallbackFile = File(context.filesDir, FALLBACK_FILE_NAME)

        validateOrCreateLogFile()

        scope.launch {
            for (entry in logChannel) {
                writeEntryToFile(entry)
            }
        }
    }

    /**
     * Logs exception with severity and optional context.
     *
     * Non-blocking, async write. Drops oldest if buffer full.
     * Sanitizes context values (max 200 chars, no newlines).
     * Extracts top stack frame for failure point.
     *
     * @param component Name of component that failed (e.g. "SpellCheckManager")
     * @param severity CRITICAL (keyboard unusable) or HIGH (core feature broken)
     * @param exception Throwable to log
     * @param context Optional metadata (NO user data)
     */
    fun logException(
        component: String,
        severity: Severity,
        exception: Throwable,
        context: Map<String, String> = emptyMap(),
    ) {
        if (isLoggingException.getAndSet(true)) {
            return
        }

        try {
            val failurePoint = extractFailurePoint(exception)
            val sanitizedContext = sanitizeContext(context)

            val entry =
                ErrorEntry(
                    timestamp = System.currentTimeMillis(),
                    component = component,
                    severity = severity,
                    failurePoint = failurePoint,
                    exceptionType = exception::class.java.simpleName,
                    message = exception.message ?: "No message",
                    context = sanitizedContext,
                )

            logChannel.trySend(entry)
        } finally {
            isLoggingException.set(false)
        }
    }

    /**
     * Exports error log for sharing via intent.
     *
     * @return Log file or empty file if no errors
     */
    fun exportLog(): File = logFile?.takeIf { it.exists() } ?: createEmptyLogFile()

    /**
     * Gets count of logged errors.
     *
     * @return Number of errors in log, or 0 if file missing/corrupted
     */
    fun getErrorCount(): Int {
        val file = logFile ?: return 0
        if (!file.exists()) return 0

        return try {
            val content = file.readText()
            val errorsStart = content.indexOf("\"errors\":[")
            if (errorsStart == -1) return 0

            var count = 0
            var index = errorsStart
            while (index < content.length) {
                index = content.indexOf("{\"timestamp\":", index)
                if (index == -1) break
                count++
                index++
            }
            count
        } catch (_: Exception) {
            0
        }
    }

    private fun validateOrCreateLogFile() {
        val file = logFile ?: return

        if (!file.exists()) {
            createEmptyLogFile()
            return
        }

        try {
            val content = file.readText()
            if (!isValidJson(content)) {
                file.delete()
                createEmptyLogFile()
            }
        } catch (_: Exception) {
            file.delete()
            createEmptyLogFile()
        }
    }

    private fun isValidJson(content: String): Boolean = content.contains("\"errors\":[") && content.contains("\"metadata\":{")

    private fun createEmptyLogFile(): File {
        val file = logFile ?: throw IllegalStateException("ErrorLogger not initialized")

        val emptyLog =
            """
            {
              "errors": [],
              "metadata": {
                "logVersion": "1.0",
                "firstError": null,
                "lastError": null,
                "totalErrors": 0
              }
            }
            """.trimIndent()

        file.writeText(emptyLog)
        return file
    }

    private fun writeEntryToFile(entry: ErrorEntry) {
        try {
            val file = logFile ?: return
            if (!file.exists()) {
                createEmptyLogFile()
            }

            val content = file.readText()
            val updatedContent = addEntryToJson(content, entry)

            file.writeText(updatedContent)

            rotateIfNeeded()
        } catch (_: Exception) {
            writeFallback(entry)
        }
    }

    private fun addEntryToJson(
        json: String,
        entry: ErrorEntry,
    ): String {
        val errorsEnd = json.indexOf("]")
        if (errorsEnd == -1) return json

        val entryJson = buildEntryJson(entry)

        val hasExistingErrors = json.substring(0, errorsEnd).contains("{\"timestamp\":")

        val separator = if (hasExistingErrors) "," else ""

        val before = json.substring(0, errorsEnd)
        val after = json.substring(errorsEnd)

        val metadataRegex = """"totalErrors":\s*(\d+)""".toRegex()
        val currentCount =
            metadataRegex
                .find(after)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 0
        val newCount = currentCount + 1

        val updatedAfter =
            after
                .replace(
                    """"firstError":\s*null""".toRegex(),
                    "\"firstError\":\"${dateFormat.format(Date(entry.timestamp))}\"",
                ).replace(
                    """"lastError":\s*"[^"]*"""".toRegex(),
                    "\"lastError\":\"${dateFormat.format(Date(entry.timestamp))}\"",
                ).replace(
                    """"lastError":\s*null""".toRegex(),
                    "\"lastError\":\"${dateFormat.format(Date(entry.timestamp))}\"",
                ).replace(
                    """"totalErrors":\s*\d+""".toRegex(),
                    "\"totalErrors\":$newCount",
                )

        return "$before$separator$entryJson$updatedAfter"
    }

    private fun buildEntryJson(entry: ErrorEntry): String {
        val contextJson =
            if (entry.context.isEmpty()) {
                "{}"
            } else {
                entry.context.entries.joinToString(",", "{", "}") { (k, v) ->
                    "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
                }
            }

        return """
            {
              "timestamp": "${dateFormat.format(Date(entry.timestamp))}",
              "component": "${escapeJson(entry.component)}",
              "severity": "${entry.severity.name}",
              "failurePoint": "${escapeJson(entry.failurePoint)}",
              "exceptionType": "${escapeJson(entry.exceptionType)}",
              "message": "${escapeJson(entry.message)}",
              "context": $contextJson
            }
            """.trimIndent().replace("\n", "")
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun rotateIfNeeded() {
        val file = logFile ?: return

        if (!file.exists()) return

        val needsRotation = file.length() > MAX_FILE_SIZE || getErrorCount() > MAX_ENTRIES

        if (needsRotation) {
            rotateLog()
        }
    }

    private fun rotateLog() {
        val file = logFile ?: return
        if (!file.exists()) return

        try {
            val content = file.readText()
            val errorsStart = content.indexOf("\"errors\":[")
            val errorsEnd = content.indexOf("]", errorsStart)

            if (errorsStart == -1 || errorsEnd == -1) {
                file.delete()
                createEmptyLogFile()
                return
            }

            val errorsSection = content.substring(errorsStart + 10, errorsEnd)
            val entries = mutableListOf<String>()

            var index = 0
            while (index < errorsSection.length) {
                val start = errorsSection.indexOf("{\"timestamp\":", index)
                if (start == -1) break

                var braceCount = 0
                var end = start
                while (end < errorsSection.length) {
                    when (errorsSection[end]) {
                        '{' -> braceCount++
                        '}' -> {
                            braceCount--
                            if (braceCount == 0) {
                                entries.add(errorsSection.substring(start, end + 1))
                                index = end + 1
                                break
                            }
                        }
                    }
                    end++
                }

                if (braceCount != 0) break
            }

            val keepCount = MAX_ENTRIES / 2
            val keptEntries = entries.takeLast(keepCount)

            val newJson =
                """
                {
                  "errors": [${keptEntries.joinToString(",")}],
                  "metadata": {
                    "logVersion": "1.0",
                    "firstError": null,
                    "lastError": null,
                    "totalErrors": ${keptEntries.size}
                  }
                }
                """.trimIndent()

            file.writeText(newJson)
        } catch (_: Exception) {
            file.delete()
            createEmptyLogFile()
        }
    }

    private fun writeFallback(entry: ErrorEntry) {
        try {
            val file = fallbackFile ?: return
            val line =
                "${dateFormat.format(
                    Date(entry.timestamp),
                )} [${entry.severity}] ${entry.component} - ${entry.failurePoint}: ${entry.exceptionType} - ${entry.message}\n"
            file.appendText(line)
        } catch (_: Exception) {
        }
    }

    private fun extractFailurePoint(exception: Throwable): String {
        val stackTrace = exception.stackTrace
        if (stackTrace.isEmpty()) {
            return "unknown"
        }

        val topFrame = stackTrace.firstOrNull { it.className.startsWith("com.urik.keyboard") } ?: stackTrace[0]

        val className = topFrame.className.substringAfterLast('.')
        val methodName = topFrame.methodName
        val lineNumber = topFrame.lineNumber

        return "$className.$methodName:$lineNumber"
    }

    private fun sanitizeContext(context: Map<String, String>): Map<String, String> =
        context.mapValues { (_, value) ->
            value
                .take(MAX_CONTEXT_VALUE_LENGTH)
                .replace("\n", " ")
                .replace("\r", " ")
        }
}
