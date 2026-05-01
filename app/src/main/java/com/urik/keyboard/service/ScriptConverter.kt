package com.urik.keyboard.service

interface ScriptConverter {
    val supportedLanguages: Set<String>
    val isReady: Boolean

    suspend fun getCandidates(input: String, languageCode: String): List<ConversionCandidate>

    fun recordSelection(input: String, surface: String)

    fun release()
}
