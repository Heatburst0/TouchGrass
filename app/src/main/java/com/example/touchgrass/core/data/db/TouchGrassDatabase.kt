package com.example.touchgrass.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, PageReadEntity::class, PointsEntryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TouchGrassDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageReadDao(): PageReadDao
    abstract fun pointsDao(): PointsDao

    companion object {
        /** v2: physical (paper) books with AI quiz verification. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN type TEXT NOT NULL DEFAULT 'PDF'")
            }
        }
    }
}
