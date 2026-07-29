package com.example.touchgrass.features.reading.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.goals.GoalEngine
import com.example.touchgrass.core.goals.PillarType
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.reading.data.BookRepository
import com.example.touchgrass.features.reading.pdf.PdfBookRenderer
import com.example.touchgrass.features.reading.quiz.QuizGenerationException
import com.example.touchgrass.features.reading.quiz.QuizCoordinator
import com.example.touchgrass.features.reading.quiz.QuizQuestion
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

data class ReaderUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val startPage: Int = 0,
    val loaded: Boolean = false,
    val error: String? = null
)

/** In-reader quiz that converts pending (dwell-read) pages into points. */
sealed interface PdfQuizPhase {
    data object None : PdfQuizPhase
    data object Generating : PdfQuizPhase
    data class Active(val questions: List<QuizQuestion>, val pages: List<Int>) : PdfQuizPhase
    data class Result(
        val correct: Int,
        val total: Int,
        val passed: Boolean,
        val pagesCredited: Int,
        val pointsEarned: Int
    ) : PdfQuizPhase
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repo: BookRepository,
    private val rewards: RewardsManager,
    private val goalEngine: GoalEngine,
    private val quizCoordinator: QuizCoordinator
) : ViewModel() {

    private val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Quiz-verified pages (already paid out). */
    val verifiedPages: StateFlow<Set<Int>> = repo.verifiedPages(bookId)
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Dwell-read pages waiting for a quiz — worth nothing until verified. */
    val pendingPages: StateFlow<Set<Int>> = repo.pendingPages(bookId)
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val points: StateFlow<Int> = rewards.pointsBalance

    private val _quizPhase = MutableStateFlow<PdfQuizPhase>(PdfQuizPhase.None)
    val quizPhase: StateFlow<PdfQuizPhase> = _quizPhase.asStateFlow()

    private val _quizAnswers = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val quizAnswers: StateFlow<Map<Int, Int>> = _quizAnswers.asStateFlow()

    private val _quizError = MutableStateFlow<String?>(null)
    val quizError: StateFlow<String?> = _quizError.asStateFlow()

    /** Short-lived toast-style flash ("Page read — quiz to earn points"). */
    private val _flash = MutableStateFlow<String?>(null)
    val flash: StateFlow<String?> = _flash.asStateFlow()

    private var renderer: PdfBookRenderer? = null
    private val bitmapCache = LruCache<Int, Bitmap>(4)
    private var dwellJob: Job? = null
    private var flashJob: Job? = null

    init {
        viewModelScope.launch {
            val book = repo.getBook(bookId)
            if (book == null || !File(book.filePath).exists()) {
                _uiState.value = ReaderUiState(error = "This book's file is missing.")
            } else {
                renderer = PdfBookRenderer(File(book.filePath))
                _uiState.value = ReaderUiState(
                    title = book.title,
                    pageCount = book.pageCount,
                    startPage = book.lastPage.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0)),
                    loaded = true
                )
            }
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? {
        bitmapCache.get(pageIndex)?.let { return it }
        val bmp = try {
            renderer?.render(pageIndex, targetWidth)
        } catch (e: Exception) {
            Timber.tag("Reading").e(e, "Render failed for page %d", pageIndex)
            null
        }
        if (bmp != null) bitmapCache.put(pageIndex, bmp)
        return bmp
    }

    /**
     * Dwell tracking: after [PAGE_DWELL_MS] on a page it becomes PENDING.
     * No points are awarded here — only the quiz pays out.
     */
    fun onPageSettled(pageIndex: Int) {
        viewModelScope.launch { repo.saveLastPage(bookId, pageIndex) }
        dwellJob?.cancel()
        if (pageIndex in verifiedPages.value || pageIndex in pendingPages.value) return
        dwellJob = viewModelScope.launch {
            delay(PAGE_DWELL_MS)
            if (repo.markPagePending(bookId, pageIndex)) {
                showFlash("Page read - take the quiz to claim +${RewardsManager.POINTS_PER_PAGE} pts")
            }
        }
    }

    // ---- In-reader quiz ----

    fun startQuiz() {
        val pages = pendingPages.value.sorted().take(MAX_QUIZ_PAGES)
        if (pages.isEmpty() || _quizPhase.value == PdfQuizPhase.Generating) return
        _quizError.value = null
        _quizAnswers.value = emptyMap()
        _quizPhase.value = PdfQuizPhase.Generating

        viewModelScope.launch {
            val files = mutableListOf<File>()
            try {
                // Render the pending pages to JPEGs — same input shape as page photos
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "quiz").apply { mkdirs() }
                    pages.forEach { index ->
                        val bmp = renderPage(index, QUIZ_RENDER_WIDTH_PX)
                            ?: throw QuizGenerationException("Couldn't render page ${index + 1}.")
                        val file = File(dir, "pdf_${bookId}_$index.jpg")
                        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        files += file
                    }
                }
                val questions = quizCoordinator.makeQuiz(files)
                _quizPhase.value = PdfQuizPhase.Active(questions, pages)
            } catch (e: QuizGenerationException) {
                _quizPhase.value = PdfQuizPhase.None
                _quizError.value = e.message
            } catch (e: Exception) {
                Timber.tag("Quiz").e(e, "PDF quiz generation failed")
                _quizPhase.value = PdfQuizPhase.None
                _quizError.value = "Something went wrong. Try again."
            } finally {
                withContext(Dispatchers.IO) { files.forEach(File::delete) }
            }
        }
    }

    fun selectAnswer(questionIndex: Int, optionIndex: Int) {
        _quizAnswers.value = _quizAnswers.value + (questionIndex to optionIndex)
    }

    fun submitQuiz() {
        val phase = _quizPhase.value as? PdfQuizPhase.Active ?: return
        val answers = _quizAnswers.value
        if (answers.size < phase.questions.size) return

        val correct = phase.questions.withIndex()
            .count { (i, q) -> answers[i] == q.correctIndex }
        val passed = correct * 3 >= phase.questions.size * 2 // >= 2/3

        viewModelScope.launch {
            var earned = 0
            if (passed) {
                repo.verifyPages(bookId, phase.pages)
                phase.pages.forEach { rewards.awardPageRead(bookId, it) }
                earned = phase.pages.size * RewardsManager.POINTS_PER_PAGE
                // Feed verified pages into any active reading pledge
                goalEngine.recordProgress(PillarType.READING, phase.pages.size)
            }
            _quizPhase.value = PdfQuizPhase.Result(
                correct = correct,
                total = phase.questions.size,
                passed = passed,
                pagesCredited = if (passed) phase.pages.size else 0,
                pointsEarned = earned
            )
        }
    }

    fun retryQuiz() = startQuiz()

    fun closeQuiz() {
        _quizPhase.value = PdfQuizPhase.None
        _quizAnswers.value = emptyMap()
    }

    private fun showFlash(message: String) {
        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            _flash.value = message
            delay(2500)
            _flash.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        // onCleared is main-thread; the mutex-guarded close is quick
        runBlocking { renderer?.close() }
    }

    companion object {
        const val PAGE_DWELL_MS = 20_000L
        const val MAX_QUIZ_PAGES = 4
        private const val QUIZ_RENDER_WIDTH_PX = 1280
    }
}

