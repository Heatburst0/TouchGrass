package com.example.touchgrass.di

import com.example.touchgrass.core.analyzer.NodeTreeAnalyzer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime scope for background work (persistence, flow sharing). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AccessibilityModule {

    @Provides
    @Singleton
    fun provideNodeTreeAnalyzer(): NodeTreeAnalyzer {
        return NodeTreeAnalyzer()
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // YouTubeShortsDetector, SettingsRepository and ShortsTrackerManager are
    // constructor-injected (@Inject/@Singleton on the classes themselves).
}
