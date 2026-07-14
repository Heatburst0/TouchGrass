package com.example.touchgrass.features.reading.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.data.db.BookEntity
import com.example.touchgrass.core.data.db.BookWithProgress
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.reading.data.BookRepository
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: BookRepository,
    rewards: RewardsManager
) : ViewModel() {
    val books: StateFlow<List<BookWithProgress>> = repo.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val points: StateFlow<Int> = rewards.pointsBalance

    var importing by mutableStateOf(false)
        private set
    var importError by mutableStateOf<String?>(null)
        private set

    fun addBook(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            importing = true
            importError = null
            try {
                repo.addBook(uri)
            } catch (e: Exception) {
                Timber.tag("Reading").e(e, "Import failed")
                importError = e.message ?: "Could not import that PDF"
            } finally {
                importing = false
            }
        }
    }

    fun addPhysicalBook(title: String) {
        viewModelScope.launch { repo.addPhysicalBook(title) }
    }
}

@Composable
fun LibraryScreen(
    onOpenBook: (BookWithProgress) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val books by viewModel.books.collectAsState()
    val points by viewModel.points.collectAsState()
    var showPaperDialog by remember { mutableStateOf(false) }

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.addBook(uri) }

    if (showPaperDialog) {
        AddPaperBookDialog(
            onConfirm = { title ->
                viewModel.addPhysicalBook(title)
                showPaperDialog = false
            },
            onDismiss = { showPaperDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Library",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Every verified page = ${RewardsManager.POINTS_PER_PAGE} pts. " +
                                "${RewardsManager.SHORTS_UNLOCK_COST} pts = +${RewardsManager.SHORTS_UNLOCK_AMOUNT} shorts.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                PointsChip(points)
            }
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                    enabled = !viewModel.importing,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
                ) {
                    if (viewModel.importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Ink,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Add PDF", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = { showPaperDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkElevated,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Add paper book", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            viewModel.importError?.let {
                Spacer(Modifier.height(6.dp))
                Text(text = it, color = AmberWarn, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "PDFs verify by reading time in the app. Paper books verify by page photos + an AI quiz.",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        if (books.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No books yet.\nAdd a PDF and start earning your shorts back.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            items(books, key = { it.book.id }) { item ->
                BookCard(item, onClick = { onOpenBook(item) })
            }
        }
    }
}

@Composable
private fun AddPaperBookDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        title = { Text("Add a paper book", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "Log reading sessions by photographing pages; a short AI quiz verifies them.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("Book title", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = GrassGreen,
                        unfocusedBorderColor = InkBorder,
                        cursorColor = GrassGreen
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank()
            ) {
                Text("Add", color = if (title.isNotBlank()) GrassGreen else TextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
fun PointsChip(points: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(AmberWarn)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$points pts",
            color = AmberWarn,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BookCard(item: BookWithProgress, onClick: () -> Unit) {
    val isPhysical = item.book.type == BookEntity.TYPE_PHYSICAL
    val progress =
        if (item.book.pageCount > 0) item.pagesRead.toFloat() / item.book.pageCount else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPhysical) "PAPER" else "PDF",
                    color = if (isPhysical) AmberWarn else GrassGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            (if (isPhysical) AmberWarn else GrassGreen).copy(alpha = 0.12f)
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.book.title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Text(
                text = if (isPhysical) "${item.pagesRead} verified"
                else "${item.pagesRead}/${item.book.pageCount} pages",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        if (!isPhysical) {
            Spacer(Modifier.height(12.dp))
            // Slim custom progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(InkBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(GrassGreen)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${item.pagesRead * RewardsManager.POINTS_PER_PAGE} pts earned from this book" +
                    if (isPhysical) "  ·  tap to log a session" else "",
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}
