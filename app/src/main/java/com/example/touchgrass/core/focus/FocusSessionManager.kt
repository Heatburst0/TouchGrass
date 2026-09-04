package com.example.touchgrass.core.focus

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the (single) active focus session. The session is stored as start-time +
 * config in DataStore, so the phase is a pure function of the wall clock — it
 * survives process death and needs no foreground service for the MVP. The
 * accessibility service reads [shouldBlock] synchronously to bounce distractions
 * during focus blocks.
 */
@Singleton
class FocusSessionManager @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile
    private var cached: ActiveFocus? = null

    /** The active session (or null). The UI ticks a 1s clock against it. */
    val activeSession: StateFlow<ActiveFocus?> =
        settings.activeFocusJson
            .map { activeFocusFromJson(it) }
            .onEach { cached = it }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Distraction opens attempted during focus blocks this session. */
    var violations = 0
        private set

    fun start(config: FocusConfig) {
        violations = 0
        scope.launch {
            settings.setActiveFocusJson(ActiveFocus(System.currentTimeMillis(), config).toJson())
        }
    }

    fun stop() {
        scope.launch { settings.setActiveFocusJson("") }
    }

    fun phaseNow(now: Long = System.currentTimeMillis()): FocusPhase = focusPhaseAt(cached, now)

    /** Synchronous read for the accessibility service. */
    fun shouldBlock(packageName: String): Boolean {
        val a = cached ?: return false
        return focusPhaseAt(a, System.currentTimeMillis()) is FocusPhase.Focusing &&
            packageName in a.config.blockedPackages
    }

    fun registerViolation() {
        violations++
    }
}
