package com.urik.keyboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryActiveLanguagesTest {
    private lateinit var context: Context
    private lateinit var repository: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>

    private val activeLanguagesList = stringPreferencesKey("active_languages_list")
    private val primaryLanguage = stringPreferencesKey("primary_language")

    private val legacyActiveLanguagesKey = stringSetPreferencesKey("active_languages")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(
            context,
            mock<KeyboardDatabase>(),
            mock<CacheMemoryManager>(),
            mock<WordFrequencyRepository>()
        )
        val field = SettingsRepository::class.java.getDeclaredField("dataStore")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        dataStore = field.get(repository) as DataStore<Preferences>
    }

    @Before
    fun clearDataStore() = runTest {
        dataStore.edit { it.clear() }
    }

    @Test
    fun `settings flow parses comma-separated activeLanguagesList`() = runTest {
        dataStore.edit { it[activeLanguagesList] = "en,fr,de" }

        val settings = repository.settings.first()

        assertEquals(listOf("en", "fr", "de"), settings.activeLanguages)
    }

    @Test
    fun `settings flow trims whitespace in activeLanguagesList entries`() = runTest {
        dataStore.edit { it[activeLanguagesList] = "en, fr , de" }

        val settings = repository.settings.first()

        assertEquals(listOf("en", "fr", "de"), settings.activeLanguages)
    }

    @Test
    fun `settings flow falls back to primaryLanguage when activeLanguagesList is missing`() = runTest {
        dataStore.edit { it[primaryLanguage] = "ja" }

        val settings = repository.settings.first()

        assertEquals(listOf("ja"), settings.activeLanguages)
    }

    @Test
    fun `settings flow falls back to DEFAULT_LANGUAGE when list and primary are missing`() = runTest {
        val settings = repository.settings.first()

        assertEquals(listOf(KeyboardSettings.DEFAULT_LANGUAGE), settings.activeLanguages)
    }

    @Test
    fun `legacy active_languages StringSet key is ignored`() = runTest {
        dataStore.edit { it[legacyActiveLanguagesKey] = setOf("en", "es") }

        val settings = repository.settings.first()

        assertEquals(listOf(KeyboardSettings.DEFAULT_LANGUAGE), settings.activeLanguages)
    }
}
