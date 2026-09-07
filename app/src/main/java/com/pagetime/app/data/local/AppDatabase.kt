package com.pagetime.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * learning_cards and learning_review_logs are live again.
 *
 * Both were declared when the app was written, kept through every migration,
 * and read by nothing at all — the generator that filled them was disabled
 * when the app moved to Explain Back, and the DAOs went with it. Keeping the
 * tables rather than dropping them was the right call: a migration that
 * destroys a reader's cards cannot be undone, and the schema was waiting with
 * columns for cloze, a validated source quote, and a per-review audit trail
 * that the chapter flashcard pipeline turned out to need exactly as written.
 *
 * learning_cards now holds generated chapter flashcards; learning_review_logs
 * records every answer given to one, append-only, so the app can say what the
 * reader actually remembers instead of only what it has scheduled.
 */
@Database(
    entities = [
        BookEntity::class,
        BlockedAppEntity::class,
        UsageEventEntity::class,
        LearningCardEntity::class,
        LearningReviewLogEntity::class,
        LearningGenerationEntity::class,
        ConceptEntity::class,
        ConceptRelationshipEntity::class,
        AiUsageEntity::class,
        ExplanationEntity::class,
        LumenCardEntity::class,
        CardEmbeddingEntity::class,
        BookChunkEmbeddingEntity::class
    ],
    version = 19,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun usageEventDao(): UsageEventDao
    abstract fun learningCardDao(): LearningCardDao
    abstract fun learningReviewLogDao(): LearningReviewLogDao
    abstract fun learningGenerationDao(): LearningGenerationDao
    abstract fun conceptDao(): ConceptDao
    abstract fun conceptRelationshipDao(): ConceptRelationshipDao
    abstract fun aiUsageDao(): AiUsageDao
    abstract fun explanationDao(): ExplanationDao
    abstract fun lumenCardDao(): LumenCardDao
    abstract fun cardEmbeddingDao(): CardEmbeddingDao
    abstract fun bookChunkEmbeddingDao(): BookChunkEmbeddingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS usage_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, type TEXT NOT NULL, packageName TEXT, seconds INTEGER NOT NULL)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_events ADD COLUMN windowStart INTEGER")
                db.execSQL("ALTER TABLE usage_events ADD COLUMN windowEnd INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS learning_cards (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, chapterTitle TEXT, prompt TEXT NOT NULL, answer TEXT NOT NULL, explanation TEXT, sourceLocator TEXT, sourceFraction REAL, fsrsCardJson TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastRating INTEGER, reviewCount INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS learning_review_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId TEXT NOT NULL, bookId TEXT NOT NULL, reviewedAt INTEGER NOT NULL, rating INTEGER NOT NULL, scheduledDays INTEGER NOT NULL, elapsedDays INTEGER NOT NULL, wasDue INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_cards_bookId ON learning_cards(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_review_logs_cardId ON learning_review_logs(cardId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN topic TEXT")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN sourceQuote TEXT")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN generatedByAi INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN aiConfidence REAL")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN generationKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_cards_bookId_generationKey ON learning_cards(bookId, generationKey)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS learning_generations (bookId TEXT NOT NULL, generationKey TEXT NOT NULL, chapterIndex INTEGER NOT NULL, status TEXT NOT NULL, cardCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(bookId, generationKey))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_generations_bookId_chapterIndex ON learning_generations(bookId, chapterIndex)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS concepts (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, label TEXT NOT NULL, normalizedLabel TEXT NOT NULL, description TEXT NOT NULL, type TEXT NOT NULL, firstChapterIndex INTEGER NOT NULL, lastChapterIndex INTEGER NOT NULL, sourceQuote TEXT, confidence REAL NOT NULL, mentionCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS concept_relationships (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, sourceConceptId TEXT NOT NULL, targetConceptId TEXT NOT NULL, relationType TEXT NOT NULL, explanation TEXT NOT NULL, sourceQuote TEXT, confidence REAL NOT NULL, firstChapterIndex INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_concepts_bookId ON concepts(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_concepts_bookId_normalizedLabel ON concepts(bookId, normalizedLabel)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_concept_relationships_bookId ON concept_relationships(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_concept_relationships_bookId_sourceConceptId_targetConceptId_relationType ON concept_relationships(bookId, sourceConceptId, targetConceptId, relationType)")
            }
        }

        /** Repairs v7 installs created with inline UNIQUE constraints. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_concepts_bookId_normalizedLabel ON concepts(bookId, normalizedLabel)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_concept_relationships_bookId_sourceConceptId_targetConceptId_relationType ON concept_relationships(bookId, sourceConceptId, targetConceptId, relationType)")
            }
        }

        /** Adds cardType and mcqOptions columns for Wozniak 20-rules card types. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN cardType TEXT NOT NULL DEFAULT 'qa'")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN mcqOptions TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_usage_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId TEXT NOT NULL, operation TEXT NOT NULL, model TEXT NOT NULL, status TEXT NOT NULL, inputCharacters INTEGER NOT NULL, outputItems INTEGER NOT NULL DEFAULT 0, secondaryItems INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, completedAt INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_usage_events_createdAt ON ai_usage_events(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_usage_events_bookId ON ai_usage_events(bookId)")
            }
        }

        /** Adds the explanations table for Feynman-style concept explanations. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS explanations (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        chapterTitle TEXT,
                        conceptLabel TEXT NOT NULL,
                        conceptKeyPoints TEXT NOT NULL,
                        userExplanation TEXT NOT NULL,
                        aiFeedback TEXT,
                        accuracyScore INTEGER,
                        completenessScore INTEGER,
                        clarityScore INTEGER,
                        overallScore REAL,
                        whatTheyGotRight TEXT,
                        whatTheyMissed TEXT,
                        suggestedImprovement TEXT,
                        simplerVersion TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_explanations_bookId_chapterIndex ON explanations(bookId, chapterIndex)")
            }
        }

        /** Adds keywords column for local concept highlight matching. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE concepts ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Luhmann slip-box filing + optional FSRS training for Lumen cards. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // All existing cards land in box 1; addresses are assigned on
                // first read of the slip box (see LumenRepository.ensureAddresses).
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN box INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN indexNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN linksJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN fsrsCardJson TEXT")
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN dueAt INTEGER")
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN lastRating INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lumen_cards_box ON lumen_cards(box)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lumen_cards_dueAt ON lumen_cards(dueAt)")
            }
        }

        /** Structure maps: a card can be marked as a hub note for a cluster. */
        /**
         * Vectors get their own table rather than a column on lumen_cards.
         *
         * The model that produced each one is stored beside it, because two
         * models embed into different spaces and comparing across them returns
         * numbers rather than errors. Without this column a model change would
         * silently degrade every comparison; with it, stale vectors can be
         * found and rebuilt.
         *
         * ON DELETE CASCADE so a deleted card cannot leave a vector behind to
         * match against a note that no longer exists.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS card_embeddings (" +
                        "cardId TEXT NOT NULL PRIMARY KEY, " +
                        "model TEXT NOT NULL, " +
                        "dimensions INTEGER NOT NULL, " +
                        "vector BLOB NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(cardId) REFERENCES lumen_cards(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_card_embeddings_model " +
                        "ON card_embeddings(model)"
                )
            }
        }

        /**
         * Book text as vectors, so a passage can be found by meaning.
         *
         * A separate table from card_embeddings rather than one table with a
         * kind column. They have different keys — a card has an id, a chunk is
         * identified by where it sits in a book — different foreign keys, and
         * very different row counts: a slip box holds hundreds of cards, one
         * novel holds thousands of chunks. Sharing a table would make every
         * card query walk past a book.
         *
         * The chunk's text is stored beside its vector so a search result can
         * be shown without re-opening and re-parsing the EPUB. Roughly 2 KB a
         * row, so about 6 MB for a 300-page book — derived data, droppable and
         * rebuildable whenever the reader wants the space.
         *
         * ON DELETE CASCADE: removing a book removes its index, which would
         * otherwise go on answering searches about a book that is gone.
         */
        /**
         * Wakes up learning_cards.
         *
         * The table has been declared since it was written and has never had a
         * DAO, so it is guaranteed empty and the defaults below are for the
         * schema's sake rather than for any row. It gains the two columns a
         * generated prompt needs that a hand-made one did not: whether the
         * reader has accepted it, and when it is next due — the latter
         * duplicated out of the FSRS JSON because a due query has to be a
         * WHERE clause.
         */
        /**
         * Records what a Gemini request actually cost.
         *
         * Until now the log stored the number of CHARACTERS sent and the usage
         * screen divided by 3.5 to guess at tokens, while counting no output at
         * all — and every response had carried the exact figures all along in a
         * usageMetadata block nothing parsed.
         *
         * Nullable on purpose. Existing rows were never measured, and the
         * on-device model reports nothing, so null means "not measured" while
         * zero would mean "measured, and free".
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_usage_events ADD COLUMN promptTokens INTEGER")
                db.execSQL("ALTER TABLE ai_usage_events ADD COLUMN outputTokens INTEGER")
                db.execSQL("ALTER TABLE ai_usage_events ADD COLUMN thinkingTokens INTEGER")
                db.execSQL("ALTER TABLE ai_usage_events ADD COLUMN cachedTokens INTEGER")
                db.execSQL("ALTER TABLE ai_usage_events ADD COLUMN totalTokens INTEGER")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE learning_cards ADD COLUMN status TEXT NOT NULL DEFAULT 'kept'"
                )
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN dueAt INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_learning_cards_status_dueAt " +
                        "ON learning_cards(status, dueAt)"
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS book_chunk_embeddings (" +
                        "bookId TEXT NOT NULL, " +
                        "chapterIndex INTEGER NOT NULL, " +
                        "ordinal INTEGER NOT NULL, " +
                        "startOffset INTEGER NOT NULL, " +
                        "endOffset INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "model TEXT NOT NULL, " +
                        "dimensions INTEGER NOT NULL, " +
                        "vector BLOB NOT NULL, " +
                        "indexedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(bookId, chapterIndex, ordinal), " +
                        "FOREIGN KEY(bookId) REFERENCES books(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_book_chunk_embeddings_bookId_model " +
                        "ON book_chunk_embeddings(bookId, model)"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lumen_cards ADD COLUMN isHub INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lumen_cards (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "bookId TEXT NOT NULL, " +
                        "front TEXT NOT NULL, " +
                        "back TEXT NOT NULL, " +
                        "quote TEXT NOT NULL, " +
                        "sourceLocatorJson TEXT, " +
                        "sourceChapterIndex INTEGER, " +
                        "sourceFraction REAL NOT NULL, " +
                        "snippetsJson TEXT NOT NULL, " +
                        "keywords TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lumen_cards_bookId ON lumen_cards(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lumen_cards_updatedAt ON lumen_cards(updatedAt)")
            }
        }
    }
}
