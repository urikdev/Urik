package com.urik.keyboard.di

import com.urik.keyboard.service.KanaKanjiConverter
import com.urik.keyboard.service.ScriptConverter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ScriptConverterModule {
    @Binds
    @IntoSet
    abstract fun bindKanaKanjiConverter(impl: KanaKanjiConverter): ScriptConverter
}
