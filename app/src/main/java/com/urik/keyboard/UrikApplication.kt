package com.urik.keyboard

import android.app.Application
import com.urik.keyboard.di.ApplicationScope
import com.urik.keyboard.service.ClipboardMonitorService
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * SQLCipher is loaded before any database access (must precede Room init).
 * Clipboard monitoring runs independently of keyboard visibility for complete capture.
 * Exception handler logs to local file only — no user data captured.
 */
@HiltAndroidApp
class UrikApplication : Application() {
    @Inject
    lateinit var clipboardMonitorService: ClipboardMonitorService

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        ErrorLogger.init(applicationContext)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            ErrorLogger.logException(
                component = "Application",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = throwable,
                context = mapOf("thread" to thread.name)
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
                    context = mapOf("phase" to "sqlcipher_load")
                )
                throw e
            }
        }

        observeClipboardSettings()
    }

    private fun observeClipboardSettings() {
        applicationScope.launch {
            settingsRepository.settings
                .map { it.isClipboardFullyActive }
                .distinctUntilChanged()
                .collect { fullyActive ->
                    if (fullyActive) {
                        clipboardMonitorService.startMonitoring()
                    } else {
                        clipboardMonitorService.stopMonitoring()
                    }
                }
        }
    }
}
