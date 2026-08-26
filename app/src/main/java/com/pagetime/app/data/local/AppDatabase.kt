package com.pagetime.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        ExplanationEntity::class
    ],
    version = 11,
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
    }
}
