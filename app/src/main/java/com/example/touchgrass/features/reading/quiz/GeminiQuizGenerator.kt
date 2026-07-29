package com.example.touchgrass.features.reading.quiz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.touchgrass.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Quiz generation on the Gemini API free tier. Two paths, both provider-agnostic
 * behind [QuizGenerator]: a TEXT path (used after on-device OCR — cheap, few
 * tokens) and a multimodal IMAGE path (vision fallback for photos OCR couldn't
 * read). Key comes from local.properties → BuildConfig.
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
        ensureConfigured()
        if (pageImages.isEmpty()) throw QuizGenerationException("No page photos to quiz on.")

        val parts = JSONArray().put(JSONObject().put("text", buildImagePrompt(questionCount)))
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
        runGeneration(parts, questionCount)
    }

    override suspend fun generateQuizFromText(
        pageText: String,
        questionCount: Int
    ): List<QuizQuestion> = withContext(Dispatchers.IO) {
        ensureConfigured()
        if (pageText.isBlank()) throw QuizGenerationException("No readable text to quiz on.")

        val parts = JSONArray().put(JSONObject().put("text", buildTextPrompt(questionCount, pageText)))
        runGeneration(parts, questionCount)
    }

    private fun ensureConfigured() {
        if (!isConfigured()) {
            throw QuizGenerationException(
                "No AI key configured. Get a free key at aistudio.google.com and add " +
                        "GEMINI_API_KEY=... to local.properties, then rebuild."
            )
        }
    }

    /** Shared request path: wrap [parts] in a request body, walk the model
     *  fallback list, and parse the winning response. */
    private suspend fun runGeneration(parts: JSONArray, questionCount: Int): List<QuizQuestion> {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("response_mime_type", "application/json")
                    .put("temperature", 0.4)
            )

        val payload = body.toString()

        // Free-tier model availability shifts as Google deprecates models
        // (e.g. gemini-2.0-flash now has a free-tier limit of 0). Walk the
        // list until one answers instead of hard-coding a single model.
        var lastFailure: QuizGenerationException? = null
        for (model in MODELS) {
            when (val outcome = requestWithRetry(model, payload)) {
                is Outcome.Success -> {
                    if (model != MODELS.first()) {
                        Timber.tag("Quiz").i("Quiz served by fallback model %s", model)
                    }
                    return parseQuestions(outcome.body, questionCount)
                }
                is Outcome.TryNextModel -> lastFailure = outcome.failure
                is Outcome.Fatal -> throw outcome.failure
            }
        }
        throw lastFailure ?: QuizGenerationException("Quiz service unavailable. Try again later.")
    }

    private sealed interface Outcome {
        data class Success(val body: String) : Outcome
        data class TryNextModel(val failure: QuizGenerationException) : Outcome
        data class Fatal(val failure: QuizGenerationException) : Outcome
    }

    /**
     * Calls one model, retrying transient overloads (503/500) with exponential
     * backoff. Quota/retired errors (429/404) fall through to the next model;
     * bad-key errors (400/401/403) are fatal.
     */
    private suspend fun requestWithRetry(model: String, payload: String): Outcome {
        var overloadFailure: QuizGenerationException? = null
        repeat(MAX_RETRIES) { attempt ->
            val request = Request.Builder()
                .url("$BASE_URL/models/$model:generateContent")
                .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> return Outcome.Success(text)

                        // Temporarily overloaded / server error → retry same model
                        response.code == 503 || response.code == 500 -> {
                            Timber.tag("Quiz").w(
                                "Model %s overloaded (HTTP %d), attempt %d/%d",
                                model, response.code, attempt + 1, MAX_RETRIES
                            )
                            overloadFailure = QuizGenerationException(
                                "The quiz service is busy right now. Please try again in a moment."
                            )
                        }

                        // Quota zeroed / model retired / rate limited → next model
                        response.code == 429 || response.code == 404 -> {
                            Timber.tag("Quiz").w(
                                "Model %s unavailable (HTTP %d): %s",
                                model, response.code, text.take(300)
                            )
                            return Outcome.TryNextModel(
                                QuizGenerationException(
                                    "Free-tier quota is unavailable right now (tried " +
                                            "${MODELS.joinToString()}). Check ai.dev/rate-limit " +
                                            "or try again later."
                                )
                            )
                        }

                        response.code == 400 || response.code == 401 || response.code == 403 ->
                            return Outcome.Fatal(
                                QuizGenerationException(
                                    "The AI key was rejected. Check GEMINI_API_KEY in local.properties."
                                )
                            )

                        else -> return Outcome.Fatal(
                            QuizGenerationException("Quiz service failed (HTTP ${response.code}). Try again.")
                        )
                    }
                }
            } catch (e: IOException) {
                Timber.tag("Quiz").w(e, "Network error on %s, attempt %d/%d", model, attempt + 1, MAX_RETRIES)
                overloadFailure = QuizGenerationException(
                    "No connection to the quiz service. Check your internet.", e
                )
            }

            // Backoff before the next attempt (skip the wait after the last one)
            if (attempt < MAX_RETRIES - 1) delay(BASE_BACKOFF_MS * (1L shl attempt))
        }
        // Exhausted retries on this model — let the caller try the next one
        return Outcome.TryNextModel(
            overloadFailure ?: QuizGenerationException("Quiz service unavailable. Try again later.")
        )
    }

    private fun buildImagePrompt(questionCount: Int) = """
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

    private fun buildTextPrompt(questionCount: Int, pageText: String) = """
        You are verifying that someone actually read the following book text.
        Create exactly $questionCount multiple-choice questions that can ONLY be answered by
        someone who read THIS text. Base every question strictly on it; do not use outside
        knowledge. Each question has exactly 4 options with one correct answer, and wrong
        options must be plausible.

        Respond with ONLY a JSON array in this exact shape:
        [{"question": "...", "options": ["...","...","...","..."], "correctIndex": 0}]

        If the text is too short or unreadable to make questions, respond with:
        {"error": "short reason"}

        TEXT:
        ${pageText.take(MAX_TEXT_CHARS)}
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

        /**
         * Tried in order; first model that answers wins. All multimodal.
         * We lead with the "-latest" ALIASES because pinned versions
         * (e.g. gemini-2.5-flash) get gated to "no longer available to new
         * users" 404s, while the aliases always resolve to a current model.
         */
        private val MODELS = listOf(
            "gemini-flash-latest",
            "gemini-flash-lite-latest"
        )
        private const val MAX_DIMENSION_PX = 1536
        private const val JPEG_QUALITY = 80
        private const val MAX_TEXT_CHARS = 8000    // cap OCR text sent to the model
        private const val MAX_RETRIES = 3          // per model, for 503/500/network
        private const val BASE_BACKOFF_MS = 1500L  // 1.5s, 3s between retries
    }
}
