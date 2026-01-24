package com.urik.keyboard.di

import android.content.Context
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.DictionaryBackupManager
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.service.WordNormalizer
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.ui.keyboard.components.PathGeometryAnalyzer
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CacheMemoryManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        settingsRepository: SettingsRepository,
    ): KeyboardRepository = KeyboardRepository(context, cacheMemoryManager, settingsRepository)

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
    fun provideWordNormalizer(): WordNormalizer = WordNormalizer()

    @Provides
    @Singleton
    fun provideWordFrequencyRepository(
        userWordFrequencyDao: UserWordFrequencyDao,
        userWordBigramDao: UserWordBigramDao,
        wordNormalizer: WordNormalizer,
        cacheMemoryManager: CacheMemoryManager,
    ): WordFrequencyRepository = WordFrequencyRepository(userWordFrequencyDao, userWordBigramDao, wordNormalizer, cacheMemoryManager)

    @Provides
    @Singleton
    fun provideWordLearningEngine(
        learnedWordDao: LearnedWordDao,
        languageManager: LanguageManager,
        wordNormalizer: WordNormalizer,
        settingsRepository: SettingsRepository,
        cacheMemoryManager: CacheMemoryManager,
    ): WordLearningEngine =
        WordLearningEngine(
            learnedWordDao,
            languageManager,
            wordNormalizer,
            settingsRepository,
            cacheMemoryManager,
        )

    @Provides
    @Singleton
    fun provideSwipeDetector(
        spellCheckManager: SpellCheckManager,
        wordLearningEngine: WordLearningEngine,
        pathGeometryAnalyzer: PathGeometryAnalyzer,
    ): SwipeDetector = SwipeDetector(spellCheckManager, wordLearningEngine, pathGeometryAnalyzer)

    @Provides
    @Singleton
    fun provideSpellCheckManager(
        @ApplicationContext context: Context,
        languageManager: LanguageManager,
        wordLearningEngine: WordLearningEngine,
        wordFrequencyRepository: WordFrequencyRepository,
        cacheMemoryManager: CacheMemoryManager,
    ): SpellCheckManager = SpellCheckManager(context, languageManager, wordLearningEngine, wordFrequencyRepository, cacheMemoryManager)

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
        wordFrequencyRepository: WordFrequencyRepository,
    ): SettingsRepository = SettingsRepository(context, database, cacheMemoryManager, wordFrequencyRepository)

    @Provides
    @Singleton
    fun provideThemeManager(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
    ): com.urik.keyboard.theme.ThemeManager =
        com.urik.keyboard.theme
            .ThemeManager(context, settingsRepository)

    @Provides
    @Singleton
    fun provideEmojiSearchManager(
        @ApplicationContext context: Context,
        languageManager: LanguageManager,
    ): EmojiSearchManager = EmojiSearchManager(context, languageManager)

    @Provides
    @Singleton
    fun provideRecentEmojiProvider(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): com.urik.keyboard.service.RecentEmojiProvider =
        com.urik.keyboard.service
            .RecentEmojiProvider(context, scope)

    @Provides
    @Singleton
    fun provideDictionaryBackupManager(
        @ApplicationContext context: Context,
        database: KeyboardDatabase,
        learnedWordDao: LearnedWordDao,
        cacheMemoryManager: CacheMemoryManager,
    ): DictionaryBackupManager = DictionaryBackupManager(context, database, learnedWordDao, cacheMemoryManager)
}
