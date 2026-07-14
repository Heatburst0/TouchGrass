package com.example.touchgrass.di

import com.example.touchgrass.features.reading.quiz.GeminiQuizGenerator
import com.example.touchgrass.features.reading.quiz.QuizGenerator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class QuizModule {

    /** Swap the LLM provider here (e.g. a Claude-backed generator later). */
    @Binds
    @Singleton
    abstract fun bindQuizGenerator(impl: GeminiQuizGenerator): QuizGenerator
}
