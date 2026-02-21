package com.urik.keyboard.service

import android.graphics.PointF
import com.urik.keyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanguageManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        private var scope = CoroutineScope(dispatcher + SupervisorJob())

        private val _currentLanguage = MutableStateFlow("en")
        val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

        private val _currentLayoutLanguage = MutableStateFlow("en")
        val currentLayoutLanguage: StateFlow<String> = _currentLayoutLanguage.asStateFlow()

        private val _activeLanguages = MutableStateFlow(listOf("en"))
        val activeLanguages: StateFlow<List<String>> = _activeLanguages.asStateFlow()

        private val _mergedDictionaries = MutableStateFlow(true)
        val mergedDictionaries: StateFlow<Boolean> = _mergedDictionaries.asStateFlow()

        private val _effectiveDictionaryLanguages = MutableStateFlow(listOf("en"))
        val effectiveDictionaryLanguages: StateFlow<List<String>> = _effectiveDictionaryLanguages.asStateFlow()

        private val _keyPositions = MutableStateFlow<Map<Char, PointF>>(emptyMap())
        val keyPositions: StateFlow<Map<Char, PointF>> = _keyPositions.asStateFlow()

        suspend fun initialize(): Result<Unit> =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    val initialSettings = settingsRepository.settings.first()
                    _currentLanguage.value = initialSettings.primaryLanguage
                    _currentLayoutLanguage.value = initialSettings.primaryLayoutLanguage
                    _activeLanguages.value = initialSettings.activeLanguages
                    _mergedDictionaries.value = initialSettings.mergedDictionaries
                    updateEffectiveDictionaryLanguages()

                    settingsRepository.settings
                        .map { it.primaryLanguage }
                        .distinctUntilChanged()
                        .onEach { _currentLanguage.value = it }
                        .launchIn(scope)

                    settingsRepository.settings
                        .map { it.primaryLayoutLanguage }
                        .distinctUntilChanged()
                        .onEach { _currentLayoutLanguage.value = it }
                        .launchIn(scope)

                    settingsRepository.settings
                        .map { it.activeLanguages }
                        .distinctUntilChanged()
                        .onEach { _activeLanguages.value = it }
                        .launchIn(scope)

                    settingsRepository.settings
                        .map { it.mergedDictionaries }
                        .distinctUntilChanged()
                        .onEach { _mergedDictionaries.value = it }
                        .launchIn(scope)

                    combine(
                        _activeLanguages,
                        _currentLayoutLanguage,
                        _mergedDictionaries,
                    ) { active, layout, merged ->
                        if (merged) active else listOf(layout)
                    }.distinctUntilChanged()
                        .onEach { _effectiveDictionaryLanguages.value = it }
                        .launchIn(scope)

                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        fun updateKeyPositions(positions: Map<Char, PointF>) {
            _keyPositions.value = positions
        }

        suspend fun switchLayoutLanguage(languageCode: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    val currentActiveLanguages = _activeLanguages.value
                    if (languageCode !in currentActiveLanguages) {
                        return@withContext Result.failure(
                            IllegalArgumentException("Language $languageCode is not in active languages"),
                        )
                    }

                    settingsRepository.updatePrimaryLayoutLanguage(languageCode)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        fun getNextLayoutLanguage(): String {
            val currentActiveLanguages = _activeLanguages.value
            val currentLayoutLang = _currentLayoutLanguage.value
            val currentIndex = currentActiveLanguages.indexOf(currentLayoutLang)

            return if (currentIndex == -1 || currentIndex == currentActiveLanguages.size - 1) {
                currentActiveLanguages.firstOrNull() ?: "en"
            } else {
                currentActiveLanguages[currentIndex + 1]
            }
        }

        private fun updateEffectiveDictionaryLanguages() {
            _effectiveDictionaryLanguages.value =
                if (_mergedDictionaries.value) {
                    _activeLanguages.value
                } else {
                    listOf(_currentLayoutLanguage.value)
                }
        }

        fun cleanup() {
            scope.cancel()
        }
    }
