package com.urik.keyboard.service

import android.content.ClipboardManager
import android.content.Context
import com.urik.keyboard.data.ClipboardRepository
import com.urik.keyboard.di.ApplicationScope
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors system clipboard for changes and stores text history.
 *
 * Stores all text copied by user. User manually deletes unwanted items.
 * Privacy-focused: Never syncs to cloud, stays local only.
 */
@Singleton
class ClipboardMonitorService
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val clipboardRepository: ClipboardRepository,
        private val settingsRepository: SettingsRepository,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        private val clipboardManager = context.getSystemService(ClipboardManager::class.java)

        private var lastClipContentHash: Int? = null
        private val isMonitoring = AtomicBoolean(false)

        private val listener =
            ClipboardManager.OnPrimaryClipChangedListener {
                onClipboardChanged()
            }

        fun startMonitoring() {
            if (!isMonitoring.compareAndSet(false, true)) return

            try {
                clipboardManager?.addPrimaryClipChangedListener(listener)
            } catch (e: Exception) {
                isMonitoring.set(false)
                ErrorLogger.logException(
                    component = "ClipboardMonitorService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "startMonitoring"),
                )
            }
        }

        fun stopMonitoring() {
            if (!isMonitoring.compareAndSet(true, false)) return

            try {
                clipboardManager?.removePrimaryClipChangedListener(listener)
            } catch (e: Exception) {
                isMonitoring.set(true)
                ErrorLogger.logException(
                    component = "ClipboardMonitorService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "stopMonitoring"),
                )
            }
        }

        private fun onClipboardChanged() {
            applicationScope.launch {
                try {
                    val settings = settingsRepository.settings.first()
                    if (!settings.clipboardEnabled) return@launch

                    val clip = clipboardManager?.primaryClip
                    if (clip == null || clip.itemCount == 0) return@launch

                    val item = clip.getItemAt(0)
                    val text = item.text?.toString()

                    if (text.isNullOrBlank()) return@launch

                    val truncatedText = text.take(100_000)

                    val textHash = truncatedText.hashCode()
                    if (textHash == lastClipContentHash) return@launch

                    clipboardRepository.addItem(truncatedText)
                    lastClipContentHash = textHash
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "ClipboardMonitorService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "onClipboardChanged"),
                    )
                }
            }
        }
    }
