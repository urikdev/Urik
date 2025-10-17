package com.urik.keyboard.di

import android.content.Context
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CacheMemoryManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides core keyboard services and infrastructure.
 *
 * All services are singletons - initialized once per app lifecycle.
 * Hilt manages dependency order and injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object KeyboardModule {
    @Provides
    @Singleton
    fun provideCacheMemoryManager(
        @ApplicationContext context: Context,
    ): CacheMemoryManager = CacheMemoryManager(context)

    @Provides
    @Singleton
    fun provideKeyboardRepository(
        @ApplicationContext context: Context,
        cacheMemoryManager: CacheMemoryManager,
    ): KeyboardRepository = KeyboardRepository(context, cacheMemoryManager)

    @Provides
    @Singleton
    fun provideCharacterVariationService(
        @ApplicationContext context: Context,
        cacheMemoryManager: CacheMemoryManager,
        languageManager: LanguageManager,
    ): CharacterVariationService = CharacterVariationService(context, cacheMemoryManager, languageManager)

    @Provides
    @Singleton
    fun provideLanguageManager(settingsRepository: SettingsRepository): LanguageManager = LanguageManager(settingsRepository)

    @Provides
    @Singleton
    fun provideWordLearningEngine(
        learnedWordDao: LearnedWordDao,
        languageManager: LanguageManager,
        settingsRepository: SettingsRepository,
        cacheMemoryManager: CacheMemoryManager,
    ): WordLearningEngine =
        WordLearningEngine(
            learnedWordDao,
            languageManager,
            settingsRepository,
            cacheMemoryManager,
        )

    @Provides
    @Singleton
    fun provideSwipeDetector(spellCheckManager: SpellCheckManager): SwipeDetector = SwipeDetector(spellCheckManager)

    @Provides
    @Singleton
    fun provideSpellCheckManager(
        @ApplicationContext context: Context,
        languageManager: LanguageManager,
        cacheMemoryManager: CacheMemoryManager,
        wordLearningEngine: WordLearningEngine,
    ): SpellCheckManager = SpellCheckManager(context, languageManager, wordLearningEngine, cacheMemoryManager)

    @Provides
    @Singleton
    fun provideTextInputProcessor(
        spellCheckManager: SpellCheckManager,
        settingsRepository: SettingsRepository,
    ): TextInputProcessor = TextInputProcessor(spellCheckManager, settingsRepository)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        database: KeyboardDatabase,
        cacheMemoryManager: CacheMemoryManager,
    ): SettingsRepository = SettingsRepository(context, database, cacheMemoryManager)
}
