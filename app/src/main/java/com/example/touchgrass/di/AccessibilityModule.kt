package com.example.touchgrass.di



import com.example.touchgrass.core.analyzer.NodeTreeAnalyzer
import com.example.touchgrass.core.analyzer.YouTubeShortsDetector
import com.example.touchgrass.core.manager.ShortsTrackerManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccessibilityModule {

    @Provides
    @Singleton
    fun provideNodeTreeAnalyzer(): NodeTreeAnalyzer {
        return NodeTreeAnalyzer()
    }

    // In di/AccessibilityModule.kt
    @Provides
    @Singleton
    fun provideYouTubeShortsDetector(): YouTubeShortsDetector {
        return YouTubeShortsDetector()
    }

    @Provides
    @Singleton
    fun provideTrackerManager(detector: YouTubeShortsDetector): ShortsTrackerManager {
        return ShortsTrackerManager(detector)
    }
}