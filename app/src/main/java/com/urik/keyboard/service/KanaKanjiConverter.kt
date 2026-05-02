package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class KanaKanjiConverter @Inject constructor(@ApplicationContext private val context: Context) : ScriptConverter {
    private var index: TreeMap<String, MutableList<ConversionCandidate>> = TreeMap()
    private val userFrequencies = ConcurrentHashMap<String, Long>()

    private val loadMutex = Mutex()

    @Volatile private var loaded = false

    override val supportedLanguages: Set<String> = setOf("ja")

    override val isReady: Boolean get() = loaded

    override suspend fun getCandidates(input: String, languageCode: String): List<ConversionCandidate> {
        ensureLoaded()
        if (input.isEmpty()) return emptyList()

        val ceiling = input + '\uFFFF'
        val dict = index.subMap(input, ceiling).values.flatten()

        val userBoosted = userFrequencies.entries
            .filter { it.key.startsWith("$input\t") }
            .map { (key, freq) ->
                val surface = key.substringAfter("\t")
                ConversionCandidate(surface, input, freq * USER_FREQ_MULTIPLIER, "learned")
            }

        val userSurfaces = userBoosted.map { it.surface }.toSet()
        return (userBoosted + dict.filter { it.surface !in userSurfaces })
            .sortedByDescending { it.frequency }
            .distinctBy { it.surface }
    }

    override fun recordSelection(input: String, surface: String) {
        val key = "$input\t$surface"
        userFrequencies.merge(key, BASE_USER_FREQUENCY) { old, new -> old + new }
    }

    override fun release() {
        index = TreeMap()
        loaded = false
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            index = withContext(Dispatchers.IO) { loadIndex() }
            loaded = true
        }
    }

    private fun loadIndex(): TreeMap<String, MutableList<ConversionCandidate>> {
        val result = TreeMap<String, MutableList<ConversionCandidate>>()
        try {
            context.assets.open("dictionaries/ja_readings.txt").bufferedReader().use { reader ->
                reader.lines().forEach { line ->
                    if (line.startsWith("#") || line.isBlank()) return@forEach
                    val parts = line.split("\t")
                    if (parts.size != 3) return@forEach
                    val reading = parts[0]
                    val surface = parts[1]
                    val freq = parts[2].toLongOrNull() ?: return@forEach
                    result.getOrPut(reading) { mutableListOf() }
                        .add(ConversionCandidate(surface, reading, freq, "dictionary"))
                }
            }
            result.values.forEach { list -> list.sortByDescending { it.frequency } }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KanaKanjiConverter",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "loadIndex")
            )
        }
        return result
    }

    private companion object {
        const val USER_FREQ_MULTIPLIER = 500L
        const val BASE_USER_FREQUENCY = 1L
    }
}
