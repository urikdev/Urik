package com.urik.keyboard.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.blacklistDataStore by preferencesDataStore(name = "blacklist_words")

/**
 * Persists user-rejected suggestion words so removals survive IME process death.
 * Global across languages.
 */
@Singleton
class BlacklistRepository internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(context.blacklistDataStore)

    private object PreferenceKeys {
        val BLACKLISTED_WORDS = stringSetPreferencesKey("blacklisted_words")
    }

    suspend fun getAll(): Set<String> = dataStore.data.first()[PreferenceKeys.BLACKLISTED_WORDS] ?: emptySet()

    suspend fun add(word: String) {
        dataStore.edit { preferences ->
            val current = preferences[PreferenceKeys.BLACKLISTED_WORDS] ?: emptySet()
            preferences[PreferenceKeys.BLACKLISTED_WORDS] = current + word
        }
    }

    suspend fun remove(word: String) {
        dataStore.edit { preferences ->
            val current = preferences[PreferenceKeys.BLACKLISTED_WORDS] ?: emptySet()
            preferences[PreferenceKeys.BLACKLISTED_WORDS] = current - word
        }
    }
}
