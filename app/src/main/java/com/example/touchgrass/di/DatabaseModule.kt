package com.example.touchgrass.di

import android.content.Context
import androidx.room.Room
import com.example.touchgrass.core.data.db.BookDao
import com.example.touchgrass.core.data.db.CommitmentDao
import com.example.touchgrass.core.data.db.GitHubGoalDao
import com.example.touchgrass.core.data.db.PageReadDao
import com.example.touchgrass.core.data.db.PointsDao
import com.example.touchgrass.core.data.db.TouchGrassDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TouchGrassDatabase =
        Room.databaseBuilder(context, TouchGrassDatabase::class.java, "touchgrass.db")
            .addMigrations(
                TouchGrassDatabase.MIGRATION_1_2,
                TouchGrassDatabase.MIGRATION_2_3,
                TouchGrassDatabase.MIGRATION_3_4,
                TouchGrassDatabase.MIGRATION_4_5
            )
            .build()

    @Provides
    fun provideBookDao(db: TouchGrassDatabase): BookDao = db.bookDao()

    @Provides
    fun providePageReadDao(db: TouchGrassDatabase): PageReadDao = db.pageReadDao()

    @Provides
    fun providePointsDao(db: TouchGrassDatabase): PointsDao = db.pointsDao()

    @Provides
    fun provideCommitmentDao(db: TouchGrassDatabase): CommitmentDao = db.commitmentDao()

    @Provides
    fun provideGitHubGoalDao(db: TouchGrassDatabase): GitHubGoalDao = db.gitHubGoalDao()
}
