package com.urik.keyboard.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConversionCandidate(val surface: String, val reading: String, val frequency: Long, val source: String)

@Singleton
class KanaKanjiConverter @Inject constructor(@ApplicationContext private val context: Context) {
    private var index: TreeMap<String, MutableList<ConversionCandidate>> = TreeMap()
    private val userFrequencies = ConcurrentHashMap<String, Long>()

    @Volatile private var loaded = false

    suspend fun getCandidates(reading: String): List<ConversionCandidate> {
        ensureLoaded()
        if (reading.isEmpty()) return emptyList()

        val ceiling = reading + '\uFFFF'
        val dict = index.subMap(reading, ceiling).values.flatten()

        val userBoosted = userFrequencies.entries
            .filter { it.key.startsWith("$reading\t") }
            .map { (key, freq) ->
                val surface = key.substringAfter("\t")
                ConversionCandidate(surface, reading, freq * USER_FREQ_MULTIPLIER, "learned")
            }

        val userSurfaces = userBoosted.map { it.surface }.toSet()
        return (userBoosted + dict.filter { it.surface !in userSurfaces })
            .sortedByDescending { it.frequency }
            .distinctBy { it.surface }
    }

    fun incrementUserFrequency(reading: String, surface: String) {
        val key = "$reading\t$surface"
        userFrequencies.merge(key, BASE_USER_FREQUENCY) { old, new -> old + new }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            index = loadIndex()
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
        } catch (_: Exception) {
        }
        return result
    }

    private companion object {
        const val USER_FREQ_MULTIPLIER = 500L
        const val BASE_USER_FREQUENCY = 1L
    }
}
