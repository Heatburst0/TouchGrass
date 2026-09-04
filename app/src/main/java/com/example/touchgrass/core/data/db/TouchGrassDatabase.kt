package com.example.touchgrass.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        PageReadEntity::class,
        PointsEntryEntity::class,
        GoalEntity::class,
        FocusSessionEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class TouchGrassDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageReadDao(): PageReadDao
    abstract fun pointsDao(): PointsDao
    abstract fun goalDao(): GoalDao
    abstract fun focusSessionDao(): FocusSessionDao

    companion object {
        /** v2: physical (paper) books with AI quiz verification. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN type TEXT NOT NULL DEFAULT 'PDF'")
            }
        }

        /** v3: quiz gating for PDFs — pages start unverified, quiz pays out.
         *  Existing rows default to verified so already-earned pages keep counting. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page_reads ADD COLUMN verified INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v5: recurring GitHub daily-commit goals, verified via the API. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `github_goals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `owner` TEXT NOT NULL,
                        `repo` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `createdDate` TEXT NOT NULL,
                        `lastSuccessDate` TEXT,
                        `lastSettledDate` TEXT,
                        `currentStreak` INTEGER NOT NULL,
                        `bestStreak` INTEGER NOT NULL,
                        `rewardPoints` INTEGER NOT NULL,
                        `penaltyShorts` INTEGER NOT NULL,
                        `active` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v6: unified goals table (Goal + Verifier refactor, Phase 0). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `goals` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `schedule` TEXT NOT NULL,
                `target` INTEGER NOT NULL,
                `unit` TEXT NOT NULL,
                `progress` INTEGER NOT NULL,
                `rewardPoints` INTEGER NOT NULL,
                `penaltyShorts` INTEGER NOT NULL,
                `configJson` TEXT NOT NULL,
                `stateJson` TEXT NOT NULL,
                `active` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `deadlineAt` INTEGER
            )
            """.trimIndent()
                )
            }
        }

        /** v7: register the goals entity with Room. The table already exists from
         *  v6 (created via raw SQL); this bumps the version so Room re-validates
         *  now that GoalEntity is a real @Entity. CREATE IF NOT EXISTS keeps it
         *  safe for fresh v6 installs too. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `goals` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `schedule` TEXT NOT NULL,
                `target` INTEGER NOT NULL,
                `unit` TEXT NOT NULL,
                `progress` INTEGER NOT NULL,
                `rewardPoints` INTEGER NOT NULL,
                `penaltyShorts` INTEGER NOT NULL,
                `configJson` TEXT NOT NULL,
                `stateJson` TEXT NOT NULL,
                `active` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `deadlineAt` INTEGER
            )
            """.trimIndent()
                )
            }
        }


        /** v9: trim the legacy tables. Pledges and GitHub goals now live entirely
         *  in `goals`; their old standalone tables (and the one-time copy-in
         *  migrations that fed them) are gone. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `commitments`")
                db.execSQL("DROP TABLE IF EXISTS `github_goals`")
            }
        }

        /** v8: focus session history — the "N successful sessions" record. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `focus_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `endedAt` INTEGER NOT NULL,
                `plannedFocusMin` INTEGER NOT NULL,
                `focusedMin` INTEGER NOT NULL,
                `cycles` INTEGER NOT NULL,
                `violations` INTEGER NOT NULL,
                `strict` INTEGER NOT NULL,
                `outcome` TEXT NOT NULL
            )
            """.trimIndent()
                )
            }
        }

        /** v4: commitments — verified pledges with a reward/penalty stake. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `commitments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `pillar` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `targetAmount` INTEGER NOT NULL,
                        `unitLabel` TEXT NOT NULL,
                        `progress` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `deadlineAt` INTEGER NOT NULL,
                        `rewardPoints` INTEGER NOT NULL,
                        `penaltyShorts` INTEGER NOT NULL,
                        `status` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
