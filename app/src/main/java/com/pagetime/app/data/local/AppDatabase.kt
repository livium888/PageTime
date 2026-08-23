package com.pagetime.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, BlockedAppEntity::class, UsageEventEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun usageEventDao(): UsageEventDao

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
    }
}
