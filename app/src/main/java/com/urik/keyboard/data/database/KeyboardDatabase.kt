package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.urik.keyboard.KeyboardConstants
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Room database for keyboard learned words with SQLCipher encryption.
 *
 * Entities:
 * - LearnedWord: User-typed words with frequency tracking
 * - LearnedWordFts: Full-text search table for prefix matching
 *
 * Encryption: Optional SQLCipher passphrase from Android Keystore.
 */
@Database(
    entities = [
        LearnedWord::class,
        LearnedWordFts::class,
    ],
    version = KeyboardConstants.DatabaseConstants.DATABASE_VERSION,
    exportSchema = true,
)
abstract class KeyboardDatabase : RoomDatabase() {
    abstract fun learnedWordDao(): LearnedWordDao

    companion object {
        const val DATABASE_NAME = "keyboard_database"

        @Suppress("ktlint:standard:property-naming")
        @Volatile
        private var INSTANCE: KeyboardDatabase? = null

        @Volatile
        private var instanceEncrypted: Boolean? = null

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

                    db.execSQL("DROP INDEX IF EXISTS idx_learned_words_normalized")

                    db.execSQL("DROP INDEX IF EXISTS idx_prefix_suggestions")
                    db.execSQL("DROP INDEX IF EXISTS idx_exact_lookup")

                    db.execSQL(
                        """
                    CREATE UNIQUE INDEX idx_exact_lookup 
                    ON learned_words(language_tag, word_normalized)
                """,
                    )
                }
            }

        /**
         * Returns singleton database instance.
         *
         * Thread-safe singleton with encryption consistency validation.
         * passphrase parameter only used on first call - subsequent calls validate consistency.
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
            INSTANCE ?: synchronized(this) {
                INSTANCE?.let {
                    validatePassphraseConsistency(passphrase)
                    return it
                }

                instanceEncrypted = (passphrase != null)

                val builder =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            KeyboardDatabase::class.java,
                            DATABASE_NAME,
                        ).addCallback(DatabaseCallback())
                        .addMigrations(MIGRATION_1_2)

                try {
                    if (passphrase != null) {
                        val factory = SupportOpenHelperFactory(passphrase)
                        builder.openHelperFactory(factory)
                    }

                    val instance = builder.build()
                    INSTANCE = instance
                    instance
                } finally {
                    passphrase?.fill(0)
                }
            }

        private fun validatePassphraseConsistency(passphrase: ByteArray?) {
            val requestEncrypted = (passphrase != null)
            if (instanceEncrypted != requestEncrypted) {
                throw IllegalStateException(
                    "Database instance already created with ${if (instanceEncrypted == true) "encryption" else "no encryption"}",
                )
            }
        }

        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS idx_learned_words_language_frequency 
                    ON learned_words(language_tag, frequency DESC, last_used DESC)
                """,
                )
            }
        }
    }
}
