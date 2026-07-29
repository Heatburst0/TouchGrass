package com.example.touchgrass.features.reading.ocr

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device OCR via Google ML Kit (Latin script). Extracts text from page photos
 * locally — no network, nothing leaves the device — so the LLM only has to WRITE
 * questions, not read the image. Runs entirely offline and is far cheaper on the
 * free tier (text is tiny vs. token-heavy images).
 *
 * Best-effort: a page that fails OCR is skipped. The [com.example.touchgrass
 * .features.reading.quiz.QuizCoordinator] decides, from how much text came back,
 * whether to use the text or fall back to sending images to the vision model.
 */
@Singleton
class TextExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extract(images: List<File>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        for (file in images) {
            try {
                val input = InputImage.fromFilePath(context, Uri.fromFile(file))
                val result = Tasks.await(recognizer.process(input))
                if (result.text.isNotBlank()) sb.append(result.text).append("\n\n")
            } catch (e: Exception) {
                Timber.tag("OCR").w(e, "OCR failed for %s", file.name)
            }
        }
        sb.toString().trim()
    }
}
