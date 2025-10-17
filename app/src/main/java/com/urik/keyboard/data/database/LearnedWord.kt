package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ibm.icu.text.BreakIterator
import kotlinx.serialization.Serializable

/**
 * User-typed word stored for learning and suggestions.
 *
 * Words normalized using NFC + language-specific rules.
 * Unique constraint on (language_tag, word_normalized).
 * characterCount uses grapheme clusters
 */
@Entity(
    tableName = "learned_words",
    indices = [
        Index(
            value = ["language_tag", "word_normalized"],
            name = "idx_exact_lookup",
            unique = true,
        ),
        Index(
            value = ["language_tag", "frequency", "last_used"],
            name = "idx_frequency_recent",
        ),
        Index(
            value = ["frequency", "last_used"],
            name = "idx_cleanup",
        ),
    ],
)
@Serializable
data class LearnedWord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "word")
    val word: String,
    @ColumnInfo(name = "word_normalized")
    val wordNormalized: String,
    @ColumnInfo(name = "language_tag")
    val languageTag: String,
    @ColumnInfo(name = "frequency")
    val frequency: Int = 1,
    @ColumnInfo(name = "source")
    val source: WordSource = WordSource.USER_TYPED,
    @ColumnInfo(name = "character_count")
    val characterCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_used")
    val lastUsed: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Creates LearnedWord with calculated grapheme count.
         *
         * Calculates visible character count
         * correctly for emoji and combining characters.
         */
        fun create(
            word: String,
            wordNormalized: String,
            languageTag: String,
            frequency: Int = 1,
            source: WordSource = WordSource.USER_TYPED,
            createdAt: Long = System.currentTimeMillis(),
            lastUsed: Long = System.currentTimeMillis(),
        ): LearnedWord =
            LearnedWord(
                id = 0,
                word = word,
                wordNormalized = wordNormalized,
                languageTag = languageTag,
                frequency = frequency,
                source = source,
                characterCount = calculateGraphemeCount(word),
                createdAt = createdAt,
                lastUsed = lastUsed,
            )

        /**
         * Calculates visible character count using ICU4J grapheme clusters
         */
        fun calculateGraphemeCount(text: String): Int {
            val iterator = BreakIterator.getCharacterInstance()
            iterator.setText(text)
            var count = 0
            while (iterator.next() != BreakIterator.DONE) {
                count++
            }
            return count
        }
    }

    /**
     * Returns new instance with incremented frequency and updated timestamp.
     */
    fun incrementFrequency(): LearnedWord =
        copy(
            frequency = frequency + 1,
            lastUsed = System.currentTimeMillis(),
        )
}

/**
 * Full-text search table for prefix matching on learned words.
 */
@Entity(tableName = "learned_words_fts")
@Fts4(contentEntity = LearnedWord::class)
data class LearnedWordFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long,
    @ColumnInfo(name = "word")
    val word: String,
    @ColumnInfo(name = "word_normalized")
    val wordNormalized: String,
)

/**
 * Source of learned word for analytics and prioritization.
 */
enum class WordSource {
    /** Manually typed by user */
    USER_TYPED,

    /** Learned from swipe gesture */
    SWIPE_LEARNED,

    /** User selected from suggestion bar */
    USER_SELECTED,

    /** User accepted auto-correction */
    AUTO_CORRECTED,

    /** Imported from external dictionary */
    IMPORTED,

    /** Pre-loaded common word */
    SYSTEM_DEFAULT,
}
