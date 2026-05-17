package com.urik.keyboard.service

import com.urik.keyboard.data.CustomKeyMappingRepository
import com.urik.keyboard.di.ApplicationScope
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service providing cached access to custom key mappings.
 *
 * Maintains an in-memory cache of mappings for fast lookup during key rendering
 * and input processing. Automatically updates cache when repository changes.
 *
 * Storage format: symbols are U+001F-delimited, NFC-normalized in the DB TEXT column.
 */
@Singleton
class CustomKeyMappingService
@Inject
constructor(
    private val repository: CustomKeyMappingRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val _mappings = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Map from base key (lowercase) to ordered list of custom symbols. */
    val mappings: StateFlow<Map<String, List<String>>> = _mappings.asStateFlow()

    private val isInitialized = AtomicBoolean(false)

    /** Safe to call multiple times; only initializes once. */
    fun initialize() {
        if (!isInitialized.compareAndSet(false, true)) return

        applicationScope.launch {
            repository.mappings.collect { mappingList ->
                _mappings.value = mappingList
                    .associate { it.baseKey to parseSymbols(it.customSymbol) }
                    .filter { it.value.isNotEmpty() }
            }
        }
    }

    fun getMapping(baseKey: String): List<String>? = _mappings.value[baseKey.lowercase()]

    fun hasMapping(baseKey: String): Boolean = baseKey.lowercase() in _mappings.value

    fun getAllMappings(): Map<String, List<String>> = _mappings.value.toMap()

    fun getMappingCount(): Int = _mappings.value.size

    suspend fun refresh() {
        _mappings.value = repository.getAllMappingsAsMap()
            .mapValues { (_, raw) -> parseSymbols(raw) }
            .filter { it.value.isNotEmpty() }
    }

    companion object {
        /** U+001F ASCII Unit Separator — safe delimiter across all supported scripts. */
        const val LONG_PRESS_DELIMITER = ''
        const val MAX_CUSTOM_SYMBOLS = 5

        /**
         * Splits a stored symbol string into an ordered, NFC-normalized, deduplicated list capped at 5.
         * Handles legacy single-char values (no delimiter) as single-element lists.
         */
        fun parseSymbols(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()

            return raw
                .split(LONG_PRESS_DELIMITER)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { Normalizer.normalize(it, Normalizer.Form.NFC) }
                .distinct()
                .take(MAX_CUSTOM_SYMBOLS)
        }
    }
}
