package com.urik.keyboard

import android.app.Application
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for keyboard app initialization and dependency injection.
 *
 * ## SQLCipher Native Library
 * Loads libsqlcipher.so at app startup (before any database access).
 * SQLCipher provides AES-256 encryption for sensitive data.
 *
 * ## Error Logging
 * Installs global exception handler for critical failures.
 * Logs to local file for debugging, no user data captured.
 */
@HiltAndroidApp
class UrikApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        ErrorLogger.init(applicationContext)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            ErrorLogger.logException(
                component = "Application",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = throwable,
                context = mapOf("thread" to thread.name),
            )

            previousHandler?.uncaughtException(thread, throwable)
        }

        val isTestEnvironment =
            try {
                Class.forName("org.robolectric.RobolectricTestRunner")
                true
            } catch (_: ClassNotFoundException) {
                false
            }

        if (!isTestEnvironment) {
            try {
                System.loadLibrary("sqlcipher")
            } catch (e: UnsatisfiedLinkError) {
                ErrorLogger.logException(
                    component = "UrikApplication",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("phase" to "sqlcipher_load"),
                )
                throw e
            }
        }
    }
}
