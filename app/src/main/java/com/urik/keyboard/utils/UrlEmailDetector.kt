package com.urik.keyboard.utils

object UrlEmailDetector {
    /**
     * Detects if we're typing in a URL or email context.
     *
     * @param currentWord Word currently being typed (may be empty)
     * @param textBeforeCursor Text before cursor position (for context)
     * @param nextChar Character about to be typed (may be null)
     * @return true if in URL/email context, false otherwise
     */
    fun isUrlOrEmailContext(
        currentWord: String,
        textBeforeCursor: String,
        nextChar: String? = null,
    ): Boolean {
        if (nextChar == "@") {
            return true
        }

        if (currentWord.contains("@")) {
            return true
        }

        if (textBeforeCursor.contains("@")) {
            val lastAtIndex = textBeforeCursor.lastIndexOf('@')
            val textAfterAt = textBeforeCursor.substring(lastAtIndex + 1)
            if (!textAfterAt.contains(' ')) {
                return true
            }
        }

        if (currentWord.contains("://") || textBeforeCursor.endsWith("://")) {
            return true
        }

        if (currentWord.startsWith("http") || currentWord.startsWith("ftp")) {
            if (nextChar == ":" || currentWord.contains(":")) {
                return true
            }
        }

        if (currentWord.equals("www", ignoreCase = true) && nextChar == ".") {
            return true
        }

        if (textBeforeCursor.endsWith("www.") || textBeforeCursor.contains("://")) {
            return true
        }

        if (nextChar == ":" && currentWord.isNotEmpty()) {
            val hasProtocolBefore =
                textBeforeCursor.endsWith("http") ||
                    textBeforeCursor.endsWith("https") ||
                    textBeforeCursor.endsWith("ftp")
            if (hasProtocolBefore) {
                return true
            }
        }

        val combinedText = textBeforeCursor + currentWord
        if (combinedText.contains("://")) {
            val lastProtocolIndex = combinedText.lastIndexOf("://")
            val textAfterProtocol = combinedText.substring(lastProtocolIndex + 3)
            if (!textAfterProtocol.contains(' ')) {
                return true
            }
        }

        return false
    }
}
