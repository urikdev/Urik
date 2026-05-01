package com.urik.keyboard.service

object EnglishPronounCorrection {
    fun capitalize(lowercaseWord: String): String? = when (lowercaseWord) {
        "i" -> "I"
        "i'm" -> "I'm"
        "i'll" -> "I'll"
        "i've" -> "I've"
        "i'd" -> "I'd"
        else -> null
    }
}
