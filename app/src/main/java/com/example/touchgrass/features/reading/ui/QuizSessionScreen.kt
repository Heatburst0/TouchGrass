package com.example.touchgrass.features.reading.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.reading.data.BookRepository
import com.example.touchgrass.features.reading.quiz.QuizGenerationException
import com.example.touchgrass.features.reading.quiz.QuizGenerator
import com.example.touchgrass.features.reading.quiz.QuizQuestion
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

sealed interface QuizPhase {
    data object Capture : QuizPhase
    data object Generating : QuizPhase
    data class Quiz(val questions: List<QuizQuestion>) : QuizPhase
    data class Result(
        val correct: Int,
        val total: Int,
        val passed: Boolean,
        val pagesCredited: Int,
        val pointsEarned: Int
    ) : QuizPhase
}

data class QuizUiState(
    val bookTitle: String = "",
    val phase: QuizPhase = QuizPhase.Capture,
    val photos: List<File> = emptyList(),
    val answers: Map<Int, Int> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class QuizSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repo: BookRepository,
    private val rewards: RewardsManager,
    private val quizGenerator: QuizGenerator
) : ViewModel() {

    private val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    private val _uiState = MutableStateFlow(QuizUiState())
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    val points: StateFlow<Int> = rewards.pointsBalance

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(bookTitle = repo.getBook(bookId)?.title ?: "") }
        }
    }

    fun newPhotoFile(): File {
        val dir = File(context.cacheDir, "quiz").apply { mkdirs() }
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    fun onPhotoTaken(file: File) {
        if (file.exists() && file.length() > 0) {
            _uiState.update { it.copy(photos = it.photos + file, error = null) }
        }
    }

    fun importFromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val copied = uris.mapNotNull { uri ->
                runCatching {
                    val file = newPhotoFile()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    file.takeIf { it.length() > 0 }
                }.getOrNull()
            }
            _uiState.update { it.copy(photos = it.photos + copied, error = null) }
        }
    }

    fun removePhoto(file: File) {
        file.delete()
        _uiState.update { it.copy(photos = it.photos - file) }
    }

    fun generateQuiz() {
        val photos = _uiState.value.photos
        if (photos.isEmpty()) return
        _uiState.update { it.copy(phase = QuizPhase.Generating, answers = emptyMap(), error = null) }
        viewModelScope.launch {
            try {
                val questions = quizGenerator.generateQuiz(photos)
                _uiState.update { it.copy(phase = QuizPhase.Quiz(questions)) }
            } catch (e: QuizGenerationException) {
                _uiState.update { it.copy(phase = QuizPhase.Capture, error = e.message) }
            } catch (e: Exception) {
                Timber.tag("Quiz").e(e, "Quiz generation failed")
                _uiState.update {
                    it.copy(phase = QuizPhase.Capture, error = "Something went wrong. Try again.")
                }
            }
        }
    }

    fun selectAnswer(questionIndex: Int, optionIndex: Int) {
        _uiState.update { it.copy(answers = it.answers + (questionIndex to optionIndex)) }
    }

    fun submit() {
        val state = _uiState.value
        val quiz = state.phase as? QuizPhase.Quiz ?: return
        if (state.answers.size < quiz.questions.size) return

        val correct = quiz.questions.withIndex()
            .count { (i, q) -> state.answers[i] == q.correctIndex }
        // Pass bar: at least 2 of 3 (score >= 2/3 of total)
        val passed = correct * 3 >= quiz.questions.size * 2

        viewModelScope.launch {
            var credited = 0
            var earned = 0
            if (passed) {
                val pages = repo.creditPhysicalPages(bookId, state.photos.size)
                credited = pages.size
                pages.forEach { rewards.awardPageRead(bookId, it) }
                earned = credited * RewardsManager.POINTS_PER_PAGE
                // Session photos are consumed once credited
                withContext(Dispatchers.IO) { state.photos.forEach(File::delete) }
            }
            _uiState.update {
                it.copy(
                    phase = QuizPhase.Result(
                        correct = correct,
                        total = quiz.questions.size,
                        passed = passed,
                        pagesCredited = credited,
                        pointsEarned = earned
                    ),
                    photos = if (passed) emptyList() else it.photos
                )
            }
        }
    }

    /** After a failed quiz: new questions on the same photos. */
    fun retry() = generateQuiz()

    /** After a pass: back to capture for the next reading session. */
    fun startNewSession() {
        _uiState.update { it.copy(phase = QuizPhase.Capture, answers = emptyMap(), error = null) }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun QuizSessionScreen(
    onDone: () -> Unit,
    viewModel: QuizSessionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val points by viewModel.points.collectAsState()
    val context = LocalContext.current

    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingPhoto
        if (success && file != null) viewModel.onPhotoTaken(file)
        pendingPhoto = null
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris -> viewModel.importFromGallery(uris) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.bookTitle.ifBlank { "Reading session" },
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Text(
                    text = "Photo-verified reading",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            PointsChip(points)
        }

        Spacer(Modifier.height(20.dp))

        when (val phase = state.phase) {
            QuizPhase.Capture -> CaptureContent(
                photos = state.photos,
                error = state.error,
                onTakePhoto = {
                    val file = viewModel.newPhotoFile()
                    pendingPhoto = file
                    takePicture.launch(
                        FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                    )
                },
                onPickImages = {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemovePhoto = viewModel::removePhoto,
                onGenerate = viewModel::generateQuiz
            )

            QuizPhase.Generating -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = GrassGreen)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Reading your pages and writing the quiz...",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }

            is QuizPhase.Quiz -> QuizContent(
                questions = phase.questions,
                answers = state.answers,
                onSelect = viewModel::selectAnswer,
                onSubmit = viewModel::submit
            )

            is QuizPhase.Result -> ResultContent(
                result = phase,
                onRetry = viewModel::retry,
                onNewSession = viewModel::startNewSession,
                onDone = onDone
            )
        }
    }
}

