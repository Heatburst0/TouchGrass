package com.example.touchgrass.core.service

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.discipline.AccessibilityOffBilling
import com.example.touchgrass.core.discipline.OffState
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.di.ApplicationScope
import com.example.touchgrass.isAccessibilityEnabled
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Charges points while the user keeps the accessibility service switched OFF (the
 * service is what enforces Shorts limits, force-read and focus blocking, so turning
 * it off must cost something). Driven by the existing lifecycle:
 *  - [onServiceDown] from InspectorService.onUnbind/onDestroy opens a "spell";
 *  - [onReminderTick] from ServiceReminderWorker (every 30 min while off) bills it;
 *  - [onServiceUp] from InspectorService.onServiceConnected settles and closes it.
 *
 * Fairness rules (10-min grace, per started half-hour, reboot-aware, no charge for an
 * OS kill where the toggle stayed on) live in the pure [AccessibilityOffBilling].
 * Robustness rules live here:
 *  - every tick is charged under a unique ledger key, so a crash between charging
 *    and checkpointing can never charge the same half-hour twice;
 *  - "switched off" is confirmed the moment the service goes down (not only by the
 *    worker), so a delayed worker can't let a user escape the charge;
 *  - the toggle is read inside the lock, and never while the service is connected,
 *    so a stale worker observation can't reopen a spell that was just settled.
 */
@Singleton
class AccessibilityOffTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val rewards: RewardsManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    /** What a reminder tick saw: whether tracking is off, and points charged this spell. */
    data class TickResult(val off: Boolean, val charged: Int)

    /** An open spell plus how many points it has cost so far (for the notification). */
    private data class Spell(val state: OffState, val charged: Int)

    private val lock = Mutex()

    @Volatile
    private var connected = false

    /** The service went down — open a spell (confirmed now if the toggle is already off). */
    fun onServiceDown() {
        connected = false
        scope.launch {
            lock.withLock {
                val open = load() ?: Spell(AccessibilityOffBilling.start(bootCount(), elapsed()), 0)
                save(if (toggleOff()) open.confirmed() else open)
            }
        }
    }

    /** The service is back — bill the remainder of a confirmed spell, then close it. */
    fun onServiceUp() {
        connected = true
        scope.launch { lock.withLock { settleAndClose() } }
    }

    /** Reminder tick while the service is (believed) down. */
    suspend fun onReminderTick(): TickResult = lock.withLock {
        // Read the toggle under the lock: a tick that raced a reconnect sees the truth.
        if (connected || !toggleOff()) return@withLock TickResult(off = false, charged = settleAndClose())
        val open = load() ?: Spell(AccessibilityOffBilling.start(bootCount(), elapsed()), 0)
        val billed = bill(open.confirmed())
        save(billed)
        TickResult(off = true, charged = billed.charged)
    }

    /** Charge what a confirmed open spell still owes, close it; returns its total cost. */
    private suspend fun settleAndClose(): Int {
        val charged = load()?.let { bill(it).charged } ?: 0
        save(null)
        return charged
    }

    private suspend fun bill(spell: Spell): Spell {
        val bill = AccessibilityOffBilling.bill(spell.state, bootCount(), elapsed())
        var charged = 0
        val firstTick = bill.state.billedTicks - bill.newTicks + 1
        for (tick in firstTick..bill.state.billedTicks) {
            val key = "accessibility_off:${bill.state.bootCount}:${bill.state.sinceElapsedMs}:$tick"
            if (rewards.penalizeOnce(AccessibilityOffBilling.POINTS_PER_TICK, key)) {
                charged += AccessibilityOffBilling.POINTS_PER_TICK
            }
        }
        if (charged > 0) Timber.tag("Discipline").i("Accessibility off: -%d pts", charged)
        return Spell(bill.state, spell.charged + charged)
    }

    private fun Spell.confirmed() = copy(state = AccessibilityOffBilling.confirmOff(state))

    private fun toggleOff(): Boolean = !isAccessibilityEnabled(context)

    private fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun elapsed(): Long = SystemClock.elapsedRealtime()

    private suspend fun load(): Spell? {
        val json = settings.getAccessOffStateJson()
        if (json.isBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            Spell(
                state = OffState(
                    bootCount = o.getInt("boot"),
                    sinceElapsedMs = o.getLong("since"),
                    billedTicks = o.getInt("billed"),
                    confirmedOff = o.getBoolean("confirmed")
                ),
                charged = o.optInt("charged", 0)
            )
        }.getOrNull()
    }

    private suspend fun save(spell: Spell?) {
        val json = spell?.let {
            JSONObject()
                .put("boot", it.state.bootCount)
                .put("since", it.state.sinceElapsedMs)
                .put("billed", it.state.billedTicks)
                .put("confirmed", it.state.confirmedOff)
                .put("charged", it.charged)
                .toString()
        } ?: ""
        settings.setAccessOffStateJson(json)
    }
}
