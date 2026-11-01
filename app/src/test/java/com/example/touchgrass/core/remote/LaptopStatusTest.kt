package com.example.touchgrass.core.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LaptopStatusTest {

    private val now = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun `agent is online within the window and last-seen after it`() {
        assertEquals(LaptopStatus.Online, LaptopStatus.of(now.minus(Duration.ofMinutes(30)), now))
        assertEquals(
            LaptopStatus.LastSeen(Duration.ofMinutes(31)),
            LaptopStatus.of(now.minus(Duration.ofMinutes(31)), now)
        )
        assertEquals(LaptopStatus.NotConnected, LaptopStatus.of(null, now))
    }

    @Test
    fun `a clock-skewed future timestamp still reads as online`() {
        assertEquals(LaptopStatus.Online, LaptopStatus.of(now.plusSeconds(90), now))
    }
}
