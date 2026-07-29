package com.example.touchgrass.features.reading.quiz

import com.example.touchgrass.features.reading.ocr.TextExtractor
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid quiz pipeline used by both the PDF reader and the paper-book flow:
 *
 *   1. On-device OCR (ML Kit) reads the page text locally — free, private, offline.
 *   2. If enough text came back, the LLM only WRITES questions from that text
 *      (cheap: a tiny payload, easy on the free tier).
 *   3. If OCR yielded too little (blur / glare / angle / failure), fall back to
 *      sending the images to the LLM's vision model, which tolerates messy photos.
 *
 * Both reading flows go through this, so the hybrid lives in exactly one place.
 */
@Singleton
class QuizCoordinator @Inject constructor(
    private val textExtractor: TextExtractor,
    private val generator: QuizGenerator
) {
    fun isConfigured(): Boolean = generator.isConfigured()

    suspend fun makeQuiz(pageImages: List<File>, questionCount: Int = 3): List<QuizQuestion> {
        if (pageImages.isEmpty()) throw QuizGenerationException("No pages to quiz on.")

        val text = textExtractor.extract(pageImages)
        return if (text.length >= MIN_TEXT_CHARS) {
            Timber.tag("Quiz").i("OCR got %d chars — text path", text.length)
            generator.generateQuizFromText(text, questionCount)
        } else {
            Timber.tag("Quiz").i("OCR sparse (%d chars) — vision fallback", text.length)
            generator.generateQuiz(pageImages, questionCount)
        }
    }

    private companion object {
        // Below this, OCR probably failed (blur/glare/angle) — use the vision path.
        const val MIN_TEXT_CHARS = 150
    }
}
