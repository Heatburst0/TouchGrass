package com.example.touchgrass.core.discipline

import org.junit.Assert.*
import org.junit.Test

class AccessibilityOffBillingTest {
    @Test
    fun `start records the boot and elapsed clock without confirming off`() {
        assertEquals(OffState(BOOT_COUNT, START_MS, 0, false), initialState())
    }

    @Test
    fun `within grace and exactly at grace no ticks are due`() {
        val state = confirmedState()
        assertEquals(0, billAtMinutes(state, 5).newTicks)
        assertEquals(0, billAtMinutes(state, 10).newTicks)
    }

    @Test
    fun `just past grace bills one tick worth ten points`() {
        val bill = AccessibilityOffBilling.bill(
            confirmedState(), BOOT_COUNT, START_MS + AccessibilityOffBilling.GRACE_MS + 1
        )
        assertEquals(1, bill.newTicks)
        assertEquals(10, bill.points)
        assertEquals(1, bill.state.billedTicks)
    }

    @Test
    fun `exactly ten plus thirty minutes still bills only one tick`() {
        assertEquals(1, billAtMinutes(confirmedState(), 40).newTicks)
    }

    @Test
    fun `ten plus thirty plus one minutes bills two ticks`() {
        val bill = billAtMinutes(confirmedState(), 41)
        assertEquals(2, bill.newTicks)
        assertEquals(20, bill.points)
    }

    @Test
    fun `repeated bills charge only newly started ticks`() {
        val first = billAtMinutes(confirmedState(), 11)
        val repeated = billAtMinutes(first.state, 11)
        val sameTick = billAtMinutes(repeated.state, 40)
        val nextTick = billAtMinutes(sameTick.state, 41)
        assertEquals(1, first.newTicks)
        assertEquals(0, repeated.newTicks)
        assertEquals(0, sameTick.newTicks)
        assertEquals(1, nextTick.newTicks)
        assertEquals(2, nextTick.state.billedTicks)
    }

    @Test
    fun `unconfirmed off state never bills even after hours`() {
        val state = initialState()
        val bill = billAtMinutes(state, 600)
        assertEquals(0, bill.newTicks)
        assertEquals(0, bill.points)
        assertEquals(state, bill.state)
    }

    @Test
    fun `confirming off preserves all other state`() {
        val state = OffState(BOOT_COUNT, START_MS, 2, false)
        assertEquals(state.copy(confirmedOff = true), AccessibilityOffBilling.confirmOff(state))
    }

    @Test
    fun `reboot discards previous boot billing and gives ten minutes from boot free`() {
        val oldState = billAtMinutes(confirmedState(), 600).state
        val rebooted = AccessibilityOffBilling.bill(oldState, NEXT_BOOT_COUNT, 10 * MINUTE_MS)
        assertEquals(0, rebooted.newTicks)
        assertEquals(OffState(NEXT_BOOT_COUNT, 0, 0, true), rebooted.state)
        val afterGrace = AccessibilityOffBilling.bill(
            rebooted.state, NEXT_BOOT_COUNT, 10 * MINUTE_MS + 1
        )
        assertEquals(1, afterGrace.newTicks)
        assertEquals(1, afterGrace.state.billedTicks)
    }

    @Test
    fun `first bill after reboot charges only elapsed time on new boot`() {
        val bill = AccessibilityOffBilling.bill(confirmedState(), NEXT_BOOT_COUNT, 41 * MINUTE_MS)
        assertEquals(2, bill.newTicks)
        assertEquals(OffState(NEXT_BOOT_COUNT, 0, 2, true), bill.state)
    }

    @Test
    fun `reboot keeps unconfirmed state unconfirmed`() {
        val bill = AccessibilityOffBilling.bill(initialState(), NEXT_BOOT_COUNT, 600 * MINUTE_MS)
        assertEquals(0, bill.newTicks)
        assertEquals(OffState(NEXT_BOOT_COUNT, 0, 0, false), bill.state)
    }

    @Test
    fun `negative elapsed time clamps to zero and preserves already billed ticks`() {
        val state = confirmedState().copy(billedTicks = 2)
        val bill = AccessibilityOffBilling.bill(state, BOOT_COUNT, START_MS - 1)
        assertEquals(0, bill.newTicks)
        assertEquals(state, bill.state)
    }

    private fun initialState(): OffState = AccessibilityOffBilling.start(BOOT_COUNT, START_MS)

    private fun confirmedState(): OffState = AccessibilityOffBilling.confirmOff(initialState())

    private fun billAtMinutes(state: OffState, minutes: Long): Bill =
        AccessibilityOffBilling.bill(state, BOOT_COUNT, START_MS + minutes * MINUTE_MS)

    private companion object {
        const val BOOT_COUNT = 7
        const val NEXT_BOOT_COUNT = 8
        const val MINUTE_MS = 60_000L
        const val START_MS = 123_000L
    }
}