// ---------------------------------------------------------------------------

@Composable
fun ReaderScreen(viewModel: ReaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val verified by viewModel.verifiedPages.collectAsState()
    val pending by viewModel.pendingPages.collectAsState()
    val points by viewModel.points.collectAsState()
    val flash by viewModel.flash.collectAsState()
    val quizPhase by viewModel.quizPhase.collectAsState()
    val quizAnswers by viewModel.quizAnswers.collectAsState()
    val quizError by viewModel.quizError.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        when {
            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = state.error!!, color = TextSecondary)
                }
            }
            !state.loaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GrassGreen)
                }
            }
            else -> {
                val pagerState = rememberPagerState(
                    initialPage = state.startPage,
                    pageCount = { state.pageCount }
                )

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        val pageStatus = when (pagerState.currentPage) {
                            in verified -> "verified"
                            in pending -> "read - quiz pending"
                            else -> "counts after 20s"
                        }
                        Text(
                            text = "Page ${pagerState.currentPage + 1} of ${state.pageCount}  ·  $pageStatus",
                            color = when (pagerState.currentPage) {
                                in verified -> GrassGreen
                                in pending -> AmberWarn
                                else -> TextSecondary
                            },
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PointsChip(points)
                }

                LaunchedEffect(pagerState.settledPage) {
                    viewModel.onPageSettled(pagerState.settledPage)
                }

                // Body: pager, or the in-reader quiz
                Box(modifier = Modifier.weight(1f)) {
                    when (val phase = quizPhase) {
                        PdfQuizPhase.None -> {
                            PdfPager(pagerState = pagerState, viewModel = viewModel)

                            androidx.compose.animation.AnimatedVisibility(
                                visible = flash != null,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                            ) {
                                Text(
                                    text = flash ?: "",
                                    color = Ink,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(AmberWarn)
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }

                        PdfQuizPhase.Generating -> Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = GrassGreen)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Re-reading your pages and writing the quiz...",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }

                        is PdfQuizPhase.Active -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Quiz on pages ${phase.pages.joinToString { "${it + 1}" }}",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            QuizQuestionsView(
                                questions = phase.questions,
                                answers = quizAnswers,
                                onSelect = viewModel::selectAnswer,
                                onSubmit = viewModel::submitQuiz
                            )
                            Spacer(Modifier.height(24.dp))
                        }

                        is PdfQuizPhase.Result -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                        ) {
                            QuizResultView(
                                correct = phase.correct,
                                total = phase.total,
                                passed = phase.passed,
                                detail = if (phase.passed)
                                    "${phase.pagesCredited} page${if (phase.pagesCredited == 1) "" else "s"} verified  ·  +${phase.pointsEarned} pts"
                                else "Re-read those pages, then quiz again.",
                                primaryLabel = if (phase.passed) "Keep reading" else "Take a new quiz",
                                onPrimary = {
                                    if (phase.passed) viewModel.closeQuiz() else viewModel.retryQuiz()
                                },
                                secondaryLabel = if (phase.passed) null else "Back to reading",
                                onSecondary = if (phase.passed) null else viewModel::closeQuiz
                            )
                        }
                    }
                }

                // Bottom bar: quiz CTA + page scrubber (hidden while quizzing)
                if (quizPhase == PdfQuizPhase.None) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(InkElevated)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        quizError?.let {
                            Text(text = it, color = AmberWarn, fontSize = 12.sp, lineHeight = 16.sp)
                            Spacer(Modifier.height(8.dp))
                        }

                        if (pending.isNotEmpty()) {
                            val batch = minOf(pending.size, ReaderViewModel.MAX_QUIZ_PAGES)
                            Button(
                                onClick = viewModel::startQuiz,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GrassGreen,
                                    contentColor = Ink
                                )
                            ) {
                                Text(
                                    text = "Take quiz - claim $batch page${if (batch == 1) "" else "s"} " +
                                            "(+${batch * RewardsManager.POINTS_PER_PAGE} pts)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        // Page scrubber — drag to jump to any page
                        var sliderPage by remember(pagerState.currentPage) {
                            mutableFloatStateOf((pagerState.currentPage + 1).toFloat())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${sliderPage.roundToInt()}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(34.dp)
                            )
                            Slider(
                                value = sliderPage,
                                onValueChange = { sliderPage = it },
                                onValueChangeFinished = {
                                    scope.launch {
                                        pagerState.scrollToPage(sliderPage.roundToInt() - 1)
                                    }
                                },
                                valueRange = 1f..state.pageCount.coerceAtLeast(1).toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = GrassGreen,
                                    activeTrackColor = GrassGreen,
                                    inactiveTrackColor = InkBorder
                                )
                            )
                            Text(
                                text = "${state.pageCount}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.width(34.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPager(
    pagerState: androidx.compose.foundation.pager.PagerState,
    viewModel: ReaderViewModel
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { pageIndex ->
        val bitmap by produceState<Bitmap?>(initialValue = null, pageIndex) {
            value = viewModel.renderPage(pageIndex, RENDER_WIDTH_PX)
        }
        val bmp = bitmap
        if (bmp == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GrassGreen)
            }
        } else {
            ZoomablePage(bmp = bmp, pageIndex = pageIndex)
        }
    }
}

