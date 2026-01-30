package com.urik.keyboard.service

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SwipeSpaceManager @Inject constructor() {
    @Volatile
    private var autoSpaceJustInserted = false

    fun markAutoSpaceInserted() {
        autoSpaceJustInserted = true
    }

    fun clearAutoSpaceFlag() {
        autoSpaceJustInserted = false
    }

    fun shouldRemoveSpaceForPunctuation(char: Char, textBeforeCursor: String): Boolean {
        if (!autoSpaceJustInserted) return false
        autoSpaceJustInserted = false
        if (char !in SPACE_REMOVING_PUNCTUATION) return false
        return textBeforeCursor.lastOrNull() == ' '
    }

    fun isWhitespace(text: String): Boolean {
        return text.isNotEmpty() && text.last().isWhitespace()
    }

    companion object {
        private val SPACE_REMOVING_PUNCTUATION = setOf('.', ',', '?', '!', ':', ';')
    }
}
