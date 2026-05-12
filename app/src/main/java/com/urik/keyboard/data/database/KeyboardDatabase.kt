package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        LearnedWord::class,
        LearnedWordFts::class,
        ClipboardItem::class,
        CustomKeyMapping::class,
        UserWordFrequency::class,
        UserWordBigram::class,
        UserKanjiFrequency::class
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3)
    ]
)
abstract class KeyboardDatabase : RoomDatabase() {
    abstract fun learnedWordDao(): LearnedWordDao

    abstract fun clipboardDao(): ClipboardDao

    abstract fun customKeyMappingDao(): CustomKeyMappingDao

    abstract fun userWordFrequencyDao(): UserWordFrequencyDao

    abstract fun userWordBigramDao(): UserWordBigramDao

    abstract fun userKanjiFrequencyDao(): UserKanjiFrequencyDao

    companion object {
        const val DATABASE_NAME = "keyboard_database"

        @Volatile private var instance: KeyboardDatabase? = null

        @Volatile private var isEncrypted: Boolean? = null

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                    DELETE FROM learned_words
                    WHERE id NOT IN (
                        SELECT id FROM (
                            SELECT id,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY language_tag, word_normalized
                                       ORDER BY frequency DESC, last_used DESC
                                   ) as rn
                            FROM learned_words
                        ) WHERE rn = 1
                    )
                        """.trimIndent()
                    )

                    db.execSQL("DROP INDEX IF EXISTS idx_prefix_suggestions")
                    db.execSQL("DROP INDEX IF EXISTS idx_exact_lookup")
                    db.execSQL("DROP INDEX IF EXISTS idx_learned_words_normalized")
                    db.execSQL("DROP INDEX IF EXISTS idx_learned_words_language_frequency")

                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "idx_exact_lookup ON learned_words(language_tag, word_normalized)"
                    )

                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "idx_frequency_recent ON learned_words(language_tag, frequency, last_used)"
                    )

                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_cleanup ON learned_words(frequency, last_used)")
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS custom_key_mappings (
                            base_key TEXT NOT NULL PRIMARY KEY,
                            custom_symbol TEXT NOT NULL,
                            created_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS user_word_frequency (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            language_tag TEXT NOT NULL,
                            word_normalized TEXT NOT NULL,
                            frequency INTEGER NOT NULL,
                            last_used INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "idx_user_freq_lookup ON user_word_frequency(language_tag, word_normalized)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "idx_user_freq_ranking ON user_word_frequency(language_tag, frequency)"
                    )

                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO user_word_frequency (language_tag, word_normalized, frequency, last_used)
                        SELECT language_tag, word_normalized, frequency, last_used
                        FROM learned_words
                        """.trimIndent()
                    )
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS user_word_bigram (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            language_tag TEXT NOT NULL,
                            word_a_normalized TEXT NOT NULL,
                            word_b_normalized TEXT NOT NULL,
                            frequency INTEGER NOT NULL,
                            last_used INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "idx_bigram_lookup ON user_word_bigram(language_tag, word_a_normalized, word_b_normalized)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "idx_bigram_predictions ON user_word_bigram(language_tag, word_a_normalized, frequency)"
                    )
                }
            }

        /**
         * Drops and recreates `clipboard_items` to change `content_hash` from INTEGER to TEXT
         * (SHA-256 hex). Existing clipboard history is cleared — clipboard is ephemeral by design
         * (TTL'd, max 100, never synced).
         */
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP INDEX IF EXISTS idx_clipboard_content_hash")
                    db.execSQL("DROP INDEX IF EXISTS idx_clipboard_timestamp")
                    db.execSQL("DROP INDEX IF EXISTS idx_clipboard_pinned")
                    db.execSQL("DROP TABLE IF EXISTS clipboard_items")
                    db.execSQL(
                        """
                        CREATE TABLE clipboard_items (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            content TEXT NOT NULL,
                            content_hash TEXT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            is_pinned INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX idx_clipboard_content_hash ON clipboard_items(content_hash)")
                    db.execSQL("CREATE INDEX idx_clipboard_timestamp ON clipboard_items(timestamp)")
                    db.execSQL("CREATE INDEX idx_clipboard_pinned ON clipboard_items(is_pinned, timestamp)")
                }
            }

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS user_kanji_frequency (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            reading TEXT NOT NULL,
                            surface TEXT NOT NULL,
                            frequency INTEGER NOT NULL,
                            last_used INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "idx_kanji_freq_lookup ON user_kanji_frequency(reading, surface)"
                    )
                }
            }

        /**
         * @param passphrase SQLCipher key from Android Keystore, or null for unencrypted
         * @throws IllegalStateException if encryption mode changes between calls
         */
        fun getInstance(context: Context, passphrase: ByteArray? = null): KeyboardDatabase =
            instance ?: synchronized(this) {
                instance?.let {
                    check(isEncrypted == (passphrase != null)) {
                        val mode = if (isEncrypted == true) "encryption" else "no encryption"
                        "Encryption mode mismatch: Database instance already created with $mode"
                    }
                    return it
                }

                isEncrypted = passphrase != null

                val builder =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            KeyboardDatabase::class.java,
                            DATABASE_NAME
                        ).addMigrations(
                            MIGRATION_1_2,
                            MIGRATION_3_4,
                            MIGRATION_4_5,
                            MIGRATION_5_6,
                            MIGRATION_6_7,
                            MIGRATION_7_8
                        )
                        .addCallback(
                            object : Callback() {
                                override fun onOpen(db: SupportSQLiteDatabase) {
                                    db.query("PRAGMA journal_mode=WAL").close()
                                    db.query("PRAGMA synchronous=NORMAL").close()
                                }
                            }
                        )

                if (passphrase != null) {
                    builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
                }

                builder
                    .build()
                    .also { instance = it }
            }

        internal fun resetInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
                isEncrypted = null
            }
        }
    }
}
