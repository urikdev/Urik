@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.data

import android.content.Context
import com.ibm.icu.util.ULocale
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests KeyboardRepository layout resolution using real assets.
 *
 * Uses Robolectric to access actual layout files from src/main/assets/layouts/,
 * verifying that locale → file → parsed layout resolves correctly with no mocked I/O.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KeyboardRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: KeyboardRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()
        val cacheMemoryManager = CacheMemoryManager(context)
        val settingsFlow = MutableStateFlow(KeyboardSettings())
        val settingsRepository = mock<SettingsRepository>()
        whenever(settingsRepository.settings).thenReturn(settingsFlow)
        repository = KeyboardRepository(context, cacheMemoryManager, settingsRepository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private suspend fun loadLetters(lang: String) =
        repository.getLayoutForMode(KeyboardMode.LETTERS, ULocale.forLanguageTag(lang))

    private fun letterKeys(result: Result<com.urik.keyboard.model.KeyboardLayout>) = result.getOrNull()!!.rows.flatten()
        .filterIsInstance<KeyboardKey.Character>()
        .filter { it.type == KeyboardKey.KeyType.LETTER }

    // ── Russian ──────────────────────────────────────────────────────────────

    @Test
    fun `Russian layout resolves to Cyrl script`() = runTest {
        val result = loadLetters("ru")
        assertTrue("ru layout must load without error", result.isSuccess)
        assertEquals("Cyrl", result.getOrNull()?.script)
    }

    @Test
    fun `Russian layout letter keys are all Cyrillic`() = runTest {
        val keys = letterKeys(loadLetters("ru"))
        assertTrue("Russian layout must have letter keys", keys.isNotEmpty())
        keys.forEach { key ->
            assertTrue(
                "Key '${key.value}' contains non-Cyrillic characters",
                key.value.all { it in '\u0400'..'\u04FF' }
            )
        }
    }

    // ── Ukrainian ─────────────────────────────────────────────────────────────

    @Test
    fun `Ukrainian layout resolves to Cyrl script`() = runTest {
        val result = loadLetters("uk")
        assertTrue("uk layout must load without error", result.isSuccess)
        assertEquals("Cyrl", result.getOrNull()?.script)
    }

    @Test
    fun `Ukrainian layout letter keys are all Cyrillic`() = runTest {
        val keys = letterKeys(loadLetters("uk"))
        assertTrue("Ukrainian layout must have letter keys", keys.isNotEmpty())
        keys.forEach { key ->
            assertTrue(
                "Key '${key.value}' contains non-Cyrillic characters",
                key.value.all { it in '\u0400'..'\u04FF' }
            )
        }
    }

    // ── Arabic ────────────────────────────────────────────────────────────────

    @Test
    fun `Arabic layout resolves to Arab script`() = runTest {
        val result = loadLetters("ar")
        assertTrue("ar layout must load without error", result.isSuccess)
        assertEquals("Arab", result.getOrNull()?.script)
    }

    @Test
    fun `Arabic layout letter keys are all Arabic`() = runTest {
        val keys = letterKeys(loadLetters("ar"))
        assertTrue("Arabic layout must have letter keys", keys.isNotEmpty())
        keys.forEach { key ->
            assertTrue(
                "Key '${key.value}' contains non-Arabic characters",
                key.value.all { it in '\u0600'..'\u06FF' }
            )
        }
    }

    // ── Farsi ─────────────────────────────────────────────────────────────────

    @Test
    fun `Farsi layout resolves to Arab script`() = runTest {
        val result = loadLetters("fa")
        assertTrue("fa layout must load without error", result.isSuccess)
        assertEquals("Arab", result.getOrNull()?.script)
    }

    @Test
    fun `Farsi layout letter keys are all Arabic-script`() = runTest {
        val keys = letterKeys(loadLetters("fa"))
        assertTrue("Farsi layout must have letter keys", keys.isNotEmpty())
        keys.forEach { key ->
            assertTrue(
                "Key '${key.value}' contains non-Arabic-script characters",
                key.value.all { it in '\u0600'..'\u06FF' }
            )
        }
    }

    // ── Greek ─────────────────────────────────────────────────────────────────

    @Test
    fun `Greek layout resolves to Grek script`() = runTest {
        val result = loadLetters("el")
        assertTrue("el layout must load without error", result.isSuccess)
        assertEquals("Grek", result.getOrNull()?.script)
    }

    // ── Japanese ──────────────────────────────────────────────────────────────

    @Test
    fun `Japanese layout resolves to Hira script`() = runTest {
        val result = loadLetters("ja")
        assertTrue("ja layout must load without error", result.isSuccess)
        assertEquals("Hira", result.getOrNull()?.script)
    }

    @Test
    fun `Japanese layout letters mode contains FlickKey instances`() = runTest {
        val layout = loadLetters("ja").getOrNull()!!
        val flickKeys = layout.rows.flatten().filterIsInstance<KeyboardKey.FlickKey>()
        assertTrue("Japanese layout must have FlickKey instances", flickKeys.isNotEmpty())
    }

    @Test
    fun `Japanese あ key has correct flick variants`() = runTest {
        val layout = loadLetters("ja").getOrNull()!!
        val aKey = layout.rows.flatten()
            .filterIsInstance<KeyboardKey.FlickKey>()
            .find { it.center == "あ" }
        assertNotNull("あ key must exist", aKey)
        assertEquals("い", aKey!!.up)
        assertEquals("う", aKey.right)
        assertEquals("え", aKey.down)
        assertEquals("お", aKey.left)
    }

    @Test
    fun `Japanese や key has null for up and left`() = runTest {
        val layout = loadLetters("ja").getOrNull()!!
        val yaKey = layout.rows.flatten()
            .filterIsInstance<KeyboardKey.FlickKey>()
            .find { it.center == "や" }
        assertNotNull("や key must exist", yaKey)
        assertNull(yaKey!!.up)
        assertNull(yaKey.left)
        assertEquals("ゆ", yaKey.right)
        assertEquals("よ", yaKey.down)
    }

    @Test
    fun `Japanese layout contains DAKUTEN action key`() = runTest {
        val layout = loadLetters("ja").getOrNull()!!
        val dakutenKey = layout.rows.flatten()
            .filterIsInstance<KeyboardKey.Action>()
            .find { it.action == KeyboardKey.ActionType.DAKUTEN }
        assertNotNull("DAKUTEN action key must exist in Japanese layout", dakutenKey)
    }

    @Test
    fun `Japanese layout contains SMALL_KANA action key`() = runTest {
        val layout = loadLetters("ja").getOrNull()!!
        val smallKanaKey = layout.rows.flatten()
            .filterIsInstance<KeyboardKey.Action>()
            .find { it.action == KeyboardKey.ActionType.SMALL_KANA }
        assertNotNull("SMALL_KANA action key must exist in Japanese layout", smallKanaKey)
    }
}
