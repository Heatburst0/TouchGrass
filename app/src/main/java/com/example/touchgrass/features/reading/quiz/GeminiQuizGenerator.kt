package com.example.touchgrass.features.reading.quiz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.touchgrass.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quiz generation on the Gemini API free tier (multimodal, so page photos go
 * in directly — no OCR step). Key comes from local.properties → BuildConfig.
 */
@Singleton
class GeminiQuizGenerator @Inject constructor() : QuizGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun isConfigured(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    override suspend fun generateQuiz(
        pageImages: List<File>,
        questionCount: Int
    ): List<QuizQuestion> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            throw QuizGenerationException(
                "No AI key configured. Get a free key at aistudio.google.com and add " +
                        "GEMINI_API_KEY=... to local.properties, then rebuild."
            )
        }
        if (pageImages.isEmpty()) throw QuizGenerationException("No page photos to quiz on.")

        val parts = JSONArray().put(JSONObject().put("text", buildPrompt(questionCount)))
        pageImages.forEach { file ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", encodeImage(file))
                )
            )
        }

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("response_mime_type", "application/json")
                    .put("temperature", 0.4)
            )

        val request = Request.Builder()
            .url("$BASE_URL/models/$MODEL:generateContent")
            .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Timber.tag("Quiz").e("Gemini HTTP %d: %s", response.code, text.take(500))
                    throw QuizGenerationException(
                        when (response.code) {
                            400, 401, 403 -> "The AI key was rejected. Check GEMINI_API_KEY in local.properties."
                            429 -> "Free-tier rate limit hit. Try again in a minute."
                            else -> "Quiz service failed (HTTP ${response.code}). Try again."
                        }
                    )
                }
                text
            }
        } catch (e: IOException) {
            throw QuizGenerationException("No connection to the quiz service. Check your internet.", e)
        }

        parseQuestions(responseText, questionCount)
    }

    private fun buildPrompt(questionCount: Int) = """
        You are verifying that someone actually read the book pages in the attached photos.
        Create exactly $questionCount multiple-choice questions that can ONLY be answered by
        someone who read the text on these pages. Base every question strictly on the visible
        text; do not use outside knowledge. Each question has exactly 4 options with one
        correct answer, and wrong options must be plausible.

        Respond with ONLY a JSON array in this exact shape:
        [{"question": "...", "options": ["...","...","...","..."], "correctIndex": 0}]

        If the images do not contain readable book text, respond with:
        {"error": "short reason"}
    """.trimIndent()

    private fun parseQuestions(responseText: String, expected: Int): List<QuizQuestion> {
        try {
            val root = JSONObject(responseText)
            val content = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            if (content.startsWith("{")) {
                val error = JSONObject(content).optString("error")
                throw QuizGenerationException(
                    if (error.isNotBlank()) "Couldn't read those pages: $error"
                    else "The AI returned an unexpected answer. Retake the photos and try again."
                )
            }

            val array = JSONArray(content)
            val questions = (0 until array.length()).map { i ->
                val q = array.getJSONObject(i)
                val options = q.getJSONArray("options")
                QuizQuestion(
                    question = q.getString("question"),
                    options = (0 until options.length()).map(options::getString),
                    correctIndex = q.getInt("correctIndex")
                )
            }.filter { it.options.size >= 2 && it.correctIndex in it.options.indices }

            if (questions.isEmpty()) {
                throw QuizGenerationException("No usable questions came back. Try clearer photos.")
            }
            return questions.take(expected)
        } catch (e: QuizGenerationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Quiz").e(e, "Failed to parse quiz response")
            throw QuizGenerationException("Couldn't understand the quiz service response. Try again.", e)
        }
    }

    /** Downscale + JPEG-compress so multi-page uploads stay small on free tier. */
    private fun encodeImage(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw QuizGenerationException("Couldn't read one of the page photos.")

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MODEL = "gemini-2.0-flash"
        private const val MAX_DIMENSION_PX = 1536
        private const val JPEG_QUALITY = 80
    }
}
