package com.example.touchgrass.features.reading.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.reading.data.BookRepository
import com.example.touchgrass.features.reading.pdf.PdfBookRenderer
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
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
import timber.log.Timber
import java.io.File
import javax.inject.Inject

data class ReaderUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val startPage: Int = 0,
    val loaded: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BookRepository,
    private val rewards: RewardsManager
) : ViewModel() {

    private val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val creditedPages: StateFlow<Set<Int>> = repo.readPages(bookId)
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val points: StateFlow<Int> = rewards.pointsBalance

    /** Short-lived "+10 pts" flash shown when a page gets credited. */
    private val _awardFlash = MutableStateFlow<String?>(null)
    val awardFlash: StateFlow<String?> = _awardFlash.asStateFlow()

    private var renderer: PdfBookRenderer? = null
    private val bitmapCache = LruCache<Int, Bitmap>(4)
    private var dwellJob: Job? = null

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
     * Dwell verification: a page only counts as read after the user stays on it
     * for [PAGE_DWELL_MS]. Flipping through pages earns nothing.
     */
    fun onPageSettled(pageIndex: Int) {
        viewModelScope.launch { repo.saveLastPage(bookId, pageIndex) }
        dwellJob?.cancel()
        if (pageIndex in creditedPages.value) return
        dwellJob = viewModelScope.launch {
            delay(PAGE_DWELL_MS)
            if (repo.creditPage(bookId, pageIndex)) {
                rewards.awardPageRead(bookId, pageIndex)
                _awardFlash.value = "+${RewardsManager.POINTS_PER_PAGE} pts - page verified"
                delay(2500)
                _awardFlash.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // onCleared is main-thread; the mutex-guarded close is quick
        runBlocking { renderer?.close() }
    }

    companion object {
        const val PAGE_DWELL_MS = 20_000L
    }
}

@Composable
fun ReaderScreen(viewModel: ReaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val credited by viewModel.creditedPages.collectAsState()
    val points by viewModel.points.collectAsState()
    val flash by viewModel.awardFlash.collectAsState()

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
                        val currentCredited = pagerState.currentPage in credited
                        Text(
                            text = "Page ${pagerState.currentPage + 1} of ${state.pageCount}" +
                                    if (currentCredited) "  ·  verified" else "  ·  verifies after 20s",
                            color = if (currentCredited) GrassGreen else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PointsChip(points)
                }

                LaunchedEffect(pagerState.settledPage) {
                    viewModel.onPageSettled(pagerState.settledPage)
                }

                Box(modifier = Modifier.fillMaxSize()) {
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
                            // Vertical scroll for pages taller than the viewport
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
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

                    // "+10 pts" flash (qualified: ColumnScope's overload shadows it here)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = flash != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    ) {
                        Text(
                            text = flash ?: "",
                            color = Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(GrassGreen)
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

private const val RENDER_WIDTH_PX = 1440
