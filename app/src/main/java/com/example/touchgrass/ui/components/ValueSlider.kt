package com.example.touchgrass.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import kotlin.math.roundToInt

/** Pure bound/step math behind [ValueSlider]. */
object SliderMath {

    /** The visible track: [range], stretched to include a [value] saved outside it. */
    fun track(range: IntRange, value: Int): IntRange =
        minOf(range.first, value)..maxOf(range.last, value)

    /** Where the value may move: the track narrowed by the locked bounds (never empty). */
    fun allowed(track: IntRange, lockedMin: Int?, lockedMax: Int?): IntRange {
        val lo = maxOf(track.first, lockedMin ?: track.first)
        val hi = minOf(track.last, lockedMax ?: track.last)
        return if (lo <= hi) lo..hi else lo..lo
    }

    /** Snap a raw slider position to the step grid (anchored at the track start), within [allowed]. */
    fun snap(raw: Float, track: IntRange, step: Int, allowed: IntRange): Int {
        val steps = ((raw - track.first) / step).roundToInt()
        return (track.first + steps * step).coerceIn(allowed)
    }

    /** One step down to the previous grid point (an off-grid value lands on the grid). */
    fun stepDown(value: Int, track: IntRange, step: Int, allowed: IntRange): Int {
        val offset = value - track.first
        val down = if (offset % step == 0) value - step else value - offset % step
        return down.coerceIn(allowed)
    }

    /** One step up to the next grid point. */
    fun stepUp(value: Int, track: IntRange, step: Int, allowed: IntRange): Int {
        val offset = value - track.first
        return (track.first + (offset / step + 1) * step).coerceIn(allowed)
    }
}

/**
 * Label + big value + fine ± buttons over a slider, like the home screen's Shorts limit.
 * Dragging only moves a draft; [onValueChange] fires on release (and on each ± tap), so
 * callers can persist directly. [lockedMin]/[lockedMax] stop the value at a bound the
 * caller can't loosen right now, and [lockReason] explains it inline.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    format: (Int) -> String = { it.toString() },
    caption: ((Int) -> String)? = null,
    lockedMin: Int? = null,
    lockedMax: Int? = null,
    lockReason: String? = null
) {
    val track = SliderMath.track(range, value)
    val allowed = SliderMath.allowed(track, lockedMin, lockedMax)
    // Draft while dragging; committed on release.
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val shown = SliderMath.snap(draft, track, step, allowed)

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                caption?.let { Text(it(shown), color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundStepButton("−", enabled = value > allowed.first) {
                    onValueChange(SliderMath.stepDown(value, track, step, allowed))
                }
                Text(
                    format(shown),
                    color = GrassGreen, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 64.dp)
                )
                RoundStepButton("+", enabled = value < allowed.last) {
                    onValueChange(SliderMath.stepUp(value, track, step, allowed))
                }
            }
        }
        Slider(
            value = draft.coerceIn(track.first.toFloat(), track.last.toFloat()),
            onValueChange = { draft = SliderMath.snap(it, track, step, allowed).toFloat() },
            onValueChangeFinished = {
                val committed = SliderMath.snap(draft, track, step, allowed)
                if (committed != value) onValueChange(committed)
            },
            valueRange = track.first.toFloat()..maxOf(track.last, track.first + 1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = GrassGreen,
                activeTrackColor = GrassGreen,
                inactiveTrackColor = InkBorder
            )
        )
        val locked = (lockedMin != null && lockedMin > track.first) || (lockedMax != null && lockedMax < track.last)
        if (locked && lockReason != null) {
            Spacer(Modifier.height(2.dp))
            Text(lockReason, color = AmberWarn, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
