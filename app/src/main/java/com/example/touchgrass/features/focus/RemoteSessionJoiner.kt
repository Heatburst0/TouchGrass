package com.example.touchgrass.features.focus

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.remote.AuthRepository
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import com.example.touchgrass.core.sync.DeviceIdentity
import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** The shared live-session row, as far as joining is concerned. */
data class LiveSession(
    val active: Boolean,
    val startMs: Long?,
    val originDeviceId: String?,
    val blockPhone: Boolean,
    val focusMin: Int,
    val breakMin: Int,
    val cycles: Int,
    val label: String?
) {
    /** When the session naturally ends (null if it hasn't started). */
    val endMs: Long? get() = startMs?.let { it + (cycles * focusMin + (cycles - 1) * breakMin) * 60_000L }
}

/** What the phone should do about the live session — pure, so it's unit-tested. */
object JoinDecision {
    sealed interface Action {
        data class Join(val session: LiveSession) : Action
        data object EndEarly : Action
        data object None : Action
    }

    /** A joined session this close to its end just finishes on its own (clocks differ slightly). */
    const val NATURAL_END_SLACK_MS = 2 * 60_000L

    /**
     * @param joinedStartMs start of the last session we joined (we join AT that start,
     *   so the local session carries the same start time)
     * @param localStartAt start of the session running on the phone, null if none
     */
    fun decide(
        live: LiveSession?,
        myDeviceId: String,
        joinedStartMs: Long?,
        localStartAt: Long?,
        localRemainingMs: Long,
        now: Long
    ): Action {
        val elsewhere = live?.takeIf { it.active && it.startMs != null && it.originDeviceId != myDeviceId }
        val stillWorthJoining = elsewhere?.endMs?.let { now < it - NATURAL_END_SLACK_MS } == true
        val runningOurJoin = localStartAt != null && localStartAt == joinedStartMs
        return when {
            elsewhere != null && elsewhere.blockPhone && elsewhere.startMs != joinedStartMs &&
                localStartAt == null && stillWorthJoining -> Action.Join(elsewhere)
            // The laptop ended the session we joined: stop too, unless we're about to finish anyway.
            elsewhere?.startMs != joinedStartMs && runningOurJoin &&
                localRemainingMs > NATURAL_END_SLACK_MS -> Action.EndEarly
            else -> Action.None
        }
    }
}

@Serializable
private data class RemoteLive(
    val active: Boolean = false,
    val started_at: String? = null,
    val origin_device_id: String? = null,
    val config: JsonObject = JsonObject(emptyMap())
)

/**
 * Makes the phone follow focus sessions started on the laptop — e.g. the agent's
 * auto-focus when you start working on a project — so distracting phone apps are
 * blocked too. Only joins when the session asked for it ("blockPhone"), never while
 * the phone already runs its own session. Ending the joined session on the phone
 * clears the shared row, which stops the laptop as well.
 */
@Singleton
class RemoteSessionJoiner @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    private val deviceIdentity: DeviceIdentity,
    private val settings: SettingsRepository,
    private val manager: FocusSessionManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val lock = Mutex()
    @Volatile private var joinedStartMs: Long? = null
    @Volatile private var realtimeStarted = false

    init {
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) {
                        check()
                        startRealtime()
                    }
                }
            }
        }
    }

    private suspend fun check() = lock.withLock {
        runCatching {
            val row = supabase.from("active_sessions").select().decodeList<RemoteLive>().firstOrNull()
            val now = System.currentTimeMillis()
            val local = manager.activeSession.value?.takeIf { focusPhaseAt(it, now) !is FocusPhase.Done }
            val action = JoinDecision.decide(
                live = row?.toDomain(),
                myDeviceId = deviceIdentity.current().id,
                joinedStartMs = joinedStartMs,
                localStartAt = local?.startAt,
                localRemainingMs = local?.let { it.endAt - now } ?: 0,
                now = now
            )
            when (action) {
                is JoinDecision.Action.Join -> join(action.session)
                JoinDecision.Action.EndEarly -> {
                    Timber.tag("Focus").i("Laptop ended the joined session — ending here too")
                    manager.endEarly()
                }
                JoinDecision.Action.None -> Unit
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.tag("Focus").w(it, "live session check failed")
        }
    }

    private suspend fun join(live: LiveSession) {
        val startMs = live.startMs ?: return
        joinedStartMs = startMs
        val blocked = settings.focusBlockedPackages.first().ifEmpty { ScreenTimeNudger.WATCHED_PACKAGES }
        Timber.tag("Focus").i("Joining laptop session %s (%d min)", live.label.orEmpty(), live.focusMin)
        manager.start(
            FocusConfig(
                focusBlockMin = live.focusMin,
                breakMin = live.breakMin,
                cycles = live.cycles,
                blockedPackages = blocked
            ),
            syncToLaptop = false, // it's the laptop's session; don't re-broadcast
            startAt = startMs // same start as the laptop: same end, and the records pair up
        )
    }

    private fun startRealtime() {
        if (realtimeStarted) return
        realtimeStarted = true
        scope.launch {
            runCatching {
                val channel = supabase.channel("live-session-join")
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "active_sessions"
                }
                channel.subscribe()
                changes.collect { check() }
            }.onFailure { Timber.tag("Focus").w(it, "live session realtime failed") }
        }
    }

    private fun RemoteLive.toDomain(): LiveSession {
        fun int(key: String, default: Int) = config[key]?.jsonPrimitive?.intOrNull ?: default
        return LiveSession(
            active = active,
            startMs = started_at?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            originDeviceId = origin_device_id,
            blockPhone = config["blockPhone"]?.jsonPrimitive?.booleanOrNull ?: false,
            focusMin = int("focusBlockMin", 25).coerceIn(1, 240),
            breakMin = int("breakMin", 0).coerceIn(0, 60),
            cycles = int("cycles", 1).coerceIn(1, 12),
            label = config["label"]?.jsonPrimitive?.content
        )
    }
}
