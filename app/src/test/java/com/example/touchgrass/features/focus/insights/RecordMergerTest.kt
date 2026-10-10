package com.example.touchgrass.features.focus.insights

import com.example.touchgrass.core.data.db.FocusSessionEntity
import com.example.touchgrass.core.data.db.FocusStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordMergerTest {
    @Test
    fun syncedPhoneAndLaptopPairBecomeOneItem() {
        val phone = session(id = 1, startedAt = 1_000, endedAt = 10_000, source = "PHONE")
        val laptop = session(id = 2, startedAt = 1_500, endedAt = 9_000, source = "LAPTOP")

        val result = RecordMerger.merge(listOf(laptop, phone))

        assertEquals(1, result.size)
        assertSame(phone, result.single().phone)
        assertSame(laptop, result.single().laptop)
        assertSame(phone, result.single().primary)
    }

    @Test
    fun laptopOutsideToleranceStaysSeparate() {
        val phone = session(id = 1, startedAt = 1_000, endedAt = 10_000, source = "PHONE")
        val laptop = session(
            id = 2,
            startedAt = 10_000 + RecordMerger.PAIR_TOLERANCE_MS + 1,
            endedAt = 200_000,
            source = "LAPTOP"
        )

        val result = RecordMerger.merge(listOf(phone, laptop))

        assertEquals(2, result.size)
        assertTrue(result.any { it.phone === phone && it.laptop == null })
        assertTrue(result.any { it.phone == null && it.laptop === laptop })
    }

    @Test
    fun laptopStartedHoursIntoLongPhoneSessionStaysSeparate() {
        val hour = 3_600_000L
        val phone = session(id = 1, startedAt = 0, endedAt = 4 * hour, source = "PHONE")
        val laptop = session(id = 2, startedAt = 2 * hour, endedAt = 3 * hour, source = "LAPTOP")

        val result = RecordMerger.merge(listOf(phone, laptop))

        assertEquals(2, result.size)
        assertTrue(result.none { it.phone != null && it.laptop != null })
    }

    @Test
    fun onlyClosestOfTwoLaptopsPairsWithPhone() {
        val phone = session(id = 1, startedAt = 100_000, endedAt = 110_000, source = "PHONE")
        val farther = session(id = 2, startedAt = 95_000, endedAt = 105_000, source = "LAPTOP")
        val closer = session(id = 3, startedAt = 100_500, endedAt = 109_000, source = "LAPTOP")

        val result = RecordMerger.merge(listOf(farther, phone, closer))

        assertEquals(2, result.size)
        assertSame(closer, result.single { it.phone != null }.laptop)
        assertNull(result.single { it.laptop === farther }.phone)
    }

    @Test
    fun legacySourceIsPhone() {
        val legacy = session(id = 1, startedAt = 1_000, endedAt = 5_000, source = "")

        val item = RecordMerger.merge(listOf(legacy)).single()

        assertSame(legacy, item.phone)
        assertNull(item.laptop)
        assertEquals(SessionSource.PHONE, legacy.sessionSource())
    }

    @Test
    fun rowsAreOrderedNewestFirst() {
        // Hours apart, so none fall inside the 2-minute pairing window of another.
        val hour = 3_600_000L
        val old = session(id = 1, startedAt = 1 * hour, endedAt = 1 * hour + 1_000, source = "PHONE")
        val newest = session(id = 2, startedAt = 3 * hour, endedAt = 3 * hour + 1_000, source = "PHONE")
        val middle = session(id = 3, startedAt = 2 * hour, endedAt = 2 * hour + 1_000, source = "LAPTOP")

        assertEquals(
            listOf(3 * hour, 2 * hour, 1 * hour),
            RecordMerger.merge(listOf(old, newest, middle)).map(RecordItem::startedAt)
        )
    }

    @Test
    fun statsUsePrimaryAndDoNotDoubleCountPair() {
        val phone = session(
            id = 1,
            startedAt = 1_000,
            endedAt = 2_000,
            source = "PHONE",
            focusedMin = 25,
            outcome = "COMPLETED"
        )
        val laptop = session(
            id = 2,
            startedAt = 1_100,
            endedAt = 2_000,
            source = "LAPTOP",
            focusedMin = 99,
            outcome = "COMPLETED"
        )
        val early = session(
            id = 3,
            startedAt = 500_000,
            endedAt = 501_000,
            source = "PHONE",
            focusedMin = 10,
            outcome = "ENDED_EARLY"
        )

        assertEquals(
            FocusStats(completed = 1, total = 2, focusedMinutes = 35),
            RecordMerger.stats(RecordMerger.merge(listOf(phone, laptop, early)))
        )
    }

    private fun session(
        id: Long,
        startedAt: Long,
        endedAt: Long,
        source: String,
        focusedMin: Int = 1,
        outcome: String = "COMPLETED"
    ): FocusSessionEntity = FocusSessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        plannedFocusMin = 25,
        focusedMin = focusedMin,
        cycles = 1,
        violations = 0,
        strict = false,
        outcome = outcome,
        source = source
    )
}
