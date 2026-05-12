@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.data.ClipboardRepository
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ContentHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Tests [ClipboardMonitorService] dedup logic, truncation, and repository delegation.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardMonitorServiceTest {
    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var repo: ClipboardRepository
    private lateinit var settings: SettingsRepository
    private lateinit var service: ClipboardMonitorService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clipboardManager = context.getSystemService(ClipboardManager::class.java)
        repo = mock()
        settings = mock()
        whenever(settings.settings).thenReturn(flowOf(KeyboardSettings(clipboardEnabled = true)))
        runBlocking { whenever(repo.addItem(any())).thenReturn(Result.success(Unit)) }
        service = ClipboardMonitorService(
            context = context,
            clipboardRepository = repo,
            settingsRepository = settings,
            applicationScope = CoroutineScope(UnconfinedTestDispatcher())
        )
    }

    private fun setClip(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("label", text))
    }

    @Test
    fun `same text copied twice triggers addItem exactly once`() = runTest {
        setClip("hello world")
        service.onClipboardChanged()
        setClip("hello world")
        service.onClipboardChanged()

        verify(repo, times(1)).addItem(any())
    }

    @Test
    fun `different text copied triggers addItem twice`() = runTest {
        setClip("alpha")
        service.onClipboardChanged()
        setClip("beta")
        service.onClipboardChanged()

        verify(repo, times(1)).addItem("alpha")
        verify(repo, times(1)).addItem("beta")
    }

    @Test
    fun `100000 character truncation before hashing and before addItem`() = runTest {
        val text1 = "A".repeat(150_000)
        setClip(text1)
        service.onClipboardChanged()

        verify(repo, times(1)).addItem("A".repeat(100_000))

        val text2 = "A".repeat(100_000) + "B".repeat(50_000)
        setClip(text2)
        service.onClipboardChanged()

        verify(repo, times(1)).addItem(any())
    }

    @Test
    fun `repository delegation passes truncated text content`() = runTest {
        val captured = mutableListOf<String>()
        whenever(repo.addItem(any())).thenAnswer { invocation ->
            captured.add(invocation.getArgument<String>(0))
            Result.success(Unit)
        }

        setClip("delegated text")
        service.onClipboardChanged()

        assertEquals(1, captured.size)
        assertEquals("delegated text", captured[0])
    }

    @Test
    fun `dedup uses SHA-256 not Int hashCode`() = runTest {
        val field = ClipboardMonitorService::class.java.getDeclaredField("lastClipContentHash")
        field.isAccessible = true

        assertEquals(String::class.java, field.type)

        setClip("abc")
        service.onClipboardChanged()

        assertEquals(ContentHasher.sha256Hex("abc"), field.get(service))
    }

    @Test
    fun `clipboardEnabled false short-circuits before addItem`() = runTest {
        whenever(settings.settings).thenReturn(flowOf(KeyboardSettings(clipboardEnabled = false)))

        setClip("anything")
        service.onClipboardChanged()

        verify(repo, never()).addItem(any())
    }

    @Test
    fun `null clip text is ignored`() = runTest {
        clipboardManager.clearPrimaryClip()
        service.onClipboardChanged()

        verify(repo, never()).addItem(any())
    }

    @Test
    fun `blank text is ignored`() = runTest {
        setClip("   ")
        service.onClipboardChanged()

        verify(repo, never()).addItem(any())
    }
}
