package com.urik.keyboard.service

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BlacklistRepositoryTest {
    private lateinit var repository: BlacklistRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
                produceFile = { File(context.cacheDir, "blacklist_test.preferences_pb") }
            )
        repository = BlacklistRepository(dataStore)
    }

    @Test
    fun `getAll returns empty set when nothing blacklisted`() = runTest {
        val words = repository.getAll()

        assertTrue(words.isEmpty())
    }

    @Test
    fun `add persists word`() = runTest {
        repository.add("hello")

        val words = repository.getAll()

        assertEquals(setOf("hello"), words)
    }

    @Test
    fun `add is idempotent for duplicate word`() = runTest {
        repository.add("hello")
        repository.add("hello")

        val words = repository.getAll()

        assertEquals(setOf("hello"), words)
    }

    @Test
    fun `remove deletes word`() = runTest {
        repository.add("hello")
        repository.add("world")

        repository.remove("hello")

        val words = repository.getAll()
        assertEquals(setOf("world"), words)
    }

    @Test
    fun `remove for missing word does nothing`() = runTest {
        repository.add("world")

        repository.remove("missing")

        val words = repository.getAll()
        assertEquals(setOf("world"), words)
    }
}
