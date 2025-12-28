@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class RecentEmojiProviderTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var provider: RecentEmojiProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mock()
        sharedPreferences = mock()
        editor = mock()

        whenever(context.getSharedPreferences(any(), eq(Context.MODE_PRIVATE))).thenReturn(sharedPreferences)
        whenever(sharedPreferences.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(sharedPreferences.getString(any(), any())).thenReturn("")

        provider = RecentEmojiProvider(context, testScope, testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `getRecentEmojiList returns empty list initially`() =
        runTest {
            whenever(sharedPreferences.getString(any(), any())).thenReturn("")

            val result = provider.getRecentEmojiList()

            assertTrue(result.isEmpty())
        }

    @Test
    fun `getRecentEmojiList loads from preferences`() =
        runTest {
            whenever(sharedPreferences.getString(any(), any())).thenReturn("😀,😁,😂")

            val result = provider.getRecentEmojiList()

            assertEquals(listOf("😀", "😁", "😂"), result)
        }

    @Test
    fun `recordSelection adds emoji to front of list`() =
        runTest {
            whenever(sharedPreferences.getString(any(), any())).thenReturn("")

            provider.recordSelection("😀")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(editor).putString(any(), eq("😀"))
        }

    @Test
    fun `recordSelection moves existing emoji to front`() =
        runTest {
            whenever(sharedPreferences.getString(any(), any())).thenReturn("😀,😁,😂")

            provider.recordSelection("😁")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(editor).putString(any(), eq("😁,😀,😂"))
        }

    @Test
    fun `recordSelection limits list to MAX_RECENT_EMOJIS`() =
        runTest {
            val fiftyOneEmojis = (1..51).joinToString(",") { "😀$it" }
            whenever(sharedPreferences.getString(any(), any())).thenReturn(fiftyOneEmojis)

            provider.recordSelection("🎉")
            testDispatcher.scheduler.advanceUntilIdle()

            val expected = (listOf("🎉") + (1..49).map { "😀$it" }).joinToString(",")
            verify(editor).putString(any(), eq(expected))
        }

    @Test
    fun `getRecentEmojiList filters empty strings`() =
        runTest {
            whenever(sharedPreferences.getString(any(), any())).thenReturn("😀,,😁,,😂")

            val result = provider.getRecentEmojiList()

            assertEquals(listOf("😀", "😁", "😂"), result)
        }

    @Test
    fun `getRecentEmojiList limits to MAX_RECENT_EMOJIS on load`() =
        runTest {
            val sixtyEmojis = (1..60).joinToString(",") { "😀$it" }
            whenever(sharedPreferences.getString(any(), any())).thenReturn(sixtyEmojis)

            val result = provider.getRecentEmojiList()

            assertEquals(50, result.size)
        }
}
