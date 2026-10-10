package com.example.touchgrass.features.focus.insights

import com.example.touchgrass.core.data.db.FocusSessionEntity
import com.example.touchgrass.core.data.db.FocusStats
import kotlin.math.abs

/** One row of the user's focus record: a phone session, a laptop session, or both. */
data class RecordItem(
    val phone: FocusSessionEntity?,
    val laptop: FocusSessionEntity?
) {
    val primary: FocusSessionEntity
        get() = phone ?: checkNotNull(laptop)

    val startedAt: Long
        get() = primary.startedAt

    val completed: Boolean
        get() = primary.outcome == COMPLETED_OUTCOME

    private companion object {
        const val COMPLETED_OUTCOME = "COMPLETED"
    }
}

/** Combines matching phone and laptop sessions into user-visible record rows. */
object RecordMerger {
    const val PAIR_TOLERANCE_MS: Long = 120_000

    /** Merges matching phone and laptop sessions and returns newest rows first. */
    fun merge(sessions: List<FocusSessionEntity>): List<RecordItem> {
        val phones = sessions.filter { it.sessionSource() == SessionSource.PHONE }
        val laptops = sessions.filter { it.sessionSource() == SessionSource.LAPTOP }
        // A synced laptop joins within one poll of the phone starting, so pair on start-to-start
        // distance — a laptop session begun hours into a long phone session stays separate.
        val candidates = laptops.flatMapIndexed { laptopIndex, laptop ->
            phones.mapIndexedNotNull { phoneIndex, phone ->
                val gap = distance(laptop.startedAt, phone.startedAt)
                if (gap <= PAIR_TOLERANCE_MS) PairCandidate(laptopIndex, phoneIndex, gap) else null
            }
        }.sortedWith(
            compareBy<PairCandidate> { it.distance }
                .thenBy { laptops[it.laptopIndex].startedAt }
                .thenBy { phones[it.phoneIndex].startedAt }
        )

        val pairedLaptops = mutableSetOf<Int>()
        val pairedPhones = mutableSetOf<Int>()
        val items = mutableListOf<RecordItem>()
        candidates.forEach { candidate ->
            if (candidate.laptopIndex !in pairedLaptops && candidate.phoneIndex !in pairedPhones) {
                pairedLaptops += candidate.laptopIndex
                pairedPhones += candidate.phoneIndex
                items += RecordItem(
                    phone = phones[candidate.phoneIndex],
                    laptop = laptops[candidate.laptopIndex]
                )
            }
        }
        phones.forEachIndexed { index, phone ->
            if (index !in pairedPhones) items += RecordItem(phone = phone, laptop = null)
        }
        laptops.forEachIndexed { index, laptop ->
            if (index !in pairedLaptops) items += RecordItem(phone = null, laptop = laptop)
        }
        return items.sortedByDescending(RecordItem::startedAt)
    }

    /** Summarizes completed sessions, total rows, and focused minutes without double counting pairs. */
    fun stats(items: List<RecordItem>): FocusStats = FocusStats(
        completed = items.count(RecordItem::completed),
        total = items.size,
        focusedMinutes = items.sumOf { it.primary.focusedMin }
    )

    private fun distance(first: Long, second: Long): Long {
        val difference = first - second
        val overflowed = (first xor second) < 0 && (first xor difference) < 0
        return if (overflowed || difference == Long.MIN_VALUE) Long.MAX_VALUE else abs(difference)
    }

    private data class PairCandidate(
        val laptopIndex: Int,
        val phoneIndex: Int,
        val distance: Long
    )
}
