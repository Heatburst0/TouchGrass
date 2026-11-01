package com.example.touchgrass.features.reading.quiz

import com.example.touchgrass.features.reading.ocr.TextExtractor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid quiz pipeline used by both the PDF reader and the paper-book flow:
 *
 *   1. On-device OCR (ML Kit) reads the page text locally — free, private, offline.
 *   2. If enough text came back, the LLM only WRITES questions from that text
 *      (cheap: a tiny payload, easy on the free tier). If the AI is unreachable or
 *      misses [AI_DEADLINE_MS], an offline fill-in-the-blank quiz from the same text
 *      is served instead, so a bad connection never means a minutes-long wait.
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

    /** OCR only — lets the reader prefetch page text while the user is still reading. */
    suspend fun readText(pageImages: List<File>): String = textExtractor.extract(pageImages)

    suspend fun makeQuiz(pageImages: List<File>, questionCount: Int = 3): List<QuizQuestion> {
        if (pageImages.isEmpty()) throw QuizGenerationException("No pages to quiz on.")
        val text = textExtractor.extract(pageImages)
        return makeQuizFromText(text, questionCount) { generator.generateQuiz(pageImages, questionCount) }
    }

    /**
     * Quiz from already-OCR'd [text]; [visionFallback] runs only when the text is too
     * sparse to trust (it may render/photograph pages lazily).
     */
    suspend fun makeQuizFromText(
        text: String,
        questionCount: Int = 3,
        visionFallback: suspend () -> List<QuizQuestion>
    ): List<QuizQuestion> {
        if (text.length < MIN_TEXT_CHARS) {
            Timber.tag("Quiz").i("OCR sparse (%d chars) — vision fallback", text.length)
            return visionFallback()
        }
        val offline = runCatching { LocalQuizGenerator.generate(text, questionCount) }.getOrNull()
        if (offline == null || !generator.isConfigured()) {
            Timber.tag("Quiz").i("OCR got %d chars — single path", text.length)
            return offline ?: generator.generateQuizFromText(text, questionCount)
        }
        return try {
            withTimeout(AI_DEADLINE_MS) { generator.generateQuizFromText(text, questionCount) }
        } catch (e: TimeoutCancellationException) {
            Timber.tag("Quiz").w("AI quiz missed %d ms deadline — offline quiz", AI_DEADLINE_MS)
            offline
        } catch (e: QuizGenerationException) {
            Timber.tag("Quiz").w(e, "AI quiz failed — offline quiz")
            offline
        }
    }

    private companion object {
        // Below this, OCR probably failed (blur/glare/angle) — use the vision path.
        const val MIN_TEXT_CHARS = 150

        // A healthy AI answer takes a few seconds; past this, don't keep the reader waiting.
        const val AI_DEADLINE_MS = 12_000L
    }
}
