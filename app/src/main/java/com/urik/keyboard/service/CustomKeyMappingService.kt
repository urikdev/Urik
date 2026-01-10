package com.urik.keyboard.service

import com.urik.keyboard.data.CustomKeyMappingRepository
import com.urik.keyboard.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service providing cached access to custom key mappings.
 *
 * Maintains an in-memory cache of mappings for fast lookup during key rendering
 * and input processing. Automatically updates cache when repository changes.
 */
@Singleton
class CustomKeyMappingService
    @Inject
    constructor(
        private val repository: CustomKeyMappingRepository,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        private val _mappings = MutableStateFlow<Map<String, String>>(emptyMap())

        /**
         * Current custom key mappings as a StateFlow.
         *
         * Map from base key (lowercase) to custom symbol.
         */
        val mappings: StateFlow<Map<String, String>> = _mappings.asStateFlow()

        private val isInitialized = AtomicBoolean(false)

        /**
         * Initializes the service and starts observing repository changes.
         *
         * Safe to call multiple times; only initializes once.
         */
        fun initialize() {
            if (!isInitialized.compareAndSet(false, true)) return

            applicationScope.launch {
                repository.mappings.collect { mappingList ->
                    _mappings.value = mappingList.associate { it.baseKey to it.customSymbol }
                }
            }
        }

        /**
         * Gets the custom symbol mapped to a key, if any.
         *
         * @param baseKey The key to look up (case-insensitive)
         * @return The mapped symbol, or null if not mapped
         */
        fun getMapping(baseKey: String): String? = _mappings.value[baseKey.lowercase()]

        /**
         * Checks if a key has a custom mapping.
         *
         * @param baseKey The key to check (case-insensitive)
         * @return true if the key has a custom mapping
         */
        fun hasMapping(baseKey: String): Boolean = baseKey.lowercase() in _mappings.value

        /**
         * Gets all current mappings as a snapshot.
         */
        fun getAllMappings(): Map<String, String> = _mappings.value.toMap()

        /**
         * Gets the count of custom mappings.
         */
        fun getMappingCount(): Int = _mappings.value.size

        /**
         * Forces a refresh of the mappings from the repository.
         */
        suspend fun refresh() {
            _mappings.value = repository.getAllMappingsAsMap()
        }
    }
