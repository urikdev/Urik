package com.urik.keyboard.utils

object UrlEmailDetector {
    fun isUrlOrEmailContext(currentWord: String, textBeforeCursor: String, nextChar: String? = null): Boolean {
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

        if ((currentWord.startsWith("http") || currentWord.startsWith("ftp")) &&
            (nextChar == ":" || currentWord.contains(":"))
        ) {
            return true
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

        return false
    }
}