/**
 * Pinch to zoom (1x–5x, two-finger pan while zoomed), double-tap to toggle.
 * Single-finger gestures keep working: vertical drag scrolls tall pages,
 * horizontal swipe still flips pages via the pager.
 */
@Composable
private fun ZoomablePage(bmp: Bitmap, pageIndex: Int) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(candidate: Offset, atScale: Float): Offset {
        val maxX = viewSize.width * (atScale - 1f) / 2f
        val maxY = viewSize.height * (atScale - 1f) / 2f
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            // Custom pinch handler in the Initial pass: it must win over BOTH the
            // page's vertical scroll and the pager's horizontal swipe — but only
            // for multi-touch (or single-finger pan while zoomed). One finger at
            // 1x is never consumed, so page swiping and scrolling stay intact.
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressedCount = event.changes.count { it.pressed }
                        when {
                            // Two fingers: pinch to zoom + pan
                            pressedCount >= 2 -> {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
                                scale = newScale
                                offset = if (newScale > 1f) {
                                    clampOffset(offset + panChange, newScale)
                                } else Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                            // One finger while zoomed: pan the page, don't flip it
                            pressedCount == 1 && scale > 1f -> {
                                val panChange = event.calculatePan()
                                if (panChange != Offset.Zero) {
                                    offset = clampOffset(offset + panChange, scale)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(pageIndex) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
    ) {
        // Vertical scroll for pages taller than the viewport
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        }
    }
}

private const val RENDER_WIDTH_PX = 1440
private const val MAX_ZOOM = 5f
