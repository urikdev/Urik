package com.urik.keyboard.service

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScriptConverterRegistry @Inject constructor(converters: Set<@JvmSuppressWildcards ScriptConverter>) {
    private val byLanguage: Map<String, ScriptConverter> =
        converters.flatMap { c -> c.supportedLanguages.map { it to c } }.toMap()

    fun forLanguage(languageCode: String): ScriptConverter? =
        byLanguage[languageCode] ?: byLanguage[languageCode.substringBefore('-')]
}
