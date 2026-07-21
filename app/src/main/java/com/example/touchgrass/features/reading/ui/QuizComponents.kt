package com.example.touchgrass.features.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.features.reading.quiz.QuizQuestion
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

/**
 * Shared quiz UI used by both verification flows:
 * paper books (photo-based) and PDFs (rendered-page-based).
 */
@Composable
fun QuizQuestionsView(
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
                    androidx.compose.foundation.layout.Box(
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
fun QuizResultView(
    correct: Int,
    total: Int,
    passed: Boolean,
    detail: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$correct/$total",
            color = if (passed) GrassGreen else DangerRed,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (passed) "Verified. You actually read it."
            else "Not quite - that didn't look like a careful read.",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            color = if (passed) GrassGreen else TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) {
            Text(primaryLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSecondary,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkElevated,
                    contentColor = TextPrimary
                )
            ) {
                Text(secondaryLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
