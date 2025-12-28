package com.urik.keyboard.service

import android.content.Context
import androidx.core.content.edit
import androidx.emoji2.emojipicker.RecentEmojiProvider
import com.urik.keyboard.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentEmojiProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : RecentEmojiProvider {
        private val preferences by lazy {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }

        private val mutex = Mutex()
        private var cachedList: MutableList<String>? = null

        private suspend fun ensureLoaded(): MutableList<String> =
            mutex.withLock {
                cachedList?.let { return@withLock it }

                val stored =
                    withContext(dispatcher) {
                        preferences.getString(KEY_RECENT_EMOJI, "") ?: ""
                    }

                val loaded =
                    if (stored.isEmpty()) {
                        mutableListOf()
                    } else {
                        stored
                            .split(DELIMITER)
                            .filter { it.isNotEmpty() }
                            .take(MAX_RECENT_EMOJIS)
                            .toMutableList()
                    }

                cachedList = loaded
                loaded
            }

        private suspend fun persist(list: List<String>) {
            withContext(dispatcher) {
                preferences
                    .edit {
                        putString(KEY_RECENT_EMOJI, list.joinToString(DELIMITER))
                    }
            }
        }

        override suspend fun getRecentEmojiList(): List<String> = ensureLoaded().toList()

        override fun recordSelection(emoji: String) {
            scope.launch {
                val list = ensureLoaded()
                mutex.withLock {
                    list.remove(emoji)
                    list.add(0, emoji)

                    if (list.size > MAX_RECENT_EMOJIS) {
                        repeat(list.size - MAX_RECENT_EMOJIS) {
                            list.removeAt(list.lastIndex)
                        }
                    }
                }
                persist(list)
            }
        }

        companion object {
            private const val PREFS_FILE = "androidx.emoji2.emojipicker.preferences"
            private const val KEY_RECENT_EMOJI = "recent-emoji"
            private const val DELIMITER = ","
            private const val MAX_RECENT_EMOJIS = 50
        }
    }
