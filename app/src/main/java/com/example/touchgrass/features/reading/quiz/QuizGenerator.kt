package com.example.touchgrass.features.reading.quiz

import java.io.File

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int
)

/**
 * Generates a comprehension quiz from photographed book pages.
 * Provider-agnostic so the backing LLM (Gemini free tier today, Claude or
 * anything else tomorrow) is a one-class swap behind DI.
 */
interface QuizGenerator {
    /** True when the provider is configured (API key present). */
    fun isConfigured(): Boolean

    /**
     * Builds [questionCount] multiple-choice questions answerable ONLY from
     * the supplied page images.
     * @throws QuizGenerationException with a user-presentable message on failure.
     */
    suspend fun generateQuiz(pageImages: List<File>, questionCount: Int = 3): List<QuizQuestion>
}

class QuizGenerationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
