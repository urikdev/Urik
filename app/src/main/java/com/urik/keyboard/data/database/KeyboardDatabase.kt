package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.urik.keyboard.KeyboardConstants
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Room database for keyboard learned words and clipboard history with SQLCipher encryption.
 */
@Database(
    entities = [
        LearnedWord::class,
        LearnedWordFts::class,
        ClipboardItem::class,
        CustomKeyMapping::class,
        UserWordFrequency::class,
        UserWordBigram::class,
    ],
    version = KeyboardConstants.DatabaseConstants.DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class KeyboardDatabase : RoomDatabase() {
    abstract fun learnedWordDao(): LearnedWordDao

    abstract fun clipboardDao(): ClipboardDao

    abstract fun customKeyMappingDao(): CustomKeyMappingDao

    abstract fun userWordFrequencyDao(): UserWordFrequencyDao

    abstract fun userWordBigramDao(): UserWordBigramDao

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
                """,
                    )

                    db.execSQL("DROP INDEX IF EXISTS idx_prefix_suggestions")
                    db.execSQL("DROP INDEX IF EXISTS idx_exact_lookup")
                    db.execSQL("DROP INDEX IF EXISTS idx_learned_words_normalized")
                    db.execSQL("DROP INDEX IF EXISTS idx_learned_words_language_frequency")

                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_exact_lookup ON learned_words(language_tag, word_normalized)")

                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_frequency_recent ON learned_words(language_tag, frequency, last_used)")

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
                        """,
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
                        """,
                    )

                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS idx_user_freq_lookup ON user_word_frequency(language_tag, word_normalized)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_freq_ranking ON user_word_frequency(language_tag, frequency)")

                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO user_word_frequency (language_tag, word_normalized, frequency, last_used)
                        SELECT language_tag, word_normalized, frequency, last_used
                        FROM learned_words
                        """,
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
                        """,
                    )

                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS idx_bigram_lookup ON user_word_bigram(language_tag, word_a_normalized, word_b_normalized)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_bigram_predictions ON user_word_bigram(language_tag, word_a_normalized, frequency)",
                    )
                }
            }

        /**
         * Returns singleton database instance.
         *
         * @param context Application context
         * @param passphrase SQLCipher key from Android Keystore, or null for unencrypted
         * @return Database instance
         * @throws IllegalStateException if encryption settings change between calls
         */
        fun getInstance(
            context: Context,
            passphrase: ByteArray? = null,
        ): KeyboardDatabase =
            instance ?: synchronized(this) {
                instance?.let {
                    check((isEncrypted == (passphrase != null))) {
                        "Encryption mode mismatch: Database instance already created with ${if (isEncrypted == true) "encryption" else "no encryption"}"
                    }
                    return it
                }

                isEncrypted = (passphrase != null)

                val builder =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            KeyboardDatabase::class.java,
                            DATABASE_NAME,
                        ).addMigrations(MIGRATION_1_2, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

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
