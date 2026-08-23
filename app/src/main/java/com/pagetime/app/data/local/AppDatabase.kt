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
        LearningGenerationEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun usageEventDao(): UsageEventDao
    abstract fun learningCardDao(): LearningCardDao
    abstract fun learningReviewLogDao(): LearningReviewLogDao
    abstract fun learningGenerationDao(): LearningGenerationDao

    companion object {
        /** v2: adds the usage_events ledger table (existing data preserved). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS usage_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "packageName TEXT, " +
                        "seconds INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v3: usage_events gains the wall-clock window columns that let the
         * UsageStats reconciler prove which time was already charged (prevents
         * double-charging when both the live ticker and the reconciler run).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_events ADD COLUMN windowStart INTEGER")
                db.execSQL("ALTER TABLE usage_events ADD COLUMN windowEnd INTEGER")
            }
        }

        /** v4: adds offline comprehension cards and immutable review logs. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS learning_cards (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "bookId TEXT NOT NULL, " +
                        "chapterIndex INTEGER NOT NULL, " +
                        "chapterTitle TEXT, " +
                        "prompt TEXT NOT NULL, " +
                        "answer TEXT NOT NULL, " +
                        "explanation TEXT, " +
                        "sourceLocator TEXT, " +
                        "sourceFraction REAL, " +
                        "fsrsCardJson TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "lastRating INTEGER, " +
                        "reviewCount INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS learning_review_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "cardId TEXT NOT NULL, " +
                        "bookId TEXT NOT NULL, " +
                        "reviewedAt INTEGER NOT NULL, " +
                        "rating INTEGER NOT NULL, " +
                        "scheduledDays INTEGER NOT NULL, " +
                        "elapsedDays INTEGER NOT NULL, " +
                        "wasDue INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_cards_bookId ON learning_cards(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_review_logs_cardId ON learning_review_logs(cardId)")
            }
        }

        /** v5: adds Gemini provenance and generation deduplication metadata. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN topic TEXT")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN sourceQuote TEXT")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN generatedByAi INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN aiConfidence REAL")
                db.execSQL("ALTER TABLE learning_cards ADD COLUMN generationKey TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_learning_cards_bookId_generationKey " +
                        "ON learning_cards(bookId, generationKey)"
                )
            }
        }

        /** v6: persists one Gemini generation claim per bounded context window. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS learning_generations (" +
                        "bookId TEXT NOT NULL, " +
                        "generationKey TEXT NOT NULL, " +
                        "chapterIndex INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "cardCount INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(bookId, generationKey))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_learning_generations_bookId_chapterIndex " +
                        "ON learning_generations(bookId, chapterIndex)"
                )
            }
        }
    }
}
