package com.urik.keyboard.service

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.data.database.UserKanjiFrequencyDao
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class KanaKanjiConverter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userKanjiFrequencyDao: UserKanjiFrequencyDao
) : ScriptConverter {
    private var index: TreeMap<String, MutableList<ConversionCandidate>> = TreeMap()
    private val userFrequencies = ConcurrentHashMap<String, Long>()

    private val loadMutex = Mutex()
    private var writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var loaded = false

    override val supportedLanguages: Set<String> = setOf("ja")

    override val isReady: Boolean get() = loaded

    @VisibleForTesting
    internal constructor(
        context: Context,
        userKanjiFrequencyDao: UserKanjiFrequencyDao,
        writeDispatcher: CoroutineDispatcher
    ) : this(context, userKanjiFrequencyDao) {
        writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)
    }

    override suspend fun getCandidates(input: String, languageCode: String): List<ConversionCandidate> {
        ensureLoaded()
        if (input.isEmpty()) return emptyList()

        val ceiling = input + '￿'
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
        writeScope.launch {
            try {
                userKanjiFrequencyDao.incrementBy(
                    reading = input,
                    surface = surface,
                    amount = BASE_USER_FREQUENCY,
                    lastUsed = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KanaKanjiConverter",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "persistUserSelection")
                )
            }
        }
    }

    override fun release() {
        writeScope.cancel()
        writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        index = TreeMap()
        loaded = false
    }

    @VisibleForTesting
    internal fun userFrequenciesForTest(): Map<String, Long> = userFrequencies

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            val result = withContext(Dispatchers.IO) { loadIndex() }
            if (result.isNotEmpty()) {
                index = result
                try {
                    val rows = userKanjiFrequencyDao.getAll()
                    rows.forEach { row ->
                        userFrequencies["${row.reading}\t${row.surface}"] = row.frequency
                    }
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "KanaKanjiConverter",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "loadUserFrequencies")
                    )
                }
                loaded = true
            }
        }
    }

    private suspend fun loadIndex(): TreeMap<String, MutableList<ConversionCandidate>> {
        val result = TreeMap<String, MutableList<ConversionCandidate>>()
        try {
            context.assets.open("dictionaries/ja_readings.txt").bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    currentCoroutineContext().ensureActive()
                    if (line.startsWith("#") || line.isBlank()) continue
                    val parts = line.split("\t")
                    if (parts.size == 3) {
                        val reading = parts[0]
                        val surface = parts[1]
                        val freq = parts[2].toLongOrNull()
                        if (freq != null) {
                            result.getOrPut(reading) { mutableListOf() }
                                .add(ConversionCandidate(surface, reading, freq, "dictionary"))
                        }
                    }
                }
            }
            result.values.forEach { list -> list.sortByDescending { it.frequency } }
        } catch (e: CancellationException) {
            throw e
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
