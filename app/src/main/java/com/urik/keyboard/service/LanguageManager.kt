package com.urik.keyboard.service

import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanguageManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) {
        private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        private val _currentLanguage = MutableStateFlow("en")
        val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

        private var collectionJob: Job? = null

        internal fun setDispatcher(dispatcher: CoroutineDispatcher) {
            collectionJob?.cancel()
            scope.cancel()
            scope = CoroutineScope(dispatcher + SupervisorJob())
        }

        suspend fun initialize(): Result<Unit> =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    collectionJob?.cancel()

                    _currentLanguage.value = settingsRepository.settings.first().primaryLanguage

                    collectionJob =
                        settingsRepository.settings
                            .onEach { settings ->
                                _currentLanguage.value = settings.primaryLanguage
                            }.launchIn(scope)

                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        fun cleanup() {
            collectionJob?.cancel()
            scope.cancel()
        }
    }
