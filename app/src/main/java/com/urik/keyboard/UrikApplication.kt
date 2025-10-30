package com.urik.keyboard

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.settings.Theme
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

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
    @Inject
    lateinit var settingsRepository: SettingsRepository

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

        applyTheme()
    }

    private fun applyTheme() {
        try {
            val theme =
                runBlocking {
                    settingsRepository.settings.first().theme
                }

            val nightMode =
                when (theme) {
                    Theme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    Theme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    Theme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }

            AppCompatDelegate.setDefaultNightMode(nightMode)
        } catch (_: Exception) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