@Composable
private fun CaptureContent(
    photos: List<File>,
    error: String?,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onRemovePhoto: (File) -> Unit,
    onGenerate: () -> Unit
) {
    Text(
        text = "Photograph each page you just read. After a short AI quiz proves you read them, " +
                "every page earns ${RewardsManager.POINTS_PER_PAGE} pts.",
        color = TextSecondary,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )

    Spacer(Modifier.height(16.dp))

    if (photos.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(photos, key = { it.absolutePath }) { file ->
                PhotoThumbnail(file = file, onRemove = { onRemovePhoto(file) })
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onTakePhoto,
            modifier = Modifier.weight(1f).height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = InkElevated, contentColor = TextPrimary)
        ) {
            Text("Take photo", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onPickImages,
            modifier = Modifier.weight(1f).height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = InkElevated, contentColor = TextPrimary)
        ) {
            Text("From gallery", fontWeight = FontWeight.SemiBold)
        }
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(text = it, color = AmberWarn, fontSize = 13.sp, lineHeight = 18.sp)
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = onGenerate,
        enabled = photos.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = GrassGreen,
            contentColor = Ink,
            disabledContainerColor = InkBorder,
            disabledContentColor = TextSecondary
        )
    ) {
        Text(
            text = if (photos.isEmpty()) "Add page photos first"
            else "Start quiz (${photos.size} page${if (photos.size == 1) "" else "s"})",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PhotoThumbnail(file: File, onRemove: () -> Unit) {
    val bitmap = remember(file.absolutePath) { decodeThumbnail(file) }
    Box {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, InkBorder, RoundedCornerShape(12.dp))
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.8f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Text("x", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QuizContent(
    questions: List<QuizQuestion>,
    answers: Map<Int, Int>,
    onSelect: (Int, Int) -> Unit,
    onSubmit: () -> Unit
) {
    Text(
        text = "Answer from what you just read - no peeking.",
        color = TextSecondary,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(16.dp))

    questions.forEachIndexed { qIndex, question ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(InkElevated)
                .border(1.dp, InkBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "${qIndex + 1}. ${question.question}",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(12.dp))
            question.options.forEachIndexed { oIndex, option ->
                val selected = answers[qIndex] == oIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) GrassGreen.copy(alpha = 0.12f) else Ink)
                        .border(
                            1.dp,
                            if (selected) GrassGreen else InkBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelect(qIndex, oIndex) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (selected) GrassGreen else TextSecondary, CircleShape)
                            .background(if (selected) GrassGreen else Ink)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = option,
                        color = if (selected) TextPrimary else TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Button(
        onClick = onSubmit,
        enabled = answers.size == questions.size,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = GrassGreen,
            contentColor = Ink,
            disabledContainerColor = InkBorder,
            disabledContentColor = TextSecondary
        )
    ) {
        Text(
            text = if (answers.size < questions.size)
                "Answer all ${questions.size} questions" else "Submit answers",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ResultContent(
    result: QuizPhase.Result,
    onRetry: () -> Unit,
    onNewSession: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${result.correct}/${result.total}",
            color = if (result.passed) GrassGreen else DangerRed,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (result.passed) "Verified. You actually read it."
            else "Not quite - that didn't look like a careful read.",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (result.passed)
                "${result.pagesCredited} page${if (result.pagesCredited == 1) "" else "s"} credited  ·  +${result.pointsEarned} pts"
            else "Re-read the pages, then take a fresh quiz.",
            color = if (result.passed) GrassGreen else TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        if (result.passed) {
            Button(
                onClick = onNewSession,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
            ) {
                Text("Log more pages", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InkElevated, contentColor = TextPrimary)
            ) {
                Text("Back to library", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
            ) {
                Text("Take a new quiz", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun decodeThumbnail(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}
